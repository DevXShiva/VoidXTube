"""`netbench report results/*.jsonl` -> markdown: per network, a variant x category matrix, a
variant x video detail matrix, "who serves this" per category, latencies and bytes."""

from __future__ import annotations

import collections
import glob
import json
import pathlib

from .classify import LETTER
from .util import median

ORDER = ['PLAY', 'SABR', '403', 'UNPLAYABLE', 'LOGIN', 'BOTWALL', 'ERROR', 'FAIL']


def load(paths):
    runs = []
    for spec in paths:
        for p in sorted(glob.glob(spec)) or [spec]:
            header, footer, attempts, sustains = None, None, [], []
            with open(p) as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    obj = json.loads(line)
                    t = obj.get('type')
                    if t == 'header':
                        header = obj
                    elif t == 'footer':
                        footer = obj
                    elif t == 'attempt':
                        attempts.append(obj)
                    elif t == 'sustain':
                        sustains.append(obj)
            runs.append({'path': p, 'header': header or {}, 'footer': footer, 'attempts': attempts,
                         'sustains': sustains})
    return runs


def _agg_label(recs):
    """Aggregate the attempts of one cell (videos x trials).
    Uniform: 'PLAY H@3s A@7-8s 3/3'. Mixed: 'PLAY A@0s 2/3 · 403 A 1/3'."""
    if not recs:
        return ''
    n = len(recs)
    by_code = collections.Counter(r['verdict']['code'] for r in recs)
    parts = []
    for code, k in sorted(by_code.items(), key=lambda kv: (-kv[1], ORDER.index(kv[0]) if kv[0] in ORDER else 99)):
        group = [r for r in recs if r['verdict']['code'] == code]
        if code == 'PLAY':
            times = collections.defaultdict(list)
            fails = collections.Counter()
            for r in group:
                for dk, d in (r.get('deliveries') or {}).items():
                    if d.get('result') == 'PLAY':
                        times[dk].append(d.get('first_ok_after_s') or 0)
                    elif d.get('result') not in ('NONE', 'SKIPPED', None):
                        fails[f'{LETTER[dk]}:{d["result"]}'] += 1
            segs = []
            for dk in sorted(times, key=lambda x: (min(times[x]), 'aphd'.index(x[0]))):
                lo, hi = min(times[dk]), max(times[dk])
                span = f'{lo:.0f}' if round(lo) == round(hi) else f'{lo:.0f}-{hi:.0f}'
                cnt = '' if len(times[dk]) == len(group) else f'[{len(times[dk])}]'
                segs.append(f'{LETTER[dk]}@{span}s{cnt}')
            label = 'PLAY ' + ' '.join(segs)
            if fails:
                label += ' (' + ' '.join(f + ('' if c == len(group) else f'[{c}]') for f, c in fails.items()) + ')'
        elif code == 'BOTWALL':
            label = 'BOTWALL'
        else:
            labels = collections.Counter(r['verdict'].get('label') or code for r in group)
            label = labels.most_common(1)[0][0]
        parts.append(label + (f' {k}/{n}' if n > 1 else ''))
    return ' · '.join(parts)


def _esc(s):
    return str(s).replace('|', '\\|')


