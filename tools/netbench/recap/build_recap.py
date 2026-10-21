#!/usr/bin/env python3
"""Recap of every YouTube /player source measured for NewTube (offline: reads files, writes two).

Inputs (paths relative to the data root: --data, else $NETBENCH_DATA, else tools/netbench, the
parent of this directory):
  corpus.json, harness/corpus/pixel-2026-09-28.json    video id -> category (merged by id, later wins)
  harness/results/*.jsonl                              netbench runs: `header` (resolved variants),
                                                       `attempt` (one /player + media probes),
                                                       `sustain` (150 s paced replay), `footer`
  appbench/results/**/*.jsonl (+ the per-open .log)    in-app opens on the Pixel (one line per open)
  appbench/*.sh                                        run id -> check build ("check build vN" comments)
Outputs (--out, default <data root>/recap): recap.json, RECAP.md.

Deterministic: no clock, no network, sorted keys; stdlib only. Re-run any time:
    python3 recap/build_recap.py [--data DIR] [--out DIR]
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import statistics
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
# Input root and output dir; main() resets them from --data / $NETBENCH_DATA and --out.
NB = os.path.dirname(HERE)
OUT = os.path.join(NB, 'recap')


def set_roots(data, out=None):
    global NB, OUT, HARNESS_RESULTS, APPBENCH, CORPORA
    NB = os.path.abspath(data)
    OUT = os.path.abspath(out) if out else os.path.join(NB, 'recap')
    HARNESS_RESULTS = os.path.join(NB, 'harness', 'results')
    APPBENCH = os.path.join(NB, 'appbench')
    CORPORA = [os.path.join(NB, 'corpus.json'), os.path.join(NB, 'harness', 'corpus', 'pixel-2026-09-28.json')]


set_roots(NB)

# ----------------------------------------------------------------------------------- vocabulary
# (key, short column label, definition)
CATEGORIES = [
    ('ordinary', 'ord', 'ordinary VOD: vlog, tech, official/vevo music video, dubbed multi-audio, topic art track, '
                        '4K60 HDR / 10 h / 360, Shorts, "history" Tiny Desk videos'),
    ('embed-disabled', 'emb-off', 'ordinary VOD whose owner disabled embedding'),
    ('kids', 'kids', 'made_for_kids=true, whatever the corpus category'),
    ('age-embeddable', 'age-emb', 'age-restricted 18+, embeddable'),
    ('age-not-embeddable', 'age-no-emb', 'age-restricted 18+, not bypassable (corpus expects a failure)'),
    ('live-24/7', 'live', 'live now (24/7 stream)'),
    ('live-upcoming', 'upcoming', 'scheduled, not started'),
    ('live-replay', 'replay', 'ended live stream with replay (not made for kids)'),
    ('terminal', 'terminal', 'private, removed, members-only, paid, region-blocked, dead stream, premium-only'),
    ('adhoc', 'adhoc', 'video id not in the corpus'),
]
CAT_ORDER = [c[0] for c in CATEGORIES]
CAT_SHORT = {c[0]: c[1] for c in CATEGORIES}
NETWORKS = ['wifi', 'lte']
TIERS = ['harness-answer', 'harness-media', 'harness-sustain', 'device']

# Row groups, in the phone's candidate order first (DESIGN.md §3.2), then everything else.
GROUP_ORDER = ['RING', 'VISIONOS', 'TV_TIZEN', 'WEB_EMBED', 'ANDROID_REEL', 'ANDROID', 'ANDROID_SDK_LESS',
               'WEB', 'WEB_SAFARI', 'MWEB', 'GEO', 'ANDROID_VR', 'IOS', 'TV', 'TV_DOWNGRADED', 'TV_SIMPLY',
               'TV_EMBED', 'TV_KIDS', 'WEB_MUSIC', 'WEB_CREATOR', 'INITIAL', 'WEB_KIDS']
YTDLP_CLIENT = {'web': 'WEB', 'web_safari': 'WEB_SAFARI', 'web_embedded': 'WEB_EMBED', 'web_music': 'WEB_MUSIC',
                'web_creator': 'WEB_CREATOR', 'android': 'ANDROID', 'android_vr': 'ANDROID_VR', 'ios': 'IOS',
                'visionos': 'VISIONOS', 'mweb': 'MWEB', 'tv': 'TV', 'tv_downgraded': 'TV_DOWNGRADED',
                'tv_simply': 'TV_SIMPLY', 'web~initial_pr': 'INITIAL'}
TAG_CLIENT = {'visionos': 'VISIONOS', 'web': 'WEB', 'web_safari': 'WEB_SAFARI', 'mweb': 'MWEB',
              'android_vr': 'ANDROID_VR', 'ios': 'IOS', 'web_embedded': 'WEB_EMBED', 'tv': 'TV',
              'tv_downgraded': 'TV_DOWNGRADED', 'tv_tizen': 'TV_TIZEN', 'tv_embedded': 'TV_EMBED',
              'tv_simply': 'TV_SIMPLY', 'tv_kids': 'TV_KIDS', 'web_music': 'WEB_MUSIC',
              'web_creator': 'WEB_CREATOR', 'web_kids': 'WEB_KIDS'}

# The phone's candidate order (DESIGN.md §3.2) -> the matrix rows that are that request shape.
CANDIDATES = [
    ('VISIONOS', ['nt-VISIONOS']),
    ('TV_TIZEN anonymous', ['nt-TV_TIZEN']),
    ('WEB_EMBED (embed identity)', ['nt-WEB_EMBED']),
    ('ANDROID_REEL (360p progressive)', ['nt-ANDROID_REEL']),
    ('ANDROID (360p progressive)', ['nt-ANDROID']),
    ('WEB (token client)', ['nt-WEB']),
    ('WEB_SAFARI (token client)', ['nt-WEB_SAFARI']),
    ('MWEB (token client)', ['nt-MWEB']),
    ('MWEB supportXhr=false', ['nt-MWEB~xhr-false', 'x-MWEB-xhr-false']),
]
# Rows shown in the timing table.
TIMING_ROWS = ['nt-VISIONOS', 'nt-TV_TIZEN', 'nt-WEB_EMBED', 'nt-ANDROID_REEL', 'nt-ANDROID', 'nt-WEB',
               'nt-WEB_SAFARI', 'nt-MWEB', 'nt-MWEB~xhr-false', 'nt-ANDROID_VR', 'nt-IOS', 'nt-TV',
               'nt-TV_SIMPLY', 'nt-WEB_MUSIC', 'nt-GEO']

# Bot-challenge text. Harness nb/net.py as FIXED after sweep2b ("Sign in to confirm your age" is an age
# gate, not a bot wall), plus the Spanish text the Pixel's answers carry.
BOT_RES = [re.compile(r"confirm (that )?you.{0,3}re not a bot", re.I),
           re.compile(r"sign in to confirm you(?!r\b)", re.I),
           re.compile(r"no eres un bot", re.I)]
# harness nb/innertube.py reason_code() table, plus Spanish needles for the Pixel's localized answers
REASON_TABLE = [('page needs to be reloaded', 'reload'), ('not available', 'not-available'),
                ('no está disponible', 'not-available'), ('unavailable', 'unavailable'),
                ('confirm your age', 'age'), ('confirma tu edad', 'age'),
                ('inappropriate for some users', 'age'), ('private video', 'private'),
                ('latest version of youtube', 'latest-version'), ('members', 'members-only'),
                ('premiere', 'premiere'), ('live event will begin', 'upcoming'),
                ('playback on other websites', 'embed-disabled'), ('copyright', 'copyright'),
                ('country', 'geo'), ('sign in', 'sign-in')]
DELIVERY_LETTER = {'adaptive': 'A', 'progressive': 'P', 'hls': 'H', 'dash': 'D'}
# device opens that are not a verdict on the source
NON_VERDICT = ('ABORTED', 'NO-DATA', 'NO-TICKS', 'SHORT-RUN', 'BOT-COOLDOWN')


def is_bot(text):
    return bool(text) and any(p.search(text) for p in BOT_RES)


def reason_code(reason, subreason):
    text = ' '.join(x for x in (reason, subreason) if x)
    if not text:
        return None
    m = re.search(r'Error code:\s*(\d+)\s*-\s*(\d+)', text)
    if m:
        return f'{m.group(1)}-{m.group(2)}'
    if is_bot(text):
        return 'bot'
    low = text.lower()
    for needle, code in REASON_TABLE:
        if needle in low:
            return code
    return re.sub(r'[^a-z0-9]+', '-', low)[:24].strip('-')


def median(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def fmt_s(x, nd=1):
    return '—' if x is None else f'{x:.{nd}f}'


def rel(p):
    return os.path.relpath(p, NB)


def read_jsonl(path):
    out = []
    with open(path, errors='replace') as fh:
        for i, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                out.append({'type': '_bad_line', 'line': i})
    return out


# ----------------------------------------------------------------------------------- corpus
def load_corpus():
    merged, infos = {}, []
    for path in CORPORA:
        if not os.path.exists(path):
            continue
        data = json.load(open(path))
        videos = data.get('videos') if isinstance(data, dict) else data
        infos.append({'path': rel(path), 'videos': len(videos)})
        for v in videos:
            vid = v.get('id')
            if not vid:
                continue
            if vid in merged:
                old = merged[vid]
                subs = old['subcategories']
                if v.get('subcategory') and v['subcategory'] not in subs:
                    subs.append(v['subcategory'])
                old.update({k: val for k, val in v.items() if k != 'subcategory'})
            else:
                merged[vid] = dict(v)
                merged[vid]['subcategories'] = [v['subcategory']] if v.get('subcategory') else []
                merged[vid]['sources_only_pixel'] = path != CORPORA[0]
    for v in merged.values():
        v['recap_category'] = recap_category(v)
    return merged, infos


def recap_category(v):
    cat = v.get('category')
    sub = ' '.join(v.get('subcategories') or []).lower()
    exp = str(v.get('expected_signed_out')).lower()
    live = v.get('live_status')
    if v.get('made_for_kids') is True:
        return 'kids'
    if cat == 'age_restricted':
        return 'age-embeddable' if 'embeddable' in sub and 'non-' not in sub else 'age-not-embeddable'
    if cat == 'live':
        if live == 'is_upcoming' or 'upcoming' in sub:
            return 'live-upcoming'
        if exp == 'fail':
            return 'terminal'
        if live == 'is_live':
            return 'live-24/7'
        if live == 'was_live':
            return 'live-replay'
    if exp == 'fail':
        return 'terminal'
    if cat == 'embed_disabled':
        return 'embed-disabled'
    return 'ordinary'


def cat_of(corpus, vid):
    v = corpus.get(vid)
    return v['recap_category'] if v else 'adhoc'


# ----------------------------------------------------------------------------------- rows
def appclient_of_variant(vid, tags):
    tags = tags or {}
    if vid.startswith('nt-'):
        base = vid[3:].split('~')[0]
        return 'WEB_EMBED' if base == 'WEB_EMBED_HEAD' else base
    if vid.startswith('ytdlp-'):
        return YTDLP_CLIENT.get(vid[6:], 'OTHER')
    t = tags.get('client')
    if t == 'android':
        return 'ANDROID_REEL' if tags.get('context') == 'reel' else 'ANDROID'
    return TAG_CLIENT.get(t, 'OTHER')


def row_sort_key(row_id, rows):
    info = rows[row_id]
    g = info['appclient'] if info['kind'] != 'ring' else 'RING'
    gi = GROUP_ORDER.index(g) if g in GROUP_ORDER else len(GROUP_ORDER)
    if info['kind'] == 'ring':
        m = re.match(r'RING\[([^,\]]+)', row_id)
        return (gi, g, build_rank(m.group(1) if m else ''), row_id)
    elif row_id == f'nt-{g}':
        sub = 0
    elif row_id.startswith(f'nt-{g}~'):
        sub = 1
    elif row_id.startswith('nt-'):
        sub = 2
    elif row_id.startswith('x-'):
        sub = 3
    elif row_id.startswith('ytdlp-'):
        sub = 4
    else:
        sub = 5
    return (gi, g, sub, row_id)


# ----------------------------------------------------------------------------------- harness
def classify_attempt(a, cat):
    """One harness attempt -> answer class, media class, served deliveries, timing."""
    v = a.get('verdict') or {}
    code = v.get('code')
    ps = a.get('playability')
    player = a.get('player') or {}
    stop = a.get('stop') or {}
    out = {'answer': None, 'reason': None, 'reason_text': None, 'media': None, 'offered': [], 'served': [],
           'refused': [], 'first_ok': {}, 'player_ms': None, 'ad_wait_s': None, 'preroll': None,
           'notes': [], 'label': v.get('label')}
    if player.get('status') == 200 and player.get('ms') is not None:
        out['player_ms'] = player['ms']
    ads = a.get('ads') or {}
    out['ad_wait_s'] = ads.get('ad_wait_s')
    out['preroll'] = ads.get('preroll_count')
    if code in ('HARNESS_ERROR', 'STOPPED'):
        out['answer'] = 'harness-error'
        out['notes'].append(f'{code} {v.get("reason_code")}')
        return out
    if code == 'ERROR' and v.get('reason_code') == 'net':
        out['answer'] = 'transport'
        out['notes'].append((v.get('detail') or 'net')[:80])
        return out
    if player.get('status') not in (None, 200):
        out['answer'] = 'refused'
        err = player.get('api_error') or {}
        out['reason'] = f'http-{player.get("status")}'
        out['reason_text'] = (err.get('message') or err.get('snippet') or '')[:120]
        return out
    if ps is None:
        out['answer'] = 'harness-error'
        out['notes'].append(f'no playability ({code} {v.get("reason_code")})')
        return out
    status = ps.get('status')
    reason = ps.get('reason') or ps.get('error_screen')
    text = ' '.join(x for x in (ps.get('reason'), ps.get('subreason'), ps.get('error_screen')) if x)
    ident_wall = stop.get('code') == 'bot-wall' and 'identity page' in (stop.get('message') or '')
    if status in ('LOGIN_REQUIRED', 'ERROR', 'UNPLAYABLE', None) and is_bot(text):
        out['answer'] = 'bot'
        out['reason'] = 'bot'
        out['reason_text'] = reason
        return out
    if ident_wall:
        out['answer'] = 'bot'
        out['reason'] = 'bot-identity-page'
        out['reason_text'] = f'/player said {status} {reason_code(reason, ps.get("subreason"))}; ' \
                             f'the identity page was a bot interstitial'
        return out
    if code == 'BOTWALL':
        out['notes'].append('harness verdict BOTWALL, but the text is not a bot challenge under the fixed '
                            'pattern (age gate)')
    if status != 'OK':
        out['answer'] = 'refused'
        out['reason'] = f'{reason_code(reason, ps.get("subreason")) or status}'
        out['status'] = status
        out['reason_text'] = (text or status or '')[:140]
        return out
    f = a.get('formats') or {}
    if f.get('adaptive_url', 0) + f.get('adaptive_cipher', 0) > 0:
        out['offered'].append('A')
    if f.get('progressive_url', 0) + f.get('progressive_cipher', 0) > 0:
        out['offered'].append('P')
    if f.get('hls'):
        out['offered'].append('H')
    if f.get('dash'):
        out['offered'].append('D')
    if not out['offered']:
        out['answer'] = 'ok-sabr'
        out['reason'] = 'sabr-only' if (f.get('adaptive_total') or f.get('sabr')) else 'no-formats'
        return out
    out['answer'] = 'ok-usable'
    dels = a.get('deliveries') or {}
    neterr, skipped, other = [], [], []
    for name, d in sorted(dels.items()):
        letter = DELIVERY_LETTER.get(name)
        r = (d or {}).get('result')
        if letter is None or r in (None, 'NONE', 'SABR'):
            continue
        if r == 'PLAY':
            out['served'].append(letter)
            out['first_ok'][letter] = d.get('first_ok_after_s')
        elif r == '403' or str(r).startswith('HTTP'):
            out['refused'].append(letter)
        elif r == 'NETERR':
            neterr.append(letter)
        elif r == 'SKIPPED':
            skipped.append(letter)
        else:
            other.append(f'{letter}:{r}')
    out['neterr'] = neterr
    if out['served']:
        out['media'] = 'served'
    elif out['refused']:
        out['media'] = 'refused'
    elif neterr:
        out['media'] = 'transport'
    elif skipped and not other:
        out['media'] = 'not-probed'
    else:
        out['media'] = 'harness-error'
        out['notes'].append('media: ' + ','.join(other))
    # readiness: the earliest media probe of this answer (any target), served or refused?
    firsts = []
    for tname, t in sorted(((a.get('media') or {}).get('targets') or {}).items()):
        atts = (t or {}).get('attempts') or []
        if atts and atts[0].get('at_s') is not None:
            firsts.append((atts[0]['at_s'], tname, atts[0].get('ok'), atts[0].get('status')))
    if firsts:
        at, tname, ok, st = min(firsts)
        out['first_probe'] = {'at_s': at, 'target': tname, 'ok': ok, 'status': st}
    return out


SHOW = {'A': 'A', 'P': 'P', 'H': 'HLS', 'D': 'DASH', '?': '?'}


def delivery_order(cat):
    return ['D', 'H', 'A', 'P'] if cat == 'live-24/7' else ['A', 'P', 'H', 'D']


def best_delivery(served, cat):
    for letter in delivery_order(cat):
        if letter in served:
            return SHOW[letter]
    return None


def attempt_cell_class(c, cat):
    ans = c['answer']
    if ans == 'bot':
        return 'BOT'
    if ans == 'transport':
        return 'NET'
    if ans == 'harness-error':
        return 'ERR'
    if ans == 'refused':
        return f'REF {c["reason"]}'
    if ans == 'ok-sabr':
        return 'SABR'
    m = c['media']
    if m == 'served':
        return best_delivery(c['served'], cat)
    if m == 'refused':
        return '403'
    if m == 'transport':
        return 'NET'
    if m == 'not-probed':
        return 'unprobed'
    return 'ERR'


def sustain_class(s):
    v = s.get('verdict') or {}
    code = v.get('code')
    letter = SHOW[DELIVERY_LETTER.get(s.get('delivery'), '?')]
    if code == 'SUSTAIN-OK':
        played = v.get('played_s') or 0
        return {'cls': 'ok', 'letter': letter, 'played_s': played, 'eof': bool(v.get('eof')),
                'ok150': played >= 149.5 and not v.get('eof'), 'label': v.get('label')}
    if code == 'FAIL':
        ff = v.get('first_failure') or {}
        return {'cls': 'fail', 'letter': letter, 'at_s': ff.get('t_s'), 'status': ff.get('status'),
                'stream': ff.get('stream'), 'label': v.get('label')}
    if code == 'STARTUP-FAIL':
        return {'cls': 'startup-fail', 'letter': letter, 'label': v.get('label')}
    if code == 'NO-MEDIA':
        return {'cls': 'no-media', 'letter': letter, 'reason': v.get('reason'), 'label': v.get('label')}
    if code == 'UNSUPPORTED':
        return {'cls': 'unsupported', 'letter': letter, 'reason': v.get('reason'), 'label': v.get('label')}
    if code == 'BOTWALL':
        return {'cls': 'bot', 'letter': letter, 'label': v.get('label')}
    return {'cls': 'harness-error', 'letter': letter, 'label': v.get('label') or code}


def load_harness(corpus):
    runs, attempts, sustains, variant_defs = [], [], [], {}
    for path in sorted(glob.glob(os.path.join(HARNESS_RESULTS, '*.jsonl'))):
        lines = read_jsonl(path)
        header = next((o for o in lines if o.get('type') == 'header'), None)
        footer = next((o for o in lines if o.get('type') == 'footer'), None)
        if header is None:
            continue
        for vdef in header.get('variants') or []:
            variant_defs[vdef['id']] = vdef
        run = {'run_id': header.get('run_id'), 'file': rel(path), 'network': header.get('network'),
               'mode': header.get('mode') or 'run', 'proxy': header.get('proxy'),
               'player_id': (header.get('player') or {}).get('player_id'),
               'planned': header.get('planned_attempts'),
               'records': sum(1 for o in lines if o.get('type') in ('attempt', 'sustain')),
               'ended': (footer or {}).get('stopped'), 'footer': footer is not None,
               'sources': [s.get('run_id') for s in header.get('sources') or []]}
        runs.append(run)
        for o in lines:
            if o.get('type') == 'attempt':
                o['_run'] = run['run_id']
                o['_network'] = o.get('network') or run['network']
                o['_cat'] = cat_of(corpus, o.get('video_id'))
                o['_c'] = classify_attempt(o, o['_cat'])
                attempts.append(o)
            elif o.get('type') == 'sustain':
                o['_run'] = run['run_id']
                o['_network'] = o.get('network') or run['network']
                o['_cat'] = cat_of(corpus, o.get('video_id'))
                o['_s'] = sustain_class(o)
                sustains.append(o)
    return runs, attempts, sustains, variant_defs


# ----------------------------------------------------------------------------------- device
def build_map():
    """run id -> {build, play_s, keep_process} from the appbench run scripts: 'check build vN' in the
    script's header comment, and --play-s / --keep-process on the line that names the run id."""
    out = {}
    for sh in sorted(glob.glob(os.path.join(APPBENCH, '*.sh'))):
        text = open(sh, errors='replace').read()
        m = re.search(r'check build (v\d+)', text)
        for line in text.splitlines():
            if line.lstrip().startswith('#'):
                continue
            for rid in re.findall(r'--run-id\s+(\S+)', line):
                ps = re.search(r'--play-s\s+(\d+)', line)
                out.setdefault(rid, {'build': m.group(1) if m else None,
                                     'play_s': int(ps.group(1)) if ps else 150,
                                     'keep_process': '--keep-process' in line, 'script': os.path.basename(sh)})
    return out


