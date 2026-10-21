"""`netbench run`: trials x videos x variants -> results/<run_id>.jsonl (one JSON object per line).

Line types: header (reproducibility block), attempt (one per trial x video x variant), footer
(totals and why the run ended). A run stopped by a bot wall / 429 / cap still gets its footer.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import pathlib
import platform
import sys
import time
import traceback

import curl_cffi

from . import HARNESS_DIR, RESULTS_DIR, harness_version
from .classify import cell_label, deliveries, verdict
from .identity import Identity, IdentityStore
from .innertube import analyse, build_request, parse_player_response, po_token_hints
from .media import MediaProber
from .net import Net, StopRun, Ledger
from .playerjs import CACHE_DIR, PlayerJS, deno_version, ytdlp_commit
from .util import fingerprint, jdump, now_iso, redact_obj
from .variants import apply_axes, load_variants, public_view, runnable, select

EXIT_OK, EXIT_ERROR, EXIT_USAGE, EXIT_STOPPED, EXIT_CAP = 0, 1, 2, 3, 4
# Default corpus: the corpus agent's ../corpus.json plus the Pixel fixtures; seed.json only if the
# main corpus is missing.
DEFAULT_CORPORA = [HARNESS_DIR.parent / 'corpus.json', HARNESS_DIR / 'corpus' / 'pixel-2026-09-28.json']
SEED_CORPUS = HARNESS_DIR / 'corpus' / 'seed.json'


def log(msg=''):
    print(msg, file=sys.stderr, flush=True)


def load_corpus(paths, video_filter: list[str] | None, categories: list[str] | None,
                include_controls: bool = False):
    """One or more corpus files (list, or {"videos": [...]}) merged by id: a later file's fields
    override an earlier one's (its subcategory is added to `subcategories`). --categories matches
    category or any subcategory; expected_signed_out=fail controls are skipped unless
    --include-controls or named in --videos."""
    if isinstance(paths, str):
        paths = [paths]
    if not paths:
        paths = [str(p) for p in DEFAULT_CORPORA if p.exists()]
        if not DEFAULT_CORPORA[0].exists():
            paths = [str(SEED_CORPUS)] + paths
    merged, order, infos = {}, [], []
    for path in paths:
        cpath = pathlib.Path(path)
        data = json.loads(cpath.read_text())
        videos = data.get('videos') if isinstance(data, dict) else data
        if not isinstance(videos, list):
            raise SystemExit(f'{cpath}: expected a list of videos or {{"videos": [...]}}')
        infos.append({'path': str(cpath), 'sha256': hashlib.sha256(cpath.read_bytes()).hexdigest()[:12],
                      'videos': len(videos)})
        for v in videos:
            if isinstance(v, str):
                v = {'id': v}
            if not v.get('id'):
                continue
            v = dict(v)
            v.setdefault('category', 'uncategorized')
            if v['id'] in merged:
                old = merged[v['id']]
                subs = list(old.get('subcategories') or [old.get('subcategory')])
                if v.get('subcategory') and v['subcategory'] not in subs:
                    subs.append(v['subcategory'])
                old.update(v)
                old['subcategories'] = [x for x in subs if x]
                old.setdefault('sources', []).append(cpath.name)
            else:
                v['sources'] = [cpath.name]
                v['subcategories'] = [v['subcategory']] if v.get('subcategory') else []
                merged[v['id']] = v
                order.append(v['id'])
    out = [merged[i] for i in order]
    if video_filter:
        ids = [x for spec in video_filter for x in spec.split(',') if x]
        out = [merged.get(i) or {'id': i, 'category': 'adhoc', 'subcategories': []} for i in ids]
    if categories:
        cats = {x for spec in categories for x in spec.split(',') if x}
        out = [v for v in out if v.get('category') in cats or cats & set(v.get('subcategories') or [])]
    skipped_controls = []
    if not include_controls and not video_filter:
        # expected_signed_out=fail videos are controls (private, removed, region-blocked, ...): opt-in only
        skipped_controls = [v['id'] for v in out if str(v.get('expected_signed_out')).lower() == 'fail']
        out = [v for v in out if str(v.get('expected_signed_out')).lower() != 'fail']
    return out, {'files': infos, 'videos_selected': len(out), 'controls_skipped': skipped_controls}


def _ua_hint(variant):
    return variant.get('user_agent') or (variant.get('client') or {}).get('userAgent')


def dry_run(args, videos, selected, player_sts):
    """Build every request with placeholder identities; never touches the network."""
    shown = 0
    vids = videos if args.show_all else videos[:1]
    for video in vids:
        for v in selected:
            idc = v['identity']
            ident = None
            if idc.get('pages'):
                ident = Identity(key=('dry',))
                ident.visitor = f'DRYRUN-VISITOR-from-{idc["visitor"]}' if idc.get('visitor') else None
                ident.flags = f'DRYRUN-FLAGS-from-{idc["flags"]}' if idc.get('flags') else None
                ident.cookies = {'DRYRUN_PAGE_COOKIE': 'x'} if idc.get('cookies') else {}
            req = build_request(v, video['id'], ident, player_sts)
            print(f'### {v["id"]}  video={video["id"]} ({video.get("category")})  tags={v.get("tags")}')
            if v.get('_extends_chain'):
                print(f'# extends: {" <- ".join(v["_extends_chain"])}')
            print(f'# identity: pages={idc.get("pages")} visitor={idc.get("visitor")} flags={idc.get("flags")} '
                  f'context={idc.get("context")} cookies={idc.get("cookies")} scope={idc.get("scope")} '
                  f'session={idc.get("session") or v["id"]}')
            print(f'# transport: {v.get("transport")}  media: range={v["media"].get("range_mode")} '
                  f'impersonate={v["media"].get("impersonate")}')
            for n in req['notes']:
                print(f'# note: {n}')
            print(f'{req["method"]} {req["url"]}')
            for k, val in req['headers'].items():
                print(f'{k}: {val}')
            if req['body_obj'] is not None:
                print(json.dumps(req['body_obj'], indent=2, ensure_ascii=False))
            print()
            shown += 1
    log(f'dry-run: {shown} request(s) built for {len(selected)} variant(s); no network used')
    return EXIT_OK


def run(args) -> int:
    try:
        videos, corpus_info = load_corpus(args.corpus, args.videos, args.categories, args.include_controls)
        variants, files = load_variants(args.variants)
    except Exception as e:
        log(f'error: {e}')
        return EXIT_USAGE
    try:
        # ids/excludes pick the BASE variants; tag filters apply after axis expansion
        selected = apply_axes(select(runnable(variants), args.variant_ids, None, args.exclude), args.axis)
        selected = select(selected, None, args.only_tags)
    except Exception as e:
        log(f'error: {e}')
        return EXIT_USAGE
    if not selected:
        log('error: no variant selected (check --variants / --variant-ids / --only-tags)')
        return EXIT_USAGE
    if not videos:
        log('error: no video selected')
        return EXIT_USAGE

    if args.dry_run:
        sts = args.sts
        if sts is None:
            cached = sorted((CACHE_DIR / 'player').glob('*-main.js'), key=lambda p: p.stat().st_mtime)
            if cached:
                import re
                m = re.search(r'(?:signatureTimestamp|sts)\s*:\s*([0-9]{5})', cached[-1].read_text())
                sts = int(m.group(1)) if m else None
        return dry_run(args, videos, selected, sts or 99999)

    schedule = sorted({float(x) for x in args.probe_schedule.split(',') if x.strip() != ''})
    media_delay = max(1.0, args.media_delay_s)
    delay = max(3.0, args.delay_s)
    planned = args.trials * len(videos) * len(selected)
    run_id = args.run_id or f'{time.strftime("%Y%m%d-%H%M%S")}-{args.network}'
    out_dir = pathlib.Path(args.out_dir or RESULTS_DIR)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f'{run_id}.jsonl'
    raw_dir = out_dir / run_id if args.save_responses else None
    if raw_dir:
        raw_dir.mkdir(parents=True, exist_ok=True)

    verbose_log = log if args.verbose else (lambda *a, **k: None)
    net = Net(proxy=args.proxy, delay_youtube=delay, delay_media=media_delay, max_requests=args.max_requests,
              max_media_requests=args.max_media_requests, log=verbose_log)
    log(f'netbench run {run_id}: {len(selected)} variant(s) x {len(videos)} video(s) x {args.trials} trial(s) = '
        f'{planned} attempt(s); network={args.network} proxy={args.proxy or "none"} delay={delay}s '
        f'media-delay={media_delay}s cap={args.max_requests}/{args.max_media_requests}')
    log(f'  -> {out_path}')

    fh = out_path.open('w')
    stopped = None
    exit_code = EXIT_OK
    attempts = 0
    started = time.time()

    def write(obj):
        fh.write(jdump(obj) + '\n')
        fh.flush()

    header = {
        'type': 'header', 'run_id': run_id, 'ts': now_iso(), 'network': args.network, 'proxy': args.proxy,
        'harness_version': harness_version(), 'curl_cffi': curl_cffi.__version__,
        'python': platform.python_version(), 'deno': deno_version(), 'ytdlp': ytdlp_commit(),
        'corpus': corpus_info, 'variant_files': files, 'trials': args.trials,
        'schedule_s': schedule, 'max_wait_s': args.max_wait_s, 'delay_s': delay, 'media_delay_s': media_delay,
        'max_requests': args.max_requests, 'max_media_requests': args.max_media_requests,
        'targets': args.targets, 'target_order': args.target_order,
        'argv': sys.argv[1:], 'planned_attempts': planned, 'axes': args.axis,
        'safety': {'cookies_from_disk_or_browser': False, 'ytdlp_config_loaded': False, 'account': False,
                   'po_token': 'none', 'cookies_sent': 'only cookies set by this run\'s own anonymous page fetches, '
                                                     'and only for variants with identity.cookies=true'},
        'videos': videos,
        'variants': [public_view(v) for v in selected],
    }
    player = PlayerJS(net, log=verbose_log)
    try:
        header['player'] = player.bootstrap(args.player_id, args.sts)
        header['ytdlp']['version'] = player.ytdlp_version
        header['ytdlp']['ejs'] = player.ejs_version
    except StopRun as e:
        header['player'] = {'error': e.message}
        write(header)
        stopped = {'code': e.code, 'message': e.message}
        write({'type': 'footer', 'run_id': run_id, 'ts': now_iso(), 'stopped': stopped, 'attempts': 0,
               'totals': Ledger.delta(Ledger().snapshot(), net.ledger.snapshot())})
        fh.close()
        log(f'STOPPED during bootstrap: {e.message}')
        return EXIT_STOPPED
    except Exception as e:
        header['player'] = {'error': f'{type(e).__name__}: {e}'}
        write(header)
        write({'type': 'footer', 'run_id': run_id, 'ts': now_iso(), 'stopped': {'code': 'bootstrap-error',
               'message': str(e)}, 'attempts': 0, 'totals': Ledger.delta(Ledger().snapshot(), net.ledger.snapshot())})
        fh.close()
        log(f'bootstrap failed: {e}')
        traceback.print_exc()
        return EXIT_ERROR
    write(header)
    log(f'  player {player.player_id} sts={player.sts} ({player.source}); yt-dlp {header["ytdlp"].get("commit")}')

    identities = IdentityStore(net, log=verbose_log)
    only_targets = [x for spec in (args.targets or []) for x in spec.split(',') if x] or None
    prober = MediaProber(net, player, schedule, args.max_wait_s, log=verbose_log, only_targets=only_targets,
                         target_order=args.target_order)
    try:
        for trial in range(1, args.trials + 1):
            for video in videos:
                for variant in selected:
                    attempts += 1
                    rec = attempt(net, player, identities, prober, variant, video, trial, run_id, args, raw_dir)
                    write(rec)
                    v = rec['verdict']
                    log(f'[{attempts}/{planned}] t{trial} {video["id"]} {video.get("category", ""):<14} '
                        f'{variant["id"]:<28} {rec["player"].get("status") or "-":>4} '
                        f'{rec["player"].get("ms") or 0:>6.0f}ms  {cell_label(v, rec.get("deliveries")):<34} '
                        f'up={rec["bytes"]["total"]["up"]} down={rec["bytes"]["total"]["down"]}')
                    if rec.get('stop'):
                        raise StopRun(rec['stop']['code'], rec['stop']['message'])
    except StopRun as e:
        stopped = {'code': e.code, 'message': e.message}
        exit_code = EXIT_CAP if e.code == 'request-cap' else EXIT_STOPPED
        log(f'\nSTOPPED: {e.message}')
    except KeyboardInterrupt:
        stopped = {'code': 'interrupted', 'message': 'KeyboardInterrupt'}
        exit_code = EXIT_STOPPED
        log('\ninterrupted')
    finally:
        totals = Ledger.delta(Ledger().snapshot(), net.ledger.snapshot())
        write({'type': 'footer', 'run_id': run_id, 'ts': now_iso(), 'stopped': stopped, 'attempts': attempts,
               'planned_attempts': planned, 'elapsed_s': round(time.time() - started, 1), 'totals': totals,
               'solve_errors': player.solve_errors[:20]})
        fh.close()
        net.close()
    t = totals
    log(f'done: {attempts} attempt(s); youtube requests={t["youtube"]["requests"]} media requests='
        f'{t["media"]["requests"]}; bytes up={t["total"]["up"]} down={t["total"]["down"]} '
        f'(+TLS est up={t["total"]["tls_est_up"]} down={t["total"]["tls_est_down"]})')
    log(f'results: {out_path}')
    return exit_code


def attempt(net, player, identities, prober, variant, video, trial, run_id, args, raw_dir) -> dict:
    vid = video['id']
    before = net.ledger.snapshot()
    rec = {
        'type': 'attempt', 'ts': now_iso(), 'run_id': run_id, 'network': args.network, 'trial': trial,
        'video_id': vid, 'category': video.get('category'), 'subcategory': video.get('subcategory'),
        'made_for_kids': video.get('made_for_kids'), 'variant_id': variant['id'],
        'variant_file': pathlib.Path(variant.get('_file', '')).name, 'tags': variant.get('tags') or {},
        'identity': None, 'request': None, 'player': {}, 'playability': None, 'video': None, 'formats': None,
        'ads': None, 'po_token': {'sent': False, 'hints': []}, 'solve': None, 'media': None,
        'deliveries': None, 'verdict': None,
    }
    analysis = None
    res = None
    parse_error = None
    dels = {}
    try:
        idc = variant['identity']
        ident, fresh = (None, [])
        if idc.get('pages'):
            ident, fresh = identities.get(variant, trial, vid, _ua_hint(variant), variant.get('transport'))
            rec['identity'] = {
                'session': idc.get('session') or variant['id'], 'scope': idc.get('scope'),
                'key': [str(x) for x in ident.key], 'pages_fetched_now': fresh,
                'visitor_source': idc.get('visitor'), 'visitor_fp': fingerprint(ident.visitor),
                'flags_source': idc.get('flags'), 'flags_fp': fingerprint(ident.flags),
                'context_source': idc.get('context'), 'cookies_sent': sorted(ident.cookies) if idc.get('cookies') else [],
                'identity_age_s': round(time.time() - ident.fetched_at, 1), 'errors': ident.errors[-3:],
            }
            if any(p.get('bot_wall') for p in fresh):
                rec['stop'] = {'code': 'bot-wall', 'message': f'bot wall on an identity page ({vid}, {variant["id"]})'}
        req = build_request(variant, vid, ident, player.sts)
        tr = variant.get('transport') or {}
        # Explicit cookie contract, per phase (names only): what each request of this attempt's
        # identity actually carried. identity.cookies governs /player; page fetches have their own.
        page_cookies = {}
        if ident is not None:
            for info in ident.pages.values():
                page_cookies.setdefault(info['kind'], info.get('cookies_sent') or [])
        player_cookie = req['headers'].get('Cookie') or ''
        rec['cookie_policy'] = {
            'pages': page_cookies,
            'player': sorted(c.split('=', 1)[0].strip() for c in player_cookie.split(';') if '=' in c),
            'media': [],
            'declared': (variant.get('tags') or {}).get('cookies'),
        }
        rec['request'] = {
            'method': req['method'], 'host': req['url'].split('/')[2], 'path': '/' + req['url'].split('/', 3)[3].split('?')[0],
            'query_keys': sorted((req['url'].split('?', 1)[1].split('&') if '?' in req['url'] else [])),
            'body_bytes': len(req['body'] or b''), 'header_names': sorted(req['headers']),
            'client_name': req['vars']['client_name'], 'client_version': req['vars']['client_version'],
            'client_name_id': req['vars']['client_name_id'], 'user_agent': req['vars']['user_agent'],
            'sts': req['vars']['sts'], 'visitor_fp': fingerprint(req['vars']['visitor_data']),
            'flags_sent': bool(req['vars']['flags']) and variant['identity'].get('flags') is not None,
            'context_source': req['context_source'], 'notes': req['notes'],
            'impersonate': tr.get('impersonate'), 'http_version': tr.get('http_version'),
            'body_sha': fingerprint(req['body']) if req['body'] else None,
        }
        if args.save_request_bodies and req['body_obj'] is not None:
            rec['request']['body'] = redact_obj(req['body_obj'])
        res = net.request(req['method'], req['url'], headers=req['headers'], data=req['body'],
                          impersonate=tr.get('impersonate'), http_version=tr.get('http_version'),
                          ja3=tr.get('ja3'), akamai=tr.get('akamai'), browser_headers=tr.get('browser_headers'),
                          purpose=f'player:{variant["id"]}')
        t_resp = time.monotonic()
        rec['player'] = {'status': res.status, 'ms': round(res.elapsed_ms, 1), 'ttfb_ms': res.ttfb_ms and round(res.ttfb_ms, 1),
                         'up': res.up_bytes, 'down': res.down_bytes, 'body_bytes': len(res.body),
                         'http': res.http_version, 'new_conn': res.new_connections, 'error': res.error}
        pr = None
        if res.error is None and res.status == 200:
            pr, parse_error = parse_player_response(variant, res)
        elif res.body:
            try:
                err = json.loads(res.text()).get('error') or {}
                rec['player']['api_error'] = {'code': err.get('code'), 'message': (err.get('message') or '')[:160],
                                              'status': err.get('status')}
            except Exception:
                rec['player']['api_error'] = {'snippet': res.text()[:160]}
        if pr is not None:
            analysis = analyse(pr)
            rec['playability'] = analysis['playability']
            rec['video'] = analysis['video']
            rec['formats'] = analysis['formats']
            rec['ads'] = analysis['ads']
            rec['ads'].update(player.ad_wait(pr, vid, variant['id']))
            rec['po_token']['hints'] = po_token_hints(analysis, variant, pr)
            rec['attestation'] = analysis['attestation']
            if raw_dir:
                p = raw_dir / f't{trial}-{vid}-{variant["id"]}.json.gz'
                p.write_bytes(gzip.compress(json.dumps(redact_obj(pr)).encode()))
            if analysis['video']['video_id'] and analysis['video']['video_id'] != vid:
                rec['video']['mismatch'] = True
            if analysis['playability']['status'] == 'OK':
                targets, solve = prober.build_targets(pr, vid, variant)
                rec['solve'] = solve
                timing = prober.run(targets, t_resp, variant, req['vars'], rec['ads'].get('ad_wait_s'), trial=trial)
                trecs = {t.name: t.record() for t in targets}
                rec['media'] = {'first_probe_after_s': timing['first_probe_after_s'], 'slots_s': timing['slots_s'],
                                'target_order': timing['target_order'], 'targets': trecs}
                wanted = set((variant.get('media') or {}).get('targets') or ['adaptive', 'progressive', 'hls', 'dash'])
                if prober.only_targets:
                    wanted &= prober.only_targets
                dels = deliveries(analysis, trecs, wanted)
                rec['deliveries'] = dels
        rec['verdict'] = verdict(http_status=res.status, net_error=res.error, parse_error=parse_error,
                                 analysis=analysis, dels=dels)
        if analysis and analysis['playability']['bot_wall']:
            rec['stop'] = {'code': 'bot-wall', 'message': f'bot wall: {analysis["playability"]["reason"]} '
                                                           f'({vid}, {variant["id"]}); stopping the run'}
    except StopRun as e:
        rec['verdict'] = rec['verdict'] or {'code': 'STOPPED', 'reason_code': e.code}
        rec['stop'] = {'code': e.code, 'message': e.message}
    except Exception as e:
        rec['verdict'] = {'code': 'HARNESS_ERROR', 'reason_code': type(e).__name__, 'detail': str(e)[:300]}
        rec['traceback'] = traceback.format_exc()[-1500:]
    rec['verdict']['label'] = cell_label(rec['verdict'], rec.get('deliveries'))
    rec['bytes'] = Ledger.delta(before, net.ledger.snapshot())
    return rec