def render(runs) -> str:
    out = []
    by_net = collections.OrderedDict()
    for run in runs:
        by_net.setdefault(run['header'].get('network', '?'), []).append(run)
    out.append('# netbench report\n')
    out.append('Cell legend: **PLAY** = googlevideo *served media bytes* (HTTP 200/206 with a body) for every '
               'resource that delivery needs - not decoded, not played for N seconds (decode-proof is the phone '
               'stage). `A@7s` = that delivery was first served 7 s after the /player answer (A adaptive video+audio, '
               'P progressive, H HLS incl. its audio group and init map, D DASH video+audio); `(A:SABR)` = that '
               'delivery failed while another was served; `[k]` = only k of the cell\'s PLAY attempts. **SABR** = status '
               'OK but no format has a URL. **403** = URLs offered, every probe refused until the schedule ended. '
               '**BOTWALL** = the answer was a bot challenge ("confirm you\'re not a bot"); the run stops there, so '
               'later cells are missing, not failed. **UNPLAYABLE / LOGIN / ERROR** + reason code (e.g. `152-18`). '
               '`k/n` = attempts (videos x trials) with that outcome.\n')
    for net, net_runs in by_net.items():
        attempts = [a for r in net_runs for a in r['attempts']]
        sustains = [a for r in net_runs for a in r.get('sustains', [])]
        out.append(f'## Network: {net}\n')
        for r in net_runs:
            h, f = r['header'], r['footer'] or {}
            pl = h.get('player') or {}
            yd = h.get('ytdlp') or {}
            stop = (f.get('stopped') or {}).get('code')
            mode = ' **sustain**' if h.get('mode') == 'sustain' else ''
            out.append(f'- run `{h.get("run_id")}`{mode} ({h.get("ts")}): player `{pl.get("player_id")}` sts {pl.get("sts")}, '
                       f'yt-dlp {yd.get("commit")} ({(yd.get("date") or "")[:10]}), harness {h.get("harness_version")}, '
                       f'curl_cffi {h.get("curl_cffi")}, {h.get("deno")}, proxy {h.get("proxy") or "none"}, '
                       f'trials {h.get("trials")}, schedule {h.get("schedule_s")} s; '
                       f'{f.get("attempts", len(r["attempts"]))}/{h.get("planned_attempts")} attempts'
                       + (f', **stopped: {stop}**' if stop else ''))
        out.append('')
        walls = [n for r in net_runs for n in bot_wall_notes(r)]
        if walls:
            out.append('### Bot walls\n')
            out.extend(f'- {n}' for n in walls)
            out.append('')
        if sustains:
            out.extend(render_sustain(sustains))
        if not attempts:
            continue

        cats = list(collections.OrderedDict.fromkeys(a.get('category') or '?' for a in attempts))
        videos = list(collections.OrderedDict.fromkeys((a['video_id'], a.get('category') or '?') for a in attempts))
        variant_tags = {}
        for a in attempts:
            variant_tags.setdefault(a['variant_id'], a.get('tags') or {})
        groups = collections.defaultdict(list)
        for vid, tags in variant_tags.items():
            groups[str(tags.get('client', 'other'))].append(vid)
        order = [(g, sorted(ids)) for g, ids in sorted(groups.items())]

        cell = collections.defaultdict(list)
        vcell = collections.defaultdict(list)
        for a in attempts:
            cell[(a['variant_id'], a.get('category') or '?')].append(a)
            vcell[(a['variant_id'], a['video_id'])].append(a)

        def tagstr(tags):
            keys = ('identity', 'capability', 'context', 'transport')
            return ' '.join(f'{tags[k]}' for k in keys if tags.get(k))

        out.append('### Matrix: variant x category\n')
        out.append('| client | variant | tags | ' + ' | '.join(_esc(c) for c in cats) + ' |')
        out.append('|---|---|---|' + '---|' * len(cats))
        for group, ids in order:
            for vid in ids:
                row = [group, f'`{vid}`', _esc(tagstr(variant_tags[vid]))]
                row += [_esc(_agg_label(cell.get((vid, c), []))) for c in cats]
                out.append('| ' + ' | '.join(row) + ' |')
        out.append('')

        if len(videos) > len(cats) or len(videos) <= 12:
            out.append('### Detail: variant x video\n')
            out.append('| variant | ' + ' | '.join(f'{_esc(v)} ({_esc(c)})' for v, c in videos) + ' |')
            out.append('|---|' + '---|' * len(videos))
            for group, ids in order:
                for vid in ids:
                    row = [f'`{vid}`'] + [_esc(_agg_label(vcell.get((vid, v), []))) for v, _c in videos]
                    out.append('| ' + ' | '.join(row) + ' |')
            out.append('')

        out.append('### Timing: when each delivery was first served vs the pre-roll ad wait\n')
        out.append('Availability is interval-censored: `(a, b]` = last refused send at a s, first served send at '
                   'b s after the /player answer; `<=b` = served on the first probe; `>a` = never served (last probe '
                   'at a s). `ad_wait_s` = yt-dlp\'s own forced-wait estimate for that answer (pre-roll skip offsets '
                   'or durations); `full`/`dedup` = sum of full pre-roll durations / yt-dlp rule minus ads repeated '
                   'across adPlacements and adSlots. Only attempts whose status was OK are listed.\n')
        out.append('| variant | video | A (video+audio) | P | H | D | ad_wait_s | full / dedup | pre-roll ads | 1st probe |')
        out.append('|---|---|---|---|---|---|---|---|---|---|')

        def interval(d):
            if not d or d.get('result') in (None, 'NONE'):
                return ''
            if d.get('result') == 'SKIPPED':
                return 'not probed'
            if d.get('result') in ('SABR', 'NOURL', 'NSIG', 'SIG', 'UNPARSABLE'):
                return d['result']
            av = d.get('availability') or {}
            lo, hi = av.get('lo_s'), av.get('hi_s')
            if hi is None:
                return f'{d["result"]} >{lo:.1f}' if lo is not None else d['result']
            if lo is None:
                return f'<={hi:.1f}'
            return f'({lo:.1f}, {hi:.1f}]'

        for group, ids in order:
            for vid in ids:
                for v, _c in videos:
                    for r in vcell.get((vid, v), []):
                        if not r.get('deliveries'):
                            continue
                        dl = r['deliveries']
                        ads = r.get('ads') or {}
                        aw = ads.get('ad_wait_s')
                        rows = [a['at_s'] for t in ((r.get('media') or {}).get('targets') or {}).values()
                                for a in t.get('attempts', [])]
                        fp = min(rows) if rows else None
                        trial = f' t{r.get("trial")}' if r.get('trial', 1) > 1 else ''
                        probe_order = (r.get('media') or {}).get('target_order') or []
                        if probe_order and probe_order[0] != 'adaptive_video':
                            trial += f' (probe order: {probe_order[0]} first)'
                        out.append(f'| `{vid}` | {v}{trial} | ' + ' | '.join(
                            _esc(interval(dl.get(k))) for k in ('adaptive', 'progressive', 'hls', 'dash')) +
                            f' | {"" if aw is None else aw} | {ads.get("preroll_full_s", "")} / '
                            f'{ads.get("preroll_dedup_s", "")} | {ads.get("preroll_count", "")} | '
                            f'{"" if fp is None else f"{fp:.1f} s"} |')
        out.append('')

        out.append('### Who serves this (per category)\n')
        for c in cats:
            rows = []
            for vid in variant_tags:
                recs = cell.get((vid, c), [])
                if not recs:
                    continue
                plays = [r for r in recs if r['verdict']['code'] == 'PLAY']
                if not plays:
                    continue
                letters = collections.Counter()
                for r in plays:
                    letters.update(r['verdict'].get('played', []))
                rows.append((len(plays) / len(recs), -(median(r['verdict'].get('first_ok_after_s') for r in plays) or 0),
                             vid, len(plays), len(recs), letters,
                             median(r['verdict'].get('first_ok_after_s') for r in plays),
                             median((r.get('player') or {}).get('ms') for r in recs)))
            rows.sort(key=lambda x: (-x[0], -x[1], x[2]))
            if rows:
                items = [f'`{vid}` {k}/{n} via {",".join(sorted(l, key=lambda z: "aphd".index(z[0])))}; '
                         f'media @{t:.0f}s; /player {ms:.0f} ms' for _r, _t, vid, k, n, l, t, ms in rows]
                out.append(f'- **{c}**: ' + ' — '.join(items))
            else:
                out.append(f'- **{c}**: nobody')
        out.append('')

        out.append('### Latency and bytes per variant\n')
        out.append('| variant | attempts | /player median ms | media TTFB median ms | first media ok median s | '
                   'bytes up | bytes down |')
        out.append('|---|---|---|---|---|---|---|')
        for group, ids in order:
            for vid in ids:
                recs = [a for a in attempts if a['variant_id'] == vid]
                pms = median((r.get('player') or {}).get('ms') for r in recs)
                ttfbs = []
                for r in recs:
                    for t in ((r.get('media') or {}).get('targets') or {}).values():
                        ok = [x for x in t.get('attempts', []) if x.get('status') in (200, 206)]
                        if ok:
                            ttfbs.append(ok[0].get('ttfb_ms'))
                fok = median(r['verdict'].get('first_ok_after_s') for r in recs if r['verdict']['code'] == 'PLAY')
                up = sum(((r.get('bytes') or {}).get('total') or {}).get('up', 0) for r in recs)
                down = sum(((r.get('bytes') or {}).get('total') or {}).get('down', 0) for r in recs)
                out.append(f'| `{vid}` | {len(recs)} | {_fmt(pms)} | {_fmt(median(ttfbs))} | '
                           f'{"" if fok is None else f"{fok:.1f}"} | {up:,} | {down:,} |')
        out.append('')

        reasons = collections.OrderedDict()
        for a in attempts:
            p = a.get('playability') or {}
            rc = p.get('reason_code')
            if rc and rc not in reasons:
                reasons[rc] = ' / '.join(x for x in (p.get('reason'), p.get('subreason')) if x)
            api = (a.get('player') or {}).get('api_error')
            if api and f'http-{a["player"]["status"]}' not in reasons:
                reasons[f'http-{a["player"]["status"]}'] = api.get('message') or api.get('snippet') or ''
        if reasons:
            out.append('### Reason codes seen\n')
            for rc, text in reasons.items():
                out.append(f'- `{rc}`: {_esc(text)[:200]}')
            out.append('')

        out.append('### Traffic\n')
        for r in net_runs:
            tot = (r['footer'] or {}).get('totals')
            if not tot:
                continue
            zero = {'requests': 0, 'up': 0, 'down': 0, 'new_conn': 0}
            tot = {k: {**zero, **(tot.get(k) or {})} for k in ('youtube', 'media', 'other')} | {
                'total': {'up': 0, 'down': 0, 'new_conn': 0, 'tls_est_up': 0, 'tls_est_down': 0, **(tot.get('total') or {})}}
            out.append(f'- `{r["header"].get("run_id")}`: youtube {tot["youtube"]["requests"]} req '
                       f'(up {tot["youtube"]["up"]:,} / down {tot["youtube"]["down"]:,} B), media '
                       f'{tot["media"]["requests"]} req (up {tot["media"]["up"]:,} / down {tot["media"]["down"]:,} B), '
                       f'other {tot["other"]["requests"]} req; total up {tot["total"]["up"]:,} B, down '
                       f'{tot["total"]["down"]:,} B, + TLS handshake estimate up {tot["total"]["tls_est_up"]:,} / '
                       f'down {tot["total"]["tls_est_down"]:,} B over {tot["total"]["new_conn"]} new connections')
        out.append('')
    return '\n'.join(out)