BUILD_FALLBACK = [
    (r'^app1', 'pre-gate'),   # app1*/app1b*: before the readiness gate (DESIGN.md §6 "Before the gate")
    (r'^app2-', 'v3'),        # results/app2-aborted/app2-lte.wrap.v3.log
    (r'^emu-', 'emu'),        # emulator smoke tests of the tool
]


def build_of(run_id, bmap):
    m = re.search(r'-(v\d+)$', run_id or '')
    if m:
        return m.group(1)
    if (bmap.get(run_id) or {}).get('build'):
        return bmap[run_id]['build']
    for pat, b in BUILD_FALLBACK:
        if re.search(pat, run_id or ''):
            return b
    return 'unknown'


def device_answer(r):
    st = r.get('status')
    reason = r.get('reason') or ''
    if reason == 'null':
        reason = ''
    if st == 'OK':
        if (r.get('usable') or 0) > 0:
            return {'cls': 'ok-usable', 'reason': None}
        return {'cls': 'ok-sabr', 'reason': 'hls-offered' if r.get('hls') == 'y' else None}
    if is_bot(reason):
        return {'cls': 'bot', 'reason': 'bot', 'text': reason}
    return {'cls': 'refused', 'reason': reason_code(reason, None) or st, 'text': reason, 'status': st}