def _fmt(v):
    return '' if v is None else f'{v:.0f}'


def main(args) -> int:
    runs = load(args.results)
    text = render(runs)
    if args.output:
        pathlib.Path(args.output).write_text(text)
        print(f'wrote {args.output}')
    else:
        print(text)
    return 0


def render_sustain(recs) -> list[str]:
    out = ['### Sustain: does the cell keep serving like a player?\n',
           'Fresh /player per cell (same variant and identity recipe), wait out that answer\'s pre-roll wait, '
           'then read the chosen delivery at playback pace (adaptive: video + audio byte ranges of ~chunk_s of '
           'media each; HLS: whole segments), keep prebuffer_s ahead, seek to seek_frac at seek_at_s, stop at '
           'duration_s or end of file. A refused chunk is retried once after 2 s. `FAIL@42s (HTTP403) A:adaptive_video` '
           '= first refusal 42 s into playback on that stream, and the retry was refused too. `EOF@131s` = the file '
           'ended before duration_s.\n']
    videos = list(collections.OrderedDict.fromkeys((r['video_id'], r.get('category') or '?') for r in recs))
    variants = list(collections.OrderedDict.fromkeys(r['variant_id'] for r in recs))
    cell = collections.defaultdict(list)
    for r in recs:
        cell[(r['variant_id'], r['video_id'])].append(r)
    out.append('| variant | ' + ' | '.join(f'{_esc(v)} ({_esc(c)})' for v, c in videos) + ' |')
    out.append('|---|' + '---|' * len(videos))
    for vid in variants:
        row = [f'`{vid}`']
        for v, _c in videos:
            labels = [(r['verdict'] or {}).get('label', '') for r in cell.get((vid, v), [])]
            row.append(_esc(' · '.join(labels)))
        out.append('| ' + ' | '.join(row) + ' |')
    out.append('')
    out.append('| variant | video | delivery | verdict | startup: ad_wait / first served (s after /player) | '
               'media chunks ok/total | first failure | seek | down |')
    out.append('|---|---|---|---|---|---|---|---|---|')
    for r in recs:
        v = r.get('verdict') or {}
        media_rows = [c for c in r.get('chunks', []) if c.get('kind') in ('range', 'segment')]
        ok = sum(1 for c in media_rows if c.get('ok'))
        st = r.get('startup') or {}
        fo = st.get('first_ok_after_s') or {}
        startup = f'{st.get("ad_wait_s")} / ' + ', '.join(f'{k.split("_")[-1]} {x}' for k, x in fo.items()) if st else ''
        ff = v.get('first_failure')
        fail = ''
        if ff:
            span = (f', media {ff["media_pos_s"]:.0f}-{ff["media_end_s"]:.0f}s'
                    if ff.get('media_pos_s') is not None and ff.get('media_end_s') is not None else '')
            fail = f'{ff.get("stream")} {ff.get("code")} @{ff.get("t_s")}s{span} (retry {ff.get("retry_code")})'
        seek = r.get('seek') or {}
        seek_s = ''
        if seek:
            after = [c for c in r.get('chunks', []) if c.get('after_seek') and c.get('kind') in ('range', 'segment')]
            seek_s = f'@{seek.get("at_s")}s: {sum(1 for c in after if c.get("ok"))}/{len(after)} ok'
        down = ((r.get('bytes') or {}).get('total') or {}).get('down', 0)
        out.append(f'| `{r["variant_id"]}` | {r["video_id"]} | {r.get("delivery")} | {_esc(v.get("label", ""))} | '
                   f'{_esc(startup)} | {ok}/{len(media_rows)} | {_esc(fail)} | {_esc(seek_s)} | {down:,} |')
    out.append('')
    return out


def _ts(value):
    import datetime
    try:
        return datetime.datetime.strptime(value, '%Y-%m-%dT%H:%M:%S%z')
    except (TypeError, ValueError):
        return None


def bot_wall_notes(run) -> list[str]:
    """Which variant hit a bot wall, on which video, after how many YouTube requests and how long.
    YouTube requests are counted through the walled attempt: run bootstrap (footer total minus every
    attempt's own requests) + the requests of every attempt up to and including it."""
    h, f = run['header'], run.get('footer') or {}
    recs = run['attempts'] or run.get('sustains') or []
    per = [((r.get('bytes') or {}).get('youtube') or {}).get('requests', 0) for r in recs]
    total = ((f.get('totals') or {}).get('youtube') or {}).get('requests')
    bootstrap = max(0, total - sum(per)) if total is not None else 0
    notes, cum, players = [], bootstrap, 0
    start = _ts(h.get('ts'))
    for r, n in zip(recs, per):
        cum += n
        players += 1
        v = r.get('verdict') or {}
        if v.get('code') != 'BOTWALL' and (r.get('stop') or {}).get('code') != 'bot-wall':
            continue
        t = _ts(r.get('ts'))
        mins = f', {((t - start).total_seconds() / 60):.1f} min into the run' if t and start else ''
        reason = (r.get('playability') or {}).get('reason') or (r.get('stop') or {}).get('message') or ''
        where = 'an identity page' if v.get('code') != 'BOTWALL' else 'its /player answer'
        notes.append(f'run `{h.get("run_id")}`: **`{r.get("variant_id")}`** on `{r.get("video_id")}` '
                     f'({r.get("category")}) hit a bot wall in {where} after **{cum} YouTube requests** '
                     f'({players} /player attempts, {bootstrap} bootstrap){mins}: "{_esc(reason)[:120]}"'
                     + (' - run stopped here' if (f.get('stopped') or {}).get('code') == 'bot-wall' else ''))
    if not notes and (f.get('stopped') or {}).get('code') == 'bot-wall':
        notes.append(f'run `{h.get("run_id")}` stopped on a bot wall before any attempt was written '
                     f'({total} YouTube requests): {(f.get("stopped") or {}).get("message")}')
    return notes