def device_outcome(row):
    """PLAY-OK only if playback advanced to >=140 s of playing time, near EOF (<=10 s left, one tick
    interval) or ENDED. appbench's own verdict is kept next to it."""
    if row.get('aborted'):
        return {'cls': 'ABORTED', 'why': row['aborted'][:100]}
    if not row.get('results') and row.get('bot_cooldown'):
        return {'cls': 'BOT-COOLDOWN', 'why': 'the app\'s bot-check cooldown answered the open without any /player'}
    if not row.get('results'):
        return {'cls': 'NO-DATA', 'why': 'no /player result for the target video in the open\'s log'}
    ticks = row.get('ticks') or []
    ff = row.get('first_frame_ms')
    playing = [t for t in ticks if t.get('playing') == 'y' and (t.get('pos') or 0) > 0]
    errors = row.get('errors') or []
    n403 = sum(1 for e in errors if 'http=403' in (e.get('error') or ''))
    if ff is None and not playing:
        return {'cls': 'NO-START', 'errors': len(errors), 'e403': n403}
    if not ticks:
        return {'cls': 'NO-TICKS', 'why': 'first frame logged but no bench-tick lines'}
    max_pos = max((t.get('pos') or 0) for t in ticks)
    dur = max((t.get('dur') or -1) for t in ticks)
    if not playing:
        return {'cls': 'DIED', 'at_s': 0, 'errors': len(errors), 'e403': n403}
    played_s = playing[0]['pos'] / 1000 + (playing[-1]['t'] - playing[0]['t']) / 1000
    ended = any(t.get('state') == 'ENDED' for t in ticks)
    near_eof = dur > 0 and max_pos >= dur - 10_000
    if ended or near_eof or played_s >= 140:
        return {'cls': 'PLAY-OK', 'played_s': round(played_s), 'eof': ended or near_eof,
                'recovered_errors': len(errors), 'e403': n403}
    if ticks[-1].get('playing') == 'y' and not errors:
        return {'cls': 'SHORT-RUN', 'played_s': round(played_s),
                'why': f'still playing when the cell ended after ~{round(played_s)} s (short test run)'}
    return {'cls': 'DIED', 'at_s': max_pos // 1000, 'errors': len(errors), 'e403': n403}


def parse_open_log(path, video):
    """(client, player ms, http code, playerPot) per /player of the target video, in order."""
    if not os.path.exists(path):
        return []
    pending, pairs = [], []
    with open(path, errors='replace') as fh:
        for ln in fh:
            if 'NetPath' not in ln:
                continue
            m = re.search(r'player-context video=(\S+) client=(\S+) .*?playerPot=(\S)', ln)
            if m and m.group(1) == video:
                pending.append({'client': m.group(2), 'pot': m.group(3)})
                continue
            m = re.search(r'player-http\[C\] rid=\S+ video=(\S+) code=(\d+) ms=(\d+)', ln)
            if m and m.group(1) == video:
                i = next((k for k, p in enumerate(pending) if p['client'] != 'ANDROID_REEL'), None)
                if i is not None:
                    p = pending.pop(i)
                    pairs.append({**p, 'code': int(m.group(2)), 'ms': int(m.group(3))})
                continue
            m = re.search(r'api-http\[C\] .*reel_item_watch code=(\d+) ms=(\d+)', ln)
            if m:
                i = next((k for k, p in enumerate(pending) if p['client'] == 'ANDROID_REEL'), None)
                if i is not None:
                    p = pending.pop(i)
                    pairs.append({**p, 'code': int(m.group(1)), 'ms': int(m.group(2))})
    return pairs


def readiness_from_log(path, video):
    """(wait ms, served sinceReadyMs) of the target video's first readiness-wait, from lines tagged video=<id>."""
    if not os.path.exists(path):
        return None, None
    wait = served = None
    with open(path, errors='replace') as fh:
        for ln in fh:
            m = re.search(r'video=(\S+) readiness-wait ms=(\d+)', ln)
            if m and m.group(1) == video and wait is None:
                wait = int(m.group(2))
            m = re.search(r'video=(\S+) readiness-served sinceReadyMs=(-?\d+)', ln)
            if m and m.group(1) == video and served is None:
                served = int(m.group(2))
    return wait, served


def other_videos_in_log(path, video):
    if not os.path.exists(path):
        return []
    seen = []
    with open(path, errors='replace') as fh:
        for ln in fh:
            m = re.search(r'player-result video=(\S+)', ln)
            if m and m.group(1) != video and m.group(1) not in seen:
                seen.append(m.group(1))
    return seen


def load_device(corpus):
    bmap = build_map()
    opens, runs = [], {}
    paths = sorted(set(glob.glob(os.path.join(APPBENCH, 'results', '*.jsonl')) +
                       glob.glob(os.path.join(APPBENCH, 'results', '*', '*.jsonl'))))
    for path in paths:
        for row in read_jsonl(path):
            if row.get('type') == '_bad_line' or 'source' not in row:
                continue
            rid = row.get('run_id')
            row['_file'] = rel(path)
            row['_build'] = build_of(rid, bmap)
            meta = bmap.get(rid) or {}
            row['_play_s'] = meta.get('play_s', 150)
            row['_keep_process'] = bool(meta.get('keep_process'))
            # build tag used in cells: build, plus the cell length and keep-process when not the default
            row['_tag'] = row['_build'] + (f'/{row["_play_s"]}s' if row['_play_s'] != 150 else '') + \
                ('/keep-process' if row['_keep_process'] else '')
            row['_network'] = row.get('network')
            row['_cat'] = cat_of(corpus, row.get('video'))
            row['_outcome'] = device_outcome(row)
            row['_answers'] = [dict(device_answer(r), client=r.get('client')) for r in row.get('results') or []]
            logp = os.path.join(os.path.dirname(path), f'{rid}.{row["source"]}.{row["video"]}.t{row.get("trial", 1)}.log')
            pairs = parse_open_log(logp, row.get('video'))
            row['_other_videos'] = other_videos_in_log(logp, row.get('video')) if not row.get('results') else []
            # match log pairs to player-result entries in order, by client (enrichment calls drop out)
            j = 0
            for ans in row['_answers']:
                while j < len(pairs) and pairs[j]['client'] != ans['client']:
                    j += 1
                if j < len(pairs):
                    ans['ms'], ans['pot'], ans['http'] = pairs[j]['ms'], pairs[j]['pot'], pairs[j]['code']
                    j += 1
            wait, served = readiness_from_log(logp, row.get('video'))
            row['_readiness_src'] = 'log' if wait is not None else None
            if wait is None:
                # no tagged line in the log: appbench's per-open list (its lines are not video-scoped)
                for s in row.get('readiness') or []:
                    m = re.match(r'readiness-wait ms=(\d+)', s)
                    if m:
                        wait = int(m.group(1))
                        row['_readiness_src'] = 'appbench-list'
                        break
            row['_readiness_wait_ms'] = wait
            row['_readiness_served_since_ready_ms'] = served
            opens.append(row)
            r = runs.setdefault(rid, {'run_id': rid, 'file': rel(path), 'network': row.get('network'),
                                      'build': row['_build'], 'play_s': row['_play_s'],
                                      'keep_process': row['_keep_process'], 'script': meta.get('script'),
                                      'opens': 0, 'sources': [], 'videos': [],
                                      'anon_tizen': False, 'support_xhr': []})
            r['opens'] += 1
            for k, val in (('sources', row['source']), ('videos', row['video']),
                           ('support_xhr', row.get('support_xhr') or 'none')):
                if val not in r[k]:
                    r[k].append(val)
            r['anon_tizen'] = r['anon_tizen'] or bool(row.get('anon_tizen'))
    return opens, [runs[k] for k in sorted(runs)]


def device_row_id(row, known_rows):
    src = row['source']
    if src == 'RING':
        extra = (', anon-tizen' if row.get('anon_tizen') else '') + \
                (f', {row["_play_s"]}s cells' if row['_play_s'] != 150 else '') + \
                (', keep-process' if row['_keep_process'] else '')
        return f'RING[{row["_build"]}{extra}]'
    xhr = row.get('support_xhr') or 'none'
    suffix = {'false': '~xhr-false', 'absent': '~dpc-absent'}.get(xhr, '')
    cand = f'nt-{src}{suffix}'
    return cand if cand in known_rows else f'device:{src}' + (f' xhr={xhr}' if xhr != 'none' else '')


def device_label(row):
    xhr = row.get('support_xhr') or 'none'
    return row['source'] + (f' xhr={xhr}' if xhr not in ('none', None) else '')


# ----------------------------------------------------------------------------------- aggregation
def new_record(source, cat, net, tier, rows):
    info = rows.get(source, {})
    return {'source': source, 'appclient': info.get('appclient'), 'category': cat, 'network': net, 'tier': tier,
            'attempts': 0, 'answer_ok_usable': 0, 'answer_ok_usable_offered': Counter(), 'answer_refused': 0,
            'refused_reasons': Counter(), 'sabr_only_or_no_usable': 0, 'bot_challenge': 0,
            'transport_error': 0, 'harness_error': 0,
            'media_served_at_start': None, 'media_served_by_delivery': None, 'media_refused_at_start': None,
            'media_transport_error': None, 'media_not_probed': None,
            'sustain_ok_150s': None, 'sustain_ok_eof': None, 'sustain_fail': None, 'sustain_other': None,
            'device_opens': None, 'device_play_ok': None, 'device_play_ok_after_recovered_errors': None,
            'device_no_start': None, 'device_died': None, 'device_aborted': None, 'device_no_data': None,
            'device_short_run': None, 'device_bot_cooldown': None, 'device_first_frame_ms_median': None, 'device_answers_in_ring_walks': None,
            'device_builds': None,
            'videos': set(), 'runs': set(), 'per_video': defaultdict(list), 'notes': set(),
            '_player_ms': [], '_first_served_s': [], '_a_served_s': [], '_ff': [], '_ad': []}


def aggregate(attempts, sustains, opens, rows):
    recs = {}

    def rec(source, cat, net, tier):
        k = (source, cat, net, tier)
        if k not in recs:
            recs[k] = new_record(source, cat, net, tier, rows)
        return recs[k]

    for a in attempts:
        c, cat, net, src = a['_c'], a['_cat'], a['_network'], a['variant_id']
        r = rec(src, cat, net, 'harness-answer')
        r['attempts'] += 1
        r['videos'].add(a['video_id'])
        r['runs'].add(a['_run'])
        cls = attempt_cell_class(c, cat)
        r['per_video'][a['video_id']].append(cls if cls not in ('A', 'P', 'HLS', 'DASH') else f'PLAY {cls}')
        ans = c['answer']
        if ans == 'ok-usable':
            r['answer_ok_usable'] += 1
            for letter in c['offered']:
                r['answer_ok_usable_offered'][letter] += 1
        elif ans == 'ok-sabr':
            r['sabr_only_or_no_usable'] += 1
        elif ans == 'refused':
            r['answer_refused'] += 1
            r['refused_reasons'][f'{c.get("status") or ""} {c["reason"]}: {c.get("reason_text") or ""}'.strip()] += 1
        elif ans == 'bot':
            r['bot_challenge'] += 1
            r['notes'].add(f'bot challenge ({c["reason"]}) on {a["video_id"]} in {a["_run"]}')
        elif ans == 'transport':
            r['transport_error'] += 1
        else:
            r['harness_error'] += 1
        for n in c['notes']:
            if 'BOTWALL' in n:
                r['notes'].add(f'{a["_run"]} {a["video_id"]}: {n}')
        if c['player_ms'] is not None:
            r['_player_ms'].append(c['player_ms'])
        if ans == 'ok-usable':
            m = rec(src, cat, net, 'harness-media')
            m['attempts'] += 1
            m['videos'].add(a['video_id'])
            m['runs'].add(a['_run'])
            m['per_video'][a['video_id']].append(cls if cls not in ('A', 'P', 'HLS', 'DASH') else f'PLAY {cls}')
            for key in ('media_served_at_start', 'media_refused_at_start', 'media_transport_error',
                        'media_not_probed', 'harness_error'):
                m[key] = m[key] or 0
            if m['media_served_by_delivery'] is None:
                m['media_served_by_delivery'] = Counter()
            med = c['media']
            if med == 'served':
                m['media_served_at_start'] += 1
                for letter in c['served']:
                    m['media_served_by_delivery'][letter] += 1
                firsts = [x for x in c['first_ok'].values() if x is not None]
                if firsts:
                    m['_first_served_s'].append(min(firsts))
                if c['first_ok'].get('A') is not None:
                    m['_a_served_s'].append(c['first_ok']['A'])
            elif med == 'refused':
                m['media_refused_at_start'] += 1
            elif med == 'transport':
                m['media_transport_error'] += 1
                m['transport_error'] += 1
            elif med == 'not-probed':
                m['media_not_probed'] += 1
            else:
                m['harness_error'] += 1
            if c.get('ad_wait_s') is not None:
                m['_ad'].append({'ad_wait_s': c['ad_wait_s'], 'a_first_ok': c['first_ok'].get('A'),
                                 'first_probe': c.get('first_probe'), 'A_offered': 'A' in c['offered']})

    for s in sustains:
        sc, cat, net, src = s['_s'], s['_cat'], s['_network'], s['variant_id']
        r = rec(src, cat, net, 'harness-sustain')
        r['attempts'] += 1
        r['videos'].add(s['video_id'])
        r['runs'].add(s['_run'])
        r['per_video'][s['video_id']].append(sc['label'])
        for key in ('sustain_ok_150s', 'sustain_ok_eof', 'sustain_fail', 'sustain_other'):
            r[key] = r[key] or 0
        if sc['cls'] == 'ok':
            if sc['eof']:
                r['sustain_ok_eof'] += 1
            else:
                r['sustain_ok_150s'] += 1
        elif sc['cls'] in ('fail', 'startup-fail'):
            r['sustain_fail'] += 1
        elif sc['cls'] == 'bot':
            r['bot_challenge'] += 1
        else:
            r['sustain_other'] += 1
        r.setdefault('_sustain', []).append(sc)

    for o in opens:
        cat, net = o['_cat'], o['_network']
        rid = device_row_id(o, rows)
        r = rec(rid, cat, net, 'device')
        for key in ('device_opens', 'device_play_ok', 'device_play_ok_after_recovered_errors', 'device_no_start',
                    'device_died', 'device_aborted', 'device_no_data', 'device_short_run', 'device_bot_cooldown',
                    'device_answers_in_ring_walks'):
            r[key] = r.get(key) or 0
        if r['device_builds'] is None:
            r['device_builds'] = Counter()
        out = o['_outcome']
        r['device_opens'] += 1
        r['device_builds'][o['_tag']] += 1
        r['videos'].add(o['video'])
        r['runs'].add(o['run_id'])
        tag = out['cls'] + (f'@{out["at_s"]}s' if out['cls'] == 'DIED' else '')
        r['per_video'][o['video']].append(f'{tag} [{o["_tag"]}]')
        r.setdefault('_outcomes', []).append({'out': out, 'build': o['_tag'], 'video': o['video'],
                                              'ff': o.get('first_frame_ms'), 'appbench': o.get('verdict'),
                                              'run': o['run_id'], 'winner': o.get('winner'),
                                              'answers': o['_answers'], 'wait': o['_readiness_wait_ms'],
                                              'info_ms': (o.get('info') or {}).get('ms'),
                                              'prepare': (o.get('prepare') or {}).get('type'),
                                              'forced': o['source'] != 'RING'})
        key = {'PLAY-OK': 'device_play_ok', 'NO-START': 'device_no_start', 'DIED': 'device_died',
               'ABORTED': 'device_aborted', 'NO-DATA': 'device_no_data', 'SHORT-RUN': 'device_short_run',
               'NO-TICKS': 'device_no_data', 'BOT-COOLDOWN': 'device_bot_cooldown'}[out['cls']]
        r[key] += 1
        if out['cls'] == 'PLAY-OK' and out.get('recovered_errors'):
            r['device_play_ok_after_recovered_errors'] += 1
        if o.get('first_frame_ms') is not None and out['cls'] not in NON_VERDICT:
            r['_ff'].append(o['first_frame_ms'])
        if out['cls'] in NON_VERDICT:
            r['notes'].add(f'{o["run_id"]} {o["video"]}: {out["cls"]} ({out.get("why", "")})')
        # every /player answer of the open: to the forced source's row, or (ring walk) to the client's row
        for ans in o['_answers']:
            if o['source'] == 'RING':
                crow = f'nt-{ans["client"]}' if f'nt-{ans["client"]}' in rows else f'device:{ans["client"]}'
                cr = rec(crow, cat, net, 'device')
                cr['device_answers_in_ring_walks'] = (cr['device_answers_in_ring_walks'] or 0) + 1
                cr['videos'].add(o['video'])
                cr['runs'].add(o['run_id'])
                cr['per_video'][o['video']].append(f'ring:{ans["cls"]}' + (f' {ans["reason"]}' if ans.get('reason') else ''))
            else:
                cr = r
            cr['attempts'] += 1
            if ans['cls'] == 'ok-usable':
                cr['answer_ok_usable'] += 1
            elif ans['cls'] == 'ok-sabr':
                cr['sabr_only_or_no_usable'] += 1
            elif ans['cls'] == 'bot':
                cr['bot_challenge'] += 1
            else:
                cr['answer_refused'] += 1
                cr['refused_reasons'][f'{ans.get("status") or ""} {ans["reason"]}: {ans.get("text") or ""}'.strip()] += 1
            if ans.get('ms') is not None and ans.get('http') == 200:
                cr['_player_ms'].append(ans['ms'])
            cr.setdefault('_ans', []).append(dict(ans, ring=o['source'] == 'RING', build=o['_tag']))
    return recs


# ----------------------------------------------------------------------------------- cell text
def harness_part(r, cat):
    classes = Counter()
    extra = Counter()
    for vid, labels in r['per_video'].items():
        for lab in labels:
            lab = lab.replace('PLAY ', '')
            if lab in ('BOT', 'NET', 'ERR'):
                extra[lab] += 1
            else:
                classes[lab] += 1
    n = sum(classes.values())
    order = ['A', 'P', 'HLS', 'DASH', '403', 'SABR', 'unprobed']
    parts = sorted(classes.items(), key=lambda kv: (-kv[1], order.index(kv[0]) if kv[0] in order else 99, kv[0]))
    txt = ', '.join(f'{k} {v}/{n}' for k, v in parts) if n else 'no verdict'
    nv = len(r['videos'])
    if n and nv != r['attempts']:
        txt += f' [{nv}v]'
    for k, label in (('NET', 'net'), ('BOT', 'bot'), ('ERR', 'err')):
        if extra[k]:
            txt += f' +{extra[k]} {label}'
    return txt


def sustain_part(r):
    """Grouped by the delivery the replay used; each group's outcomes over that group's cells."""
    by_letter = defaultdict(list)
    for s in r.get('_sustain') or []:
        by_letter[s['letter']].append(s)
    out = []
    for letter in sorted(by_letter, key=lambda x: ['A', 'P', 'HLS', 'DASH', '?'].index(x) if x in ('A', 'P', 'HLS', 'DASH', '?') else 9):
        items = by_letter[letter]
        n = len(items)
        groups = defaultdict(list)
        for s in items:
            if s['cls'] == 'ok':
                groups['ok'].append(f'EOF@{s["played_s"]:.0f}s' if s['eof'] else f'{s["played_s"]:.0f}s')
            elif s['cls'] == 'fail':
                groups[f'{s["status"]}@{s["at_s"]:.0f}s'].append('')
            elif s['cls'] == 'no-media':
                groups[f'NO-MEDIA {s["reason"]}'].append('')
            elif s['cls'] == 'unsupported':
                groups[f'unsupported({s["reason"]})'].append('')
            else:
                groups[s['cls']].append('')
        segs = []
        for k in sorted(groups, key=lambda k: (k != 'ok', -len(groups[k]), k)):
            det = [x for x in groups[k] if x]
            segs.append(f'{k} {len(groups[k])}/{n}' + (f' ({",".join(det)})' if det else ''))
        out.append(f'{letter} ' + ', '.join(segs))
    return 'S: ' + ' · '.join(out)


def device_part(r, prefix='D', show_build=True):
    outs = r.get('_outcomes') or []
    if not outs:
        return None
    by_build = defaultdict(list)
    for o in outs:
        by_build[o['build']].append(o)
    parts = []
    for b in sorted(by_build, key=build_rank):
        items = by_build[b]
        verdicts = [o for o in items if o['out']['cls'] not in NON_VERDICT]
        n = len(verdicts)
        c = Counter()
        died = []
        for o in verdicts:
            cls = o['out']['cls']
            if cls == 'DIED':
                died.append(o['out'])
            c[cls] += 1
        segs = []
        if c['PLAY-OK']:
            rec_err = sum(1 for o in verdicts if o['out']['cls'] == 'PLAY-OK' and o['out'].get('recovered_errors'))
            segs.append(f'PLAY {c["PLAY-OK"]}/{n}' + (f' ({rec_err} after recovered errors)' if rec_err else ''))
        if c['NO-START']:
            segs.append(f'NO-START {c["NO-START"]}/{n}')
        if died:
            lo, hi = min(d['at_s'] for d in died), max(d['at_s'] for d in died)
            at = f'{lo}s' if lo == hi else f'{lo}-{hi}s'
            e403 = ' 403' if all(d.get('e403') for d in died) else ''
            segs.append(f'DIED@{at} {len(died)}/{n}{e403}')
        other = Counter(o['out']['cls'] for o in items if o['out']['cls'] in NON_VERDICT)
        for k in sorted(other):
            segs.append(f'+{other[k]} {k.lower()}')
        # the forced source's own /player answers, when not all of them had usable adaptive formats
        anss = [a for o in items if o['forced'] and o['out']['cls'] not in ('ABORTED',) for a in o['answers']]
        if anss:
            ok = sum(1 for a in anss if a['cls'] == 'ok-usable')
            if ok < len(anss):
                segs.append(f'(answers usable {ok}/{len(anss)})')
        ff = median([o['ff'] for o in items if o['ff'] is not None and o['out']['cls'] not in NON_VERDICT])
        if ff is not None:
            segs.append(f'ff {ff / 1000:.1f}s')
        parts.append((f'{prefix} {b}: ' if show_build else f'{prefix}: ') + ' '.join(segs))
    return ' · '.join(parts)


def ring_answers_part(r):
    anss = [a for a in r.get('_ans') or [] if a['ring']]
    if not anss:
        return None
    c = Counter()
    for a in anss:
        if a['cls'] == 'ok-usable':
            c['usable'] += 1
        elif a['cls'] == 'ok-sabr':
            c['SABR'] += 1
        elif a['cls'] == 'bot':
            c['BOT'] += 1
        else:
            c[f'REF {a["reason"]}'] += 1
    n = len(anss)
    return 'ring: ' + ', '.join(f'{k} {v}/{n}' for k, v in sorted(c.items(), key=lambda kv: (-kv[1], kv[0])))


def build_rank(b):
    m = re.match(r'v(\d+)', b)
    if m:
        return (1, int(m.group(1)), b)
    return (0 if b.split('/')[0] in ('pre-gate', 'emu') else 2, 0, b)


def cell_text(recs, rows, row, cat, net):
    parts = []
    ha = recs.get((row, cat, net, 'harness-answer'))
    if ha:
        parts.append('H: ' + harness_part(ha, cat))
    hs = recs.get((row, cat, net, 'harness-sustain'))
    if hs:
        parts.append(sustain_part(hs))
    dv = recs.get((row, cat, net, 'device'))
    ring_row = (rows.get(row) or {}).get('kind') == 'ring'
    if dv:
        d = device_part(dv, show_build=not ring_row)
        if d:
            parts.append(d)
        ra = ring_answers_part(dv)
        if ra:
            parts.append(ra)
    if net == 'wifi':
        em = recs.get((row, cat, 'wifi-emu', 'device'))
        if em:
            d = device_part(em, prefix='emu', show_build=False)
            if d:
                parts.append(d)
    return ' · '.join(parts) if parts else '—'


# ----------------------------------------------------------------------------------- markdown
def md_matrix(recs, rows, net, lines, footnotes):
    nets = [net] + (['wifi-emu'] if net == 'wifi' else [])
    present_rows = sorted({k[0] for k in recs if k[2] in nets}, key=lambda r: row_sort_key(r, rows))
    present_cats = [c for c in CAT_ORDER if any(k[1] == c and k[2] in nets for k in recs)]
    empty_cats = [c for c in CAT_ORDER if c not in present_cats and c != 'adhoc']
    lines.append('| AppClient | source (variant / device) | ' + ' | '.join(CAT_SHORT[c] for c in present_cats) + ' |')
    lines.append('|---|---|' + '---|' * len(present_cats))
    last_group = None
    for row in present_rows:
        info = rows[row]
        group = 'RING' if info['kind'] == 'ring' else info['appclient']
        gtxt = f'**{group}**' if group != last_group else ''
        last_group = group
        name = f'`{row}`' if info['kind'] != 'ring' else f'`{row}` (app walk)'
        cells = [cell_text(recs, rows, row, c, net) for c in present_cats]
        lines.append(f'| {gtxt} | {name} | ' + ' | '.join(cells) + ' |')
    if empty_cats:
        lines.append('')
        lines.append(f'No source was ever tried on this network for: {", ".join(CAT_SHORT[c] for c in empty_cats)} '
                     f'(every cell would be `—`).')


def evidence_counts(recs, rows_ids, cat, net):
    hv, hn, sn, dv, dn, dplay = set(), 0, 0, set(), 0, 0
    for row in rows_ids:
        ha = recs.get((row, cat, net, 'harness-answer'))
        if ha:
            for vid, labels in ha['per_video'].items():
                k = sum(1 for lab in labels if lab not in ('BOT', 'NET', 'ERR'))
                if k:
                    hv.add(vid)
                    hn += k
        hs = recs.get((row, cat, net, 'harness-sustain'))
        if hs:
            sn += sum(1 for s in hs.get('_sustain') or [] if s['cls'] not in ('bot', 'harness-error'))
        d = recs.get((row, cat, net, 'device'))
        if d:
            for o in d.get('_outcomes') or []:
                if o['out']['cls'] in ('PLAY-OK', 'NO-START', 'DIED'):
                    dv.add(o['video'])
                    dn += 1
                    dplay += o['out']['cls'] == 'PLAY-OK'
    return {'harness_videos': len(hv), 'harness_attempts': hn, 'sustain_cells': sn, 'device_videos': len(dv),
            'device_opens': dn, 'device_play_ok': dplay}


def md_gaps(recs, rows, lines):
    relevant = ['ordinary', 'embed-disabled', 'kids', 'age-embeddable', 'age-not-embeddable', 'live-24/7',
                'live-upcoming', 'live-replay', 'terminal']
    lines.append('Evidence depth for the phone\'s candidate order (signed out). Cell = `H` harness videos/attempts '
                 '(verdicts only: bot, transport and harness errors excluded) · `S` sustain cells · `D` device '
                 'videos/opens (PLAY-OK+NO-START+DIED). **none** = nothing on any tier; **1** = a single video '
                 'across all tiers.')
    lines.append('')
    for net in NETWORKS:
        lines.append(f'**{net.upper() if net == "lte" else "Wi-Fi"}**')
        lines.append('')
        lines.append('| candidate | ' + ' | '.join(CAT_SHORT[c] for c in relevant) + ' |')
        lines.append('|---|' + '---|' * len(relevant))
        for label, row_ids in CANDIDATES:
            cells = []
            for c in relevant:
                e = evidence_counts(recs, row_ids, c, net)
                vids = set()
                for row in row_ids:
                    for t in TIERS:
                        r = recs.get((row, c, net, t))
                        if r:
                            vids |= r['videos']
                if not (e['harness_attempts'] or e['sustain_cells'] or e['device_opens']):
                    cells.append('**none**')
                    continue
                txt = f'H{e["harness_videos"]}/{e["harness_attempts"]} S{e["sustain_cells"]} D{e["device_videos"]}/{e["device_opens"]}'
                if len(vids) <= 1:
                    txt = f'**1**: {txt}'
                cells.append(txt)
            lines.append(f'| {label} | ' + ' | '.join(cells) + ' |')
        lines.append('')


RELEVANT = ['ordinary', 'embed-disabled', 'kids', 'age-embeddable', 'age-not-embeddable', 'live-24/7',
            'live-upcoming', 'live-replay', 'terminal']


def evidence(recs, row_ids, cat, net):
    e = evidence_counts(recs, row_ids, cat, net)
    vids = set()
    for row in row_ids:
        for t in TIERS:
            r = recs.get((row, cat, net, t))
            if r:
                vids |= r['videos']
    e['videos'] = vids
    e['any'] = bool(e['harness_attempts'] or e['sustain_cells'] or e['device_opens'])
    return e


def computed_gaps(recs, ctx):
    """Every statement here is recomputed from the records, so a re-run after new data stays true."""
    out = []
    ev = {(label, c, net): evidence(recs, ids, c, net) for label, ids in CANDIDATES for c in RELEVANT for net in NETWORKS}
    labels = [label for label, _ in CANDIDATES]
    nowhere = [c for c in RELEVANT if not any(ev[(lb, c, n)]['any'] for lb in labels for n in NETWORKS)]
    if nowhere:
        out.append(f'Never measured for any candidate on any tier or network: {", ".join(CAT_SHORT[c] for c in nowhere)}.')
    for net in NETWORKS:
        netname = 'LTE' if net == 'lte' else 'Wi-Fi'
        none_here = [c for c in RELEVANT if c not in nowhere and not any(ev[(lb, c, net)]['any'] for lb in labels)]
        if none_here:
            out.append(f'{netname}: no candidate has any evidence for {", ".join(CAT_SHORT[c] for c in none_here)}.')
        single = defaultdict(list)
        for lb in labels:
            for c in RELEVANT:
                e = ev[(lb, c, net)]
                if e['any'] and len(e['videos']) <= 1:
                    single[CAT_SHORT[c]].append(lb)
        for cs, lbs in sorted(single.items()):
            who = 'every candidate' if len(lbs) == len(labels) else ', '.join(lbs)
            vids = sorted({v for lb in lbs for v in ev[(lb, [c for c in RELEVANT if CAT_SHORT[c] == cs][0], net)]['videos']})
            out.append(f'{netname}, {cs}: a single video ({", ".join(vids)}) for {who}.')
    # device evidence, exhaustively
    dev = []
    for lb in labels:
        for net in NETWORKS:
            for c in RELEVANT:
                e = ev[(lb, c, net)]
                if e['device_opens']:
                    per_build = defaultdict(lambda: [0, 0])
                    for row in dict(CANDIDATES)[lb]:
                        d = recs.get((row, c, net, 'device'))
                        for o in (d or {}).get('_outcomes') or []:
                            if o['out']['cls'] in ('PLAY-OK', 'NO-START', 'DIED'):
                                per_build[o['build']][1] += 1
                                per_build[o['build']][0] += o['out']['cls'] == 'PLAY-OK'
                    pb = ', '.join(f'{b} {v[0]}/{v[1]}' for b, v in sorted(per_build.items(), key=lambda kv: build_rank(kv[0])))
                    dev.append(f'{lb} {CAT_SHORT[c]} {net}: {e["device_play_ok"]}/{e["device_opens"]} PLAY on '
                               f'{e["device_videos"]} video(s)' + (f' [{pb}]' if len(per_build) > 1 else ''))
    pixel_wifi = sum(1 for o in ctx['opens'] if o['_network'] == 'wifi')
    out.append('Device (Pixel) verdict opens for the candidates, all of them: ' + ('; '.join(dev) if dev else 'none') +
               '. Everything else in the candidate x category grid has no device open.' +
               ('' if pixel_wifi else ' No Pixel open on Wi-Fi at all (only the emulator smoke tests).'))
    # DESIGN §4 bar
    bar = []
    for lb, ids in CANDIDATES:
        for c in RELEVANT:
            ok_nets = []
            for net in NETWORKS:
                per_video = Counter()
                for row in ids:
                    d = recs.get((row, c, net, 'device'))
                    for o in (d or {}).get('_outcomes') or []:
                        if o['out']['cls'] == 'PLAY-OK':
                            per_video[o['video']] += 1
                if sum(1 for v, k in per_video.items() if k >= 3) >= 5:
                    ok_nets.append(net)
            if len(ok_nets) == len(NETWORKS):
                bar.append(f'{lb} {CAT_SHORT[c]}')
    out.append('DESIGN §4 acceptance bar (>=5 videos x >=3 PLAY opens on each network, in the app): met by '
               + (', '.join(bar) if bar else 'no candidate in any category') + '.')
    # sustain, exhaustively
    sus = defaultdict(int)
    for (row, c, net, tier), r in recs.items():
        if tier == 'harness-sustain':
            sus[(row, CAT_SHORT.get(c, c), net)] += r['attempts']
    out.append('Harness sustain (150 s replay) exists only for: ' +
               '; '.join(f'{row} {c} {net} ({n})' for (row, c, net), n in sorted(sus.items())) +
               '. ' + ('No sustain on LTE.' if not any(k[2] == 'lte' for k in sus) else ''))
    # PO-token clients on the device
    pot_clients = sorted({a['client'] for o in ctx['opens'] for a in o['_answers'] if a.get('pot') == 'y'})
    forced = defaultdict(Counter)
    for o in ctx['opens']:
        if o['source'] in pot_clients:
            forced[device_label(o)][o['_outcome']['cls']] += 1
    txt = ('PO tokens: the harness never sends one (web-family media results there are token-less). On the device '
           f'the app\'s answers carried a player PO token (`playerPot=y`) for {", ".join(pot_clients) or "no client"}; '
           'forced opens of those clients: ' +
           ('; '.join(f'{k}: ' + ', '.join(f'{v} {c}' for c, v in sorted(cnt.items())) for k, cnt in sorted(forced.items()))
            if forced else 'none') + '. The rest appear only inside ring walks.')
    out.append(txt)
    # HLS for VOD in the app
    prep = Counter((o.get('prepare') or {}).get('type') or 'no prepare line' for o in ctx['opens'])
    hls_vod = sum(1 for o in ctx['opens'] if ((o.get('prepare') or {}).get('type') or '').startswith('hls')
                  and o['_cat'] != 'live-24/7')
    out.append('HLS for VOD in the app: device loader types over all opens: ' +
               ', '.join(f'{k} {v}' for k, v in sorted(prep.items())) + f'; HLS on a non-live video: {hls_vod}. '
               'Other HLS-VOD evidence is harness-only (the `HLS ok` sustain cells).')
    # the app's own walk, per RING row
    ring = []
    for (row, c, net, tier), r in sorted(recs.items(), key=lambda kv: (row_sort_key(kv[0][0], ctx['rows']), kv[0][1], kv[0][2])):
        if tier == 'device' and (ctx['rows'].get(row) or {}).get('kind') == 'ring':
            outs = [o for o in r.get('_outcomes') or [] if o['out']['cls'] not in NON_VERDICT]
            if outs:
                ok = sum(1 for o in outs if o['out']['cls'] == 'PLAY-OK')
                wins = Counter(o['winner'] for o in outs if o['out']['cls'] == 'PLAY-OK' and o['winner'])
                ring.append(f'{row} {CAT_SHORT.get(c, c)} {net}: PLAY {ok}/{len(outs)} on {len({o["video"] for o in outs})} '
                            f'video(s)' + (f' (won by {", ".join(f"{w} {n}" for w, n in sorted(wins.items()))})' if wins else ''))
    if ring:
        out.append('The app\'s own walk (RING rows, verdict opens only): ' + '; '.join(ring) + '.')
    # bot challenges per candidate shape, all tiers and networks (harness /player answers + device answers)
    bots = []
    for lb, ids in CANDIDATES:
        hb = sum(1 for a in ctx['attempts'] if a['variant_id'] in ids and a['_c']['answer'] == 'bot')
        hn = sum(1 for a in ctx['attempts'] if a['variant_id'] in ids and a['_c']['answer'] not in ('transport', 'harness-error'))
        db = dn = 0
        for (row, c, net, tier), r in recs.items():
            if row in ids and tier == 'device':
                for a in r.get('_ans') or []:
                    dn += 1
                    db += a['cls'] == 'bot'
        bots.append(f'{lb} {hb}/{hn} harness, {db}/{dn} device')
    out.append('Bot challenges per candidate (challenged / /player answers, both networks): ' + '; '.join(bots) +
               ' (counts only; they say nothing about higher request rates).')
    out.append('Signed in: no record on any tier is signed in (the harness is signed out by construction; the '
               'check build has no account). Kids, age-restricted and bot-wall behaviour signed in are unmeasured.')
    out.append('Repeats: harness cells are one trial per run (reruns of the same video come from separate runs); '
               'device cells are one open per (source, video, build) unless listed twice in "Device opens".')
    return out


def preroll_stats(ads):
    """ads = one entry per OK+usable harness answer: yt-dlp's ad_wait_s and the answer's earliest probe."""
    pre = [x for x in ads if (x['ad_wait_s'] or 0) > 0]
    nop = [x for x in ads if (x['ad_wait_s'] or 0) == 0 and x['ad_wait_s'] is not None]
    # only probes that got an HTTP status count; a transport error (status None) says nothing about readiness
    probed_pre = [x for x in pre if x['first_probe'] and x['first_probe']['status'] is not None]
    probed_nop = [x for x in nop if x['first_probe'] and x['first_probe']['status'] is not None]
    return {'preroll_answers': len(pre), 'answers_with_ad_data': len([x for x in ads if x['ad_wait_s'] is not None]),
            'preroll_ad_wait_s_median': median([x['ad_wait_s'] for x in pre]),
            'preroll_first_probe_refused': sum(1 for x in probed_pre if 400 <= x['first_probe']['status'] < 500),
            'preroll_first_probe_n': len(probed_pre),
            'no_ad_first_probe_refused': sum(1 for x in probed_nop if 400 <= x['first_probe']['status'] < 500),
            'no_ad_first_probe_n': len(probed_nop),
            'preroll_adaptive_first_served_s_median': median([x['a_first_ok'] for x in pre])}


def preroll_text(ads):
    if not ads:
        return '—'
    p = preroll_stats(ads)
    txt = f'{p["preroll_answers"]}/{p["answers_with_ad_data"]} ads'
    if p['preroll_answers']:
        txt += f' (wait {fmt_s(p["preroll_ad_wait_s_median"], 0)} s)'
        txt += f'; 1st probe 403: ads {p["preroll_first_probe_refused"]}/{p["preroll_first_probe_n"]}'
        txt += f', no-ad {p["no_ad_first_probe_refused"]}/{p["no_ad_first_probe_n"]}'
        if p['preroll_adaptive_first_served_s_median'] is not None:
            txt += f'; ads A served {fmt_s(p["preroll_adaptive_first_served_s_median"])} s'
    else:
        txt += f'; 1st probe 403: {p["no_ad_first_probe_refused"]}/{p["no_ad_first_probe_n"]}'
    return txt


def md_timing(recs, rows, lines, attempts, opens):
    lines.append('Harness: `/player` = HTTP time of the /player request (host-side curl_cffi, median, n); '
                 '`media` = seconds from the /player answer to the first served probe of any delivery / of '
                 'adaptive (median over served attempts; probes retry the same URL at 0,2,4,6,10,20,40 s, so '
                 'values are upper bounds at slot granularity). `pre-roll` over OK+usable answers: `k/n ads` = '
                 'answers whose yt-dlp `ad_wait_s` > 0 (median wait); `1st probe 403` = the earliest media probe '
                 '(~0.6-2 s after /player) was refused, for ad-bearing vs no-ad answers; `ads A served` = median '
                 'first adaptive success on ad-bearing answers. Device (Pixel, LTE): `/player` = '
                 '`player-http[C]`/`api-http[C]` ms of that client in the open logs (forced opens and ring walks); '
                 '`ff` = open → first frame of forced opens (includes the readiness wait and decoding); `wait` = '
                 'the readiness-wait ms of each verdict open that waited, from the log line tagged with the video '
                 '(`r` = a ring open this client won).')
    lines.append('')
    lines.append('| source | Wi-Fi /player ms | Wi-Fi media s (any / A) | Wi-Fi pre-roll | LTE /player ms | '
                 'LTE media s (any / A) | LTE pre-roll | device /player ms | device ff s | device wait ms |')
    lines.append('|---|---|---|---|---|---|---|---|---|---|')
    for row in TIMING_ROWS:
        cells = []
        for net in NETWORKS:
            pm, fs, fa, ads = [], [], [], []
            for (src, cat, n, tier), r in recs.items():
                if src != row or n != net:
                    continue
                if tier == 'harness-answer':
                    pm += r['_player_ms']
                if tier == 'harness-media':
                    fs += r['_first_served_s']
                    fa += r['_a_served_s']
                    ads += r['_ad']
            cells.append(f'{fmt_s(median(pm), 0)} (n={len(pm)})' if pm else '—')
            cells.append(f'{fmt_s(median(fs))} / {fmt_s(median(fa))}' if fs else '—')
            cells.append(preroll_text(ads))
        dms, dff = [], []
        for (src, cat, n, tier), r in recs.items():
            if src != row or tier != 'device' or n != 'lte':
                continue
            dms += r['_player_ms']
            for o in r.get('_outcomes') or []:
                if o['ff'] is not None and o['out']['cls'] not in NON_VERDICT:
                    dff.append(o['ff'])
        # readiness waits: forced opens of this source, and ring opens this client won
        dwait = []
        for o in opens:
            if o['_network'] != 'lte' or o['_readiness_wait_ms'] is None or o['_outcome']['cls'] in NON_VERDICT:
                continue
            owner = f'nt-{o.get("winner")}' if o['source'] == 'RING' else device_row_id(o, rows)
            if owner == row:
                dwait.append(f'{o["_readiness_wait_ms"]}' + ('r' if o['source'] == 'RING' else ''))
        cells.append(f'{fmt_s(median(dms), 0)} (n={len(dms)})' if dms else '—')
        cells.append(f'{fmt_s(median(dff) / 1000)} (n={len(dff)})' if dff else '—')
        cells.append(', '.join(sorted(dwait, key=lambda w: int(w.rstrip('r')))) if dwait else '—')
        lines.append(f'| `{row}` | ' + ' | '.join(cells) + ' |')


def md_device_detail(opens, rows, lines):
    groups = defaultdict(list)
    for o in opens:
        rid = device_row_id(o, rows)
        label = rid if o['source'] == 'RING' else device_label(o)
        groups[(rid, label, o['_network'])].append(o)
    lines.append('| device source | network | per open: video (category) outcome [build] first frame | appbench verdict, where it differs |')
    lines.append('|---|---|---|---|')
    for key in sorted(groups, key=lambda k: (row_sort_key(k[0], rows), k[1], k[2])):
        rid, label, net = key
        items = sorted(groups[key], key=lambda o: (o['video'], build_rank(o['_tag']), o['run_id']))
        segs, diffs = [], []
        for o in items:
            out = o['_outcome']
            cls = out['cls'] + (f'@{out["at_s"]}s' if out['cls'] == 'DIED' else '')
            if out['cls'] == 'PLAY-OK' and out.get('recovered_errors'):
                cls += f'(+{out["recovered_errors"]} err)'
            ff = f' {o["first_frame_ms"] / 1000:.1f}s' if o.get('first_frame_ms') is not None else ''
            if o['source'] == 'RING':
                walk = ' '.join(f'{a["client"]}:' + {'ok-usable': 'ok', 'ok-sabr': 'sabr', 'bot': 'BOT'}.get(a['cls'], a.get('reason') or 'ref')
                                for a in o['_answers'])
                cls += f' walk[{walk}]' + (f' win={o.get("winner")}' if o.get('winner') else '')
            elif any(a['cls'] != 'ok-usable' for a in o['_answers']):
                cls += ' answers[' + ' '.join({'ok-usable': 'ok', 'ok-sabr': 'sabr', 'bot': 'BOT'}.get(a['cls'], a.get('reason') or 'ref')
                                               for a in o['_answers']) + ']' + \
                       (f' prepare={o["prepare"]["type"]}' if o.get('prepare') else '')
            segs.append(f'{o["video"][:6]}({CAT_SHORT[o["_cat"]]}) {cls} [{o["_tag"]}]{ff}')
            ab = o.get('verdict') or ''
            same = ab.startswith(out['cls']) or (out['cls'] == 'DIED' and ab.startswith(('FAIL', 'STALL', 'PARTIAL')))
            if not same:
                diffs.append(f'{o["video"][:6]} {ab}')
        lines.append(f'| `{label}` | {net} | ' + '; '.join(segs) + ' | ' + '; '.join(diffs) + ' |')


def md_ordinary_by_video(recs, rows, lines, corpus):
    """Candidates x each ordinary / embed-disabled video that has any evidence (subcategory visible)."""
    vids = []
    for c in ('ordinary', 'embed-disabled'):
        for (row, cat, net, tier), r in recs.items():
            if cat == c:
                for v in r['videos']:
                    if v not in vids:
                        vids.append(v)
    vids = sorted(vids, key=lambda v: (corpus.get(v, {}).get('recap_category', ''), corpus.get(v, {}).get('category', ''), v))
    if not vids:
        return
    head = [f'{v} ({corpus[v]["category"]}: {", ".join(corpus[v].get("subcategories") or [])})' for v in vids]
    lines.append('| candidate | ' + ' | '.join(head) + ' |')
    lines.append('|---|' + '---|' * len(vids))
    for label, ids in CANDIDATES:
        cells = []
        for v in vids:
            segs = []
            for net, short in (('wifi', 'W'), ('lte', 'L')):
                labs = []
                for row in ids:
                    cat = corpus[v]['recap_category']
                    ha = recs.get((row, cat, net, 'harness-answer'))
                    if ha:
                        labs += [x.replace('PLAY ', '') for x in ha['per_video'].get(v, [])]
                    d = recs.get((row, cat, net, 'device'))
                    if d:
                        labs += ['D:' + x.split(' [')[0] for x in d['per_video'].get(v, []) if not x.startswith('ring:')]
                if labs:
                    segs.append(f'{short} ' + ','.join(labs))
            cells.append(' · '.join(segs) if segs else '—')
        lines.append(f'| {label} | ' + ' | '.join(cells) + ' |')


def md_sources(rows, lines):
    lines.append('| AppClient | variant | shape (tags: identity · supportXhr · context · transport) | definition |')
    lines.append('|---|---|---|---|')
    last = None
    for row in sorted((r for r in rows if rows[r]['kind'] == 'harness'), key=lambda r: row_sort_key(r, rows)):
        info = rows[row]
        g = info['appclient']
        gtxt = f'**{g}**' if g != last else ''
        last = g
        t = info.get('tags') or {}
        shape = ' · '.join(str(t.get(k)) for k in ('identity', 'capability', 'context', 'transport') if t.get(k))
        desc = (info.get('description') or '').replace('|', '/')
        if len(desc) > 150:
            desc = desc[:147] + '...'
        lines.append(f'| {gtxt} | `{row}` | {shape} | {desc} |')


def render_md(ctx):
    recs, rows, corpus = ctx['recs'], ctx['rows'], ctx['corpus']
    L = []
    L.append('# /player sources: measured recap')
    L.append('')
    L.append('Generated by `recap/build_recap.py` from the files on disk (re-run it to refresh; nothing here is '
             'hand-edited). Signed out only: the harness never sends an account or a PO token, and the device '
             'check build has no account. **There is no signed-in evidence at all.**')
    L.append('')
    # evidence base
    ha = ctx['attempts']
    L.append('## Evidence base')
    L.append('')
    for net in NETWORKS:
        n_att = sum(1 for a in ha if a['_network'] == net)
        n_sus = sum(1 for s in ctx['sustains'] if s['_network'] == net)
        n_dev = sum(1 for o in ctx['opens'] if o['_network'] == net)
        vids = sorted({a['video_id'] for a in ha if a['_network'] == net})
        L.append(f'- {net}: {n_att} harness attempts on {len(vids)} videos, {n_sus} sustain cells, {n_dev} device opens')
    n_emu = sum(1 for o in ctx['opens'] if o['_network'] == 'wifi-emu')
    L.append(f'- wifi-emu (Android emulator, not the Pixel): {n_emu} device opens, shown as `emu:` in the Wi-Fi matrix')
    L.append('- Harness runs: ' + '; '.join(
        f'`{r["run_id"]}` {r["network"]} {r["records"]}' + (f'/{r["planned"]}' if r.get('planned') else '') +
        (f' stopped: {r["ended"].get("code")}' if r.get('ended') else '') + ('' if r['footer'] else ' (no footer: interrupted)')
        for r in ctx['harness_runs']))
    L.append('- Device runs (Pixel 9 `.check` build unless noted): ' + '; '.join(
        f'`{r["run_id"]}` {r["network"]} build {r["build"]} {r["opens"]} opens ({",".join(r["sources"])})' +
        (f' {r["play_s"]} s cells' if r.get('play_s') not in (None, 150) else '') + (' keep-process' if r.get('keep_process') else '')
        for r in ctx['device_runs']))
    L.append('')
    # legend
    L.append('## How to read a cell')
    L.append('')
    L.append('- `H:` harness (host, signed out, no PO token), one /player + media probes per attempt. The answer '
             'was OK and googlevideo served the first bytes of: `A` adaptive video+audio; `P` progressive 360p '
             '(itag 18) but not adaptive; `HLS` only HLS; `DASH` only DASH (for `live` the order is DASH > HLS > A '
             '> P). `403` = URLs offered but every probe refused through 40 s. `SABR` = OK but no format with a URL (SABR-only or no '
             'formats). `REF <code>` = UNPLAYABLE / LOGIN_REQUIRED / ERROR with that reason code. `k/n` = attempts; '
             '`[Nv]` = distinct videos when fewer than attempts. `+k net` transport errors (proxy/timeout), '
             '`+k bot` bot challenges, `+k err` harness errors — excluded from n.')
    L.append('- `S:` harness sustain (150 s paced HTTP replay with a seek at 90 s; no decoding): `A ok k/n (EOF@120s)` '
             'served to the end of the file before 150 s; `A 403@46s` first refusal at playback second 46; '
             '`NO-MEDIA SABR` the fresh answer had no URLs; `unsupported` the replay cannot do that delivery.')
    L.append('- `D <build>:` in-app opens on the Pixel (forced source, 150 s with the app\'s auto-seek). `PLAY` = '
             'bench-tick positions advanced to >=140 s of playing time, or to <=10 s from the end / ENDED; '
             '`NO-START` = no first frame; `DIED@Ns` = playback stopped at position N s (` 403` = every such open '
             'logged HTTP 403 errors); `(answers usable k/n)` = only k of the forced source\'s own /player answers '
             'had usable adaptive formats. Not verdicts: `aborted` (a phone event took focus), `no-data` (the '
             'open\'s log had no /player result for the video), `bot-cooldown` (the app\'s bot-check cooldown '
             'answered without any /player), `short-run` (a 15 s test cell, still playing when it ended). `ff` = median open → first frame over the verdict opens (PLAY, NO-START, DIED). `ring:` = answers this client gave inside '
             'the app\'s own ring walks (`RING[...]` rows), classified from the app\'s `player-result` lines: `usable` '
             '= usableAdaptive>0, `SABR` = OK with usableAdaptive=0, `BOT` = explicit bot text.')
    L.append('- Builds: `pre-gate` = app1*/app1b* runs, before the readiness gate (DESIGN §6); `vN` = the check build '
             'named in the appbench run script (`check build vN` comment or a `-vN` run id). Builds change the ring, '
             'so each `RING[...]` row is one build/profile; the clients each walk asked are listed in "Device opens '
             'in detail".')
    L.append('- Categories: ' + '; '.join(
        f'`{CAT_SHORT[c]}` {d} ({", ".join(sorted(v["id"] for v in corpus.values() if v["recap_category"] == c)) or "none"})'
        for c, _, d in CATEGORIES if c != 'adhoc'))
    L.append('')
    for net in NETWORKS:
        L.append(f'## Matrix: {"Wi-Fi" if net == "wifi" else "LTE"}')
        L.append('')
        if net == 'lte':
            lte_runs = [r for r in ctx['harness_runs'] if r['network'] == 'lte']
            desc = '; '.join(f'`{r["run_id"]}` {len({a["video_id"] for a in ctx["attempts"] if a["_run"] == r["run_id"]})} '
                             f'videos, {r["records"]} attempts' + (f', stopped: {r["ended"].get("code")}' if r.get('ended') else '')
                             for r in lte_runs)
            L.append(f'Harness LTE = the Pixel\'s LTE through a SOCKS proxy on the phone, host-side requests ({desc or "no run"}). '
                     'Device LTE = the app on the Pixel\'s own LTE.')
            L.append('')
        md_matrix(recs, rows, net, L, ctx['footnotes'])
        L.append('')
    L.append('## Ordinary videos one by one (candidates only)')
    L.append('')
    L.append('Per video, the harness classes (`W` Wi-Fi, `L` LTE; one entry per attempt) and device outcomes (`D:`), '
             'so the subcategory behind the `ord` / `emb-off` columns stays visible.')
    L.append('')
    md_ordinary_by_video(recs, rows, L, corpus)
    L.append('')
    L.append('## Device opens in detail')
    L.append('')
    md_device_detail(ctx['opens'], rows, L)
    L.append('')
    L.append('## What we don\'t know')
    L.append('')
    md_gaps(recs, rows, L)
    for g in computed_gaps(recs, ctx):
        L.append(f'- {g}')
    L.append('')
    L.append('## Timing')
    L.append('')
    md_timing(recs, rows, L, ctx['attempts'], ctx['opens'])
    L.append('')
    L.append('## Sources (harness variants → AppClient)')
    L.append('')
    pot = Counter()
    seen = Counter()
    for o in ctx['opens']:
        for a in o['_answers']:
            if a.get('pot') is not None:
                seen[a['client']] += 1
                pot[a['client']] += a['pot'] == 'y'
    pot_txt = ', '.join(f'{c} {pot[c]}/{seen[c]}' for c in sorted(seen))
    L.append('`nt-*` = NewTube\'s own request for that AppClient (MSC working tree 2026-09-28, per '
             'harness/variants/newtube.yaml), `~axis` = one field changed, `ytdlp-*` = yt-dlp\'s anonymous request, '
             '`x-*` = experiments. Device results are the app\'s real request for the forced AppClient, shown in the '
             'matching `nt-*` row. Device answers that carried the app\'s player PO token (`player-context '
             f'playerPot=y`), per client: {pot_txt or "no log data"}.')
    L.append('')
    md_sources(rows, L)
    L.append('')
    L.append('## Footnotes and parsing caveats')
    L.append('')
    for i, f in enumerate(ctx['footnotes'], 1):
        L.append(f'{i}. {f}')
    L.append('')
    return '\n'.join(L)


# ----------------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description='Recap of every /player source measured (offline).')
    ap.add_argument('--data', default=os.environ.get('NETBENCH_DATA') or os.path.dirname(HERE),
                    help='input root holding corpus.json, harness/results, appbench/ '
                         '(default: $NETBENCH_DATA, else tools/netbench)')
    ap.add_argument('--out', help='output dir for recap.json and RECAP.md (default: <data>/recap)')
    args = ap.parse_args()
    set_roots(args.data, args.out)
    os.makedirs(OUT, exist_ok=True)
    corpus, corpus_info = load_corpus()
    harness_runs, attempts, sustains, variant_defs = load_harness(corpus)
    opens, device_runs = load_device(corpus)

    rows = {}
    for vid, vdef in variant_defs.items():
        rows[vid] = {'kind': 'harness', 'appclient': appclient_of_variant(vid, vdef.get('tags')),
                     'tags': vdef.get('tags') or {}, 'description': vdef.get('description'),
                     'client_name': (vdef.get('client') or {}).get('clientName'),
                     'client_version': (vdef.get('client') or {}).get('clientVersion'),
                     'endpoint': (vdef.get('endpoint') or {}).get('path') if (vdef.get('endpoint') or {}).get('kind') != 'watch_page' else 'watch page'}
    for a in attempts:
        rows.setdefault(a['variant_id'], {'kind': 'harness', 'appclient': appclient_of_variant(a['variant_id'], a.get('tags')),
                                          'tags': a.get('tags') or {}, 'description': None})
    for o in opens:
        rid = device_row_id(o, rows)
        if rid not in rows:
            if o['source'] == 'RING':
                rows[rid] = {'kind': 'ring', 'appclient': 'RING', 'description': 'the app\'s own ring walk'}
            else:
                rows[rid] = {'kind': 'device', 'appclient': o['source'], 'description': 'device-only source'}
        for ans in o['_answers']:
            crow = f'nt-{ans["client"]}'
            if crow not in rows and f'device:{ans["client"]}' not in rows:
                rows[f'device:{ans["client"]}'] = {'kind': 'device', 'appclient': ans['client'],
                                                   'description': 'device-only source (ring answers)'}

    recs = aggregate(attempts, sustains, opens, rows)

    # ---------------------------------------------------------------- footnotes (data-driven)
    footnotes = []
    reclass = [a for a in attempts if (a.get('verdict') or {}).get('code') == 'BOTWALL' and a['_c']['answer'] != 'bot']
    for a in reclass:
        footnotes.append(f'`{a["_run"]}` {a["variant_id"]} {a["video_id"]}: the harness verdict is BOTWALL (the run '
                         f'stopped there), but the text is "{(a.get("playability") or {}).get("reason")}", an age gate '
                         f'under the fixed bot pattern (harness nb/net.py, fixed after this run). Counted as '
                         f'`REF age`, not as a bot challenge.')
    for a in attempts:
        if a['_c']['answer'] == 'bot':
            footnotes.append(f'Bot challenge: `{a["_run"]}` {a["variant_id"]} on {a["video_id"]} '
                             f'({a["_c"]["reason_text"]}); the run stopped there, so later cells of that run are '
                             f'missing, not failed. Not counted as that source\'s verdict on the video.')
    stopped_runs = [r for r in harness_runs if r.get('ended') or not r['footer']]
    for r in stopped_runs:
        why = r['ended'].get('message') if r.get('ended') else 'no footer line (the run was interrupted)'
        if any(a['_run'] == r['run_id'] for a in reclass):
            why += ' [an age gate the harness then misread as a bot wall, see above]'
        footnotes.append(f'Run `{r["run_id"]}` ended early: {why}; {r["records"]}/{r.get("planned") or "?"} attempts.')
    hidden = []
    for a in attempts:
        c = a['_c']
        if c['media'] == 'served' and c.get('neterr'):
            order = delivery_order(a['_cat'])
            best = next(x for x in order if x in c['served'])
            better = [x for x in c['neterr'] if order.index(x) < order.index(best)]
            if better:
                hidden.append(f'{a["_run"]} {a["variant_id"]} {a["video_id"]} (counted {SHOW[best]}, '
                              f'{"/".join(SHOW[x] for x in better)} hit a transport error)')
    if hidden:
        footnotes.append('Served-delivery class limited by a transport error, not by YouTube: ' + '; '.join(hidden) + '.')
    net_lte = sum(1 for a in attempts if a['_network'] == 'lte' and (a['_c']['answer'] == 'transport' or a['_c']['media'] == 'transport'))
    if net_lte:
        vids = Counter(a['video_id'] for a in attempts if a['_network'] == 'lte' and
                       (a['_c']['answer'] == 'transport' or a['_c']['media'] == 'transport'))
        footnotes.append(f'LTE harness transport errors ({net_lte} attempts, `+net`): SOCKS proxy failures to googlevideo or '
                         f'/player timeouts, on ' + ', '.join(f'{v} ({n})' for v, n in sorted(vids.items(), key=lambda kv: (-kv[1], kv[0]))) +
                         '. When the /player answer itself arrived it still counts in the harness-answer tier; '
                         'only the media verdict is missing.')
    for o in opens:
        out = o['_outcome']
        if out['cls'] == 'NO-DATA':
            others = o.get('_other_videos') or []
            footnotes.append(f'`{o["run_id"]}` {o["source"]} {o["video"]}: appbench says {o.get("verdict")}, but the '
                             f'open\'s log has no /player result for {o["video"]}' +
                             (f' (only for other videos: {", ".join(others[:6])}{" ..." if len(others) > 6 else ""})'
                              if others else '') + '. Counted as `no-data`, not NO-START.')
        elif out['cls'] == 'BOT-COOLDOWN':
            footnotes.append(f'`{o["run_id"]}` {o["source"]} {o["video"]} [{o["_tag"]}]: no /player at all; appbench '
                             f'flags `bot_cooldown` (the app\'s bot-check cooldown answered the open). Counted as '
                             f'`bot-cooldown`, not as a source verdict (appbench says {o.get("verdict")}).')
        elif out['cls'] == 'SHORT-RUN':
            footnotes.append(f'`{o["run_id"]}` {o["source"]} {o["video"]} [{o["_tag"]}]: {out["why"]}; appbench '
                             f'says {o.get("verdict")}. Not counted as PLAY (it never reached 150 s).')
        elif out['cls'] == 'PLAY-OK' and out.get('recovered_errors'):
            footnotes.append(f'`{o["run_id"]}` {device_label(o)} {o["video"]}: reached {o["max_pos_ms"] // 1000} s '
                             f'of {o["dur_ms"] // 1000} s (near EOF) after {out["recovered_errors"]} playback errors '
                             f'({out["e403"]} HTTP 403) that the app recovered from; appbench says {o.get("verdict")}. '
                             f'Counted as PLAY with that note.')
        elif out['cls'] == 'ABORTED':
            footnotes.append(f'`{o["run_id"]}` {o["source"]} {o["video"]} [{o["_tag"]}]: aborted ({out["why"]}); '
                             f'appbench verdict {o.get("verdict")} is not a verdict.')
    n_log = sum(1 for o in opens if o.get('_readiness_src') == 'log')
    n_list = sum(1 for o in opens if o.get('_readiness_src') == 'appbench-list')
    footnotes.append(f'Device readiness waits: {n_log} open(s) from the log line tagged with the video '
                     f'(`video=<id> readiness-wait ms=`), {n_list} from appbench\'s per-open list (not video-scoped).')
    footnotes.append('Device `/player` ms comes from pairing each `player-context` of the target video with the next '
                     '`player-http[C]` (ANDROID_REEL: `api-http[C] … reel_item_watch`) in the open\'s .log, then '
                     'matching those pairs to the `player-result` list by client in order; enrichment /player calls '
                     'fall out because they have no `player-result`.')
    footnotes.append('Harness `media` timings are the actual send time of the first successful probe; a delivery '
                     'probed later in a slot is left-censored (README "Probe order"). Pre-roll counts use yt-dlp\'s '
                     '`ad_wait_s` on the same answer.')
    only_pixel = sorted(v for v, d in corpus.items() if d.get('sources_only_pixel'))
    moved = sorted(f'{v} (corpus: {d.get("category")}, {", ".join(d.get("subcategories") or [])})'
                   for v, d in corpus.items() if d['recap_category'] == 'kids' and d.get('category') != 'made_for_kids')
    footnotes.append('Categories come from corpus.json merged with harness/corpus/pixel-2026-09-28.json by id' +
                     (f' (only in the latter: {", ".join(only_pixel)})' if only_pixel else '') +
                     '. made_for_kids=true wins over the corpus category' +
                     (f': {"; ".join(moved)} counted as `kids`' if moved else '') + '.')
    players = sorted({r['player_id'] for r in harness_runs if r.get('player_id')})
    footnotes.append('Every harness attempt is one trial; repeated counts for the same video come from separate runs, '
                     f'shown as `[Nv]`. Player JS versions across runs: {", ".join(players)}.')
    footnotes.append('Harness LTE rides the phone\'s LTE through a TCP-only SOCKS proxy from the PC (curl_cffi TLS), '
                     'not the app\'s OkHttp/Cronet stack; the device rows are the app itself.')

    ctx = {'recs': recs, 'rows': rows, 'corpus': corpus, 'attempts': attempts, 'sustains': sustains,
           'opens': opens, 'harness_runs': harness_runs, 'device_runs': device_runs, 'footnotes': footnotes}
    md = render_md(ctx)

    # ---------------------------------------------------------------- recap.json
    common = ['source', 'appclient', 'category', 'network', 'tier', 'attempts', 'videos', 'runs', 'per_video', 'notes']
    answer_fields = ['answer_ok_usable', 'answer_ok_usable_offered', 'answer_refused', 'refused_reasons',
                     'sabr_only_or_no_usable', 'bot_challenge', 'transport_error', 'harness_error']
    tier_fields = {
        'harness-answer': answer_fields,
        'harness-media': ['media_served_at_start', 'media_served_by_delivery', 'media_refused_at_start',
                          'media_transport_error', 'media_not_probed', 'harness_error'],
        'harness-sustain': ['sustain_ok_150s', 'sustain_ok_eof', 'sustain_fail', 'sustain_other', 'bot_challenge'],
        'device': ['device_opens', 'device_play_ok', 'device_play_ok_after_recovered_errors', 'device_no_start',
                   'device_died', 'device_aborted', 'device_no_data', 'device_bot_cooldown', 'device_short_run',
                   'device_builds', 'device_answers_in_ring_walks'] + [f for f in answer_fields if f != 'answer_ok_usable_offered'],
    }

    def finish(r):
        out = {}
        keep = set(common) | set(tier_fields.get(r['tier'], []))
        for k, v in r.items():
            if k.startswith('_') or k not in keep:
                continue
            if isinstance(v, set):
                v = sorted(v)
            elif isinstance(v, Counter):
                v = dict(sorted(v.items()))
            elif isinstance(v, defaultdict):
                v = {kk: vv for kk, vv in sorted(v.items())}
            out[k] = v
        if r['_player_ms']:
            out['player_ms_median'] = median(r['_player_ms'])
            out['player_ms_n'] = len(r['_player_ms'])
        if r['_first_served_s']:
            out['media_first_served_s_median'] = median(r['_first_served_s'])
        if r['_a_served_s']:
            out['media_adaptive_first_served_s_median'] = median(r['_a_served_s'])
        if r['tier'] == 'harness-media' and r['_ad']:
            out.update(preroll_stats(r['_ad']))
        if r['tier'] == 'device':
            ffs = [o['ff'] for o in r.get('_outcomes') or [] if o['ff'] is not None and o['out']['cls'] not in NON_VERDICT]
            out['device_first_frame_ms_median'] = median(ffs)
            out['device_opens_detail'] = [{'run': o['run'], 'build': o['build'], 'video': o['video'],
                                           'outcome': o['out'], 'appbench_verdict': o['appbench'],
                                           'first_frame_ms': o['ff'], 'winner': o['winner'],
                                           'readiness_wait_ms': o['wait'], 'info_ms': o['info_ms'],
                                           'answers': o['answers']}
                                          for o in sorted(r.get('_outcomes') or [], key=lambda o: (o['run'], o['video']))]
            ring = [a for a in r.get('_ans') or [] if a['ring']]
            if ring:
                out['device_ring_answers'] = [{k: a.get(k) for k in ('client', 'cls', 'reason', 'text', 'ms', 'pot', 'build')} for a in ring]
        if r['tier'] == 'harness-sustain':
            out['sustain_detail'] = r.get('_sustain')
        if r['tier'] == 'device':
            for k in tier_fields['device']:
                if out.get(k) is None and k != 'device_builds':
                    out[k] = 0
            if r['appclient'] == 'RING':
                # a ring row's answers are credited to each client's own row; here: opens only
                out['attempts'] = out['device_opens']
                for k in answer_fields + ['device_answers_in_ring_walks']:
                    out.pop(k, None)
        return out

    tier_rank = {t: i for i, t in enumerate(TIERS)}
    net_rank = {'wifi': 0, 'lte': 1, 'wifi-emu': 2}
    keys = sorted(recs, key=lambda k: (net_rank.get(k[2], 9), row_sort_key(k[0], rows),
                                       CAT_ORDER.index(k[1]) if k[1] in CAT_ORDER else 99, tier_rank.get(k[3], 9)))
    data = {
        'generated_by': 'recap/build_recap.py (deterministic; re-run to refresh)',
        'inputs': {
            'corpus': corpus_info,
            'harness_runs': harness_runs,
            'device_runs': device_runs,
        },
        'definitions': {
            'categories': {c: d for c, _, d in CATEGORIES},
            'tiers': {
                'harness-answer': 'every harness attempt: /player answer class (OK+usable delivery, refused with '
                                  'reason, SABR-only/no usable, bot challenge, transport error, harness error)',
                'harness-media': 'harness attempts whose answer was OK with a usable delivery: were the first '
                                 'bytes served (any delivery; per delivery A/P/H/D), refused (403 through 40 s), '
                                 'or a transport error',
                'harness-sustain': '150 s paced replay of a fresh answer (sustain runs)',
                'device': 'in-app opens on the Pixel (or the emulator for network wifi-emu). device_* count opens '
                          '(forced source, or RING rows for the app walk). attempts and answer_* count the /player '
                          'answers of this client seen on the device: its forced opens (incl. re-resolves) plus the '
                          'answers it gave inside RING walks (device_answers_in_ring_walks)',
            },
            'device_play_ok': 'bench-tick positions advanced to >=140 s of playing time, or <=10 s from the end, or ENDED',
            'bot_pattern': [p.pattern for p in BOT_RES],
        },
        'videos': {vid: {'category': v['recap_category'], 'corpus_category': v.get('category'),
                         'subcategories': v.get('subcategories'), 'made_for_kids': v.get('made_for_kids'),
                         'expected_signed_out': v.get('expected_signed_out')}
                   for vid, v in sorted(corpus.items())},
        'sources': {r: {k: v for k, v in rows[r].items()} for r in sorted(rows, key=lambda r: row_sort_key(r, rows))},
        'records': [finish(recs[k]) for k in keys],
        'footnotes': footnotes,
        'gaps': computed_gaps(recs, ctx),
    }
    with open(os.path.join(OUT, 'recap.json'), 'w') as fh:
        json.dump(data, fh, indent=1, sort_keys=False, ensure_ascii=False, default=str)
        fh.write('\n')
    with open(os.path.join(OUT, 'RECAP.md'), 'w') as fh:
        fh.write(md)
    print(f'recap: {len(attempts)} harness attempts, {len(sustains)} sustain cells, {len(opens)} device opens, '
          f'{len(data["records"])} records -> {os.path.join(OUT, "recap.json")}, '
          f'{os.path.join(OUT, "RECAP.md")} ({md.count(chr(10))} lines)')


if __name__ == '__main__':
    main()
