"""`netbench sustain`: does a cell that served 4 KiB keep serving like a real player?

For each chosen cell of earlier run(s) (e.g. every PLAY), make a FRESH /player request with that
cell's exact variant (the resolved definition stored in the source run's header; same identity
recipe), then play:

  startup   wait out this answer's own pre-roll wait (yt-dlp's ad_wait_s), then request the first
            chunk of every stream, retrying the SAME URL on the startup schedule until it is served
            (honours the first-OK time). Playback time t=0 = the moment all first chunks were served.
  steady    keep --prebuffer-s of media ahead of the playback clock: adaptive video + audio are read
            in successive byte ranges sized from the format's bitrate (--chunk-s of media per range);
            HLS reads whole segments (and reloads the playlist when live).
  seek      at t=--seek-at-s the buffer is dropped and every stream jumps to --seek-frac of the file
            (VOD only), like a user seek.
  end       at t=--duration-s, or when every stream reached the end of the file.

A refused chunk is retried once after 2 s; a second refusal ends the cell: FAIL@<t>s (<code>),
t = playback seconds of the FIRST refusal. Verdicts: SUSTAIN-OK, FAIL, STARTUP-FAIL, NO-MEDIA
(the fresh answer had no such delivery), UNSUPPORTED (live/OTF adaptive), HARNESS_ERROR.
Same Net: pacing floors, caps, bot-wall / 429 stop, proxy for every request.
"""

from __future__ import annotations

import fnmatch
import glob
import json
import pathlib
import platform
import sys
import time
import traceback
import urllib.parse

import curl_cffi

from . import RESULTS_DIR, harness_version
from .identity import IdentityStore
from .innertube import analyse, build_request, parse_player_response
from .media import MediaProber, _hls_choose, _hls_parse_master, _byterange, _attrs
from .net import Net, StopRun, Ledger
from .playerjs import PlayerJS, deno_version, ytdlp_commit
from .util import fingerprint, jdump, now_iso, url_summary, expand_placeholders, strip_nulls, merge_patch

EXIT_OK, EXIT_ERROR, EXIT_USAGE, EXIT_STOPPED, EXIT_CAP = 0, 1, 2, 3, 4
SUSTAIN_BODY_CAP = 32_000_000
RETRY_AFTER_S = 2.0


def log(msg=''):
    print(msg, file=sys.stderr, flush=True)


# ------------------------------------------------------------------ cell selection
def load_cells(specs, only_codes, variant_globs, video_ids, categories):
    cells, sources = {}, []
    for spec in specs:
        for path in sorted(glob.glob(spec)) or [spec]:
            header, attempts = None, []
            with open(path) as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    o = json.loads(line)
                    if o.get('type') == 'header':
                        header = o
                    elif o.get('type') == 'attempt':
                        attempts.append(o)
            if header is None:
                continue
            vdefs = {v['id']: v for v in header.get('variants') or []}
            sources.append({'path': path, 'run_id': header.get('run_id'), 'network': header.get('network'),
                            'player_id': (header.get('player') or {}).get('player_id')})
            for a in attempts:
                code = (a.get('verdict') or {}).get('code')
                if only_codes and code not in only_codes:
                    continue
                if variant_globs and not any(fnmatch.fnmatch(a['variant_id'], g) for g in variant_globs):
                    continue
                if video_ids and a['video_id'] not in video_ids:
                    continue
                if categories and a.get('category') not in categories and a.get('subcategory') not in categories:
                    continue
                key = (a['variant_id'], a['video_id'])
                if key in cells or a['variant_id'] not in vdefs:
                    continue
                cells[key] = {'variant': vdefs[a['variant_id']], 'source': a, 'source_run': header.get('run_id'),
                              'source_network': header.get('network'),
                              'source_player': (header.get('player') or {}).get('player_id')}
    return list(cells.values()), sources


def pick_delivery(requested: str, source_attempt: dict) -> str:
    if requested != 'auto':
        return requested
    dels = source_attempt.get('deliveries') or {}
    live = (source_attempt.get('video') or {}).get('is_live')
    order = ['hls', 'adaptive', 'progressive'] if live else ['adaptive', 'hls', 'progressive']
    for k in order:
        if (dels.get(k) or {}).get('result') == 'PLAY':
            return k
    return 'adaptive'


# ------------------------------------------------------------------ streams
class Stream:
    def __init__(self, name):
        self.name = name
        self.buffered_until = 0.0      # playback seconds of media already fetched
        self.eof = False
        self.failed = None             # {'t_s', 'code', 'status'}
        self.chunks_ok = 0
        self.bytes = 0
        self.blips = []
        self.first_ok_after_s = None   # startup, seconds after the /player answer
        self.hold_until = 0.0          # monotonic; a live playlist with nothing new waits


class RangeStream(Stream):
    def __init__(self, name, url, fmt, chunk_s):
        super().__init__(name)
        self.url = url
        self.fmt = fmt
        self.length = int(fmt.get('contentLength') or 0) or None
        bitrate = int(fmt.get('averageBitrate') or fmt.get('bitrate') or 0)
        self.bps = max(bitrate // 8, 8_000)
        self.chunk_bytes = max(int(self.bps * chunk_s), 65_536)
        self.chunk_media_s = self.chunk_bytes / self.bps
        self.offset = 0

    def unsupported(self):
        if self.fmt.get('targetDurationSec'):
            return 'live-adaptive'
        if self.fmt.get('type') == 'FORMAT_STREAM_TYPE_OTF':
            return 'otf'
        if not self.length:
            return 'no-contentLength'
        return None

    def next_request(self):
        if self.offset >= self.length:
            self.eof = True
            return None
        ln = min(self.chunk_bytes, self.length - self.offset)
        return {'url': self.url, 'range': (self.offset, ln), 'media_s': ln / self.bps, 'kind': 'range',
                'media_pos': self.offset / self.bps, 'media_end': (self.offset + ln) / self.bps}

    def advance(self, req):
        self.offset += req['range'][1]

    def seek(self, frac):
        self.offset = int(self.length * frac)
        return {'offset': self.offset}

    def describe(self):
        return {'itag': self.fmt.get('itag'), 'mime': (self.fmt.get('mimeType') or '').split(';')[0],
                'height': self.fmt.get('height'), 'bitrate': self.fmt.get('averageBitrate') or self.fmt.get('bitrate'),
                'content_length': self.length, 'chunk_bytes': self.chunk_bytes,
                'chunk_media_s': round(self.chunk_media_s, 2), 'url': url_summary(self.url)}


class HlsStream(Stream):
    """A media playlist: whole segments (or their EXT-X-BYTERANGE), init map first; live playlists
    are reloaded when the known segments run out."""

    def __init__(self, name, playlist_url):
        super().__init__(name)
        self.playlist_url = playlist_url
        self.segments = []   # {'seq', 'uri', 'range', 'dur'}
        self.init = None
        self.init_done = False
        self.live = False
        self.idx = 0
        self.last_seq = None
        self.reloads = 0

    def load(self, text, base):
        segs, init, live, media_seq = parse_hls_media(text, base)
        self.live = live
        if init and not self.init_done:
            self.init = init
        if self.last_seq is None:
            # live: start three segments behind the edge, like a player; VOD: from the start
            start = max(0, len(segs) - 3) if live else 0
            self.segments = segs[start:]
        else:
            self.segments = [s for s in segs if s['seq'] > self.last_seq]
        self.idx = 0

    def next_request(self):
        if self.init and not self.init_done:
            return {'url': self.init['uri'], 'range': self.init['range'], 'media_s': 0.0, 'kind': 'init'}
        if self.idx < len(self.segments):
            s = self.segments[self.idx]
            pos = None if self.live else sum(x['dur'] for x in self.segments[:self.idx])
            return {'url': s['uri'], 'range': s['range'], 'media_s': s['dur'], 'kind': 'segment', 'seq': s['seq'],
                    'media_pos': pos, 'media_end': None if pos is None else pos + s['dur']}
        if self.live:
            return {'url': self.playlist_url, 'range': None, 'media_s': 0.0, 'kind': 'playlist'}
        self.eof = True
        return None

    def advance(self, req):
        if req['kind'] == 'init':
            self.init_done = True
        elif req['kind'] == 'segment':
            self.last_seq = req.get('seq')
            self.idx += 1

    def seek(self, frac):
        if self.live:
            return {'skipped': 'live'}
        self.idx = int(len(self.segments) * frac)
        return {'segment_index': self.idx, 'segments': len(self.segments)}

    def describe(self):
        return {'playlist': url_summary(self.playlist_url), 'live': self.live, 'segments_known': len(self.segments),
                'init': bool(self.init)}


def parse_hls_media(text, base):
    lines = [l.strip() for l in text.splitlines()]
    segs, init, live = [], None, '#EXT-X-ENDLIST' not in text
    media_seq = 0
    dur = None
    rng = None
    prev_end = None
    for line in lines:
        if line.startswith('#EXT-X-MEDIA-SEQUENCE:'):
            try:
                media_seq = int(line.split(':', 1)[1])
            except ValueError:
                pass
        elif line.startswith('#EXT-X-MAP:'):
            a = _attrs(line)
            if a.get('uri'):
                init = {'uri': urllib.parse.urljoin(base, a['uri']),
                        'range': _byterange(a['byterange'], 0) if a.get('byterange') else None}
        elif line.startswith('#EXTINF:'):
            try:
                dur = float(line.split(':', 1)[1].split(',')[0])
            except ValueError:
                dur = 5.0
        elif line.startswith('#EXT-X-BYTERANGE:'):
            rng = _byterange(line.split(':', 1)[1], prev_end)
        elif line and not line.startswith('#'):
            segs.append({'seq': media_seq + len(segs), 'uri': urllib.parse.urljoin(base, line), 'range': rng,
                         'dur': dur or 5.0})
            if rng:
                prev_end = rng[0] + rng[1]
            dur, rng = None, None
    return segs, init, live, media_seq


# ------------------------------------------------------------------ the player
class Player:
    def __init__(self, net: Net, variant: dict, variables: dict, args, t_resp: float, rec: dict):
        self.rec = rec
        chunks = rec['chunks']
        self.net = net
        self.variant = variant
        self.variables = variables
        self.args = args
        self.t_resp = t_resp
        self.chunks = chunks
        self.play0 = None

    def _headers(self, extra=None):
        media = self.variant.get('media') or {}
        h = strip_nulls(expand_placeholders(merge_patch({}, media.get('headers') or {}), self.variables))
        h.setdefault('Accept', '*/*')
        h['Accept-Encoding'] = 'identity'
        if extra:
            h.update(extra)
        return h

    def fetch(self, stream, req, *, phase, retry=False):
        media = self.variant.get('media') or {}
        url = req['url']
        extra = {}
        if req['range']:
            off, ln = req['range']
            if (req['kind'] == 'range' and media.get('range_mode', 'query') == 'query'
                    and 'googlevideo.com/videoplayback?' in url):
                url = url + ('&' if '?' in url else '?') + f'range={off}-{off + ln - 1}'
            else:
                extra['Range'] = f'bytes={off}-{off + ln - 1}'
        res = self.net.request('GET', url, headers=self._headers(extra), impersonate=media.get('impersonate') or 'chrome',
                               http_version=media.get('http_version'), body_cap=SUSTAIN_BODY_CAP,
                               purpose=f'sustain:{stream.name}:{req["kind"]}')
        if res.status == 429:
            raise StopRun('http-429', 'HTTP 429 from googlevideo during sustain - stopping')
        want = req['range'][1] if req['range'] else None
        ok = res.status in (200, 206) and len(res.body) > 0 and (want is None or len(res.body) >= want)
        code = None
        if not ok:
            code = ('NET' if res.status is None else f'HTTP{res.status}' if res.status not in (200, 206)
                    else 'SHORT' if want and len(res.body) < want else 'EMPTY')
        row = {'stream': stream.name, 'phase': phase, 'kind': req['kind'],
               'play_s': None if self.play0 is None else round(res.t_start - self.play0, 2),
               'wall_s': round(res.t_start - self.t_resp, 2),
               'range': list(req['range']) if req['range'] else None,
               'media_pos_s': _r2(req.get('media_pos')), 'media_end_s': _r2(req.get('media_end')),
               'status': res.status,
               'bytes': len(res.body), 'ok': ok, 'code': code, 'ttfb_ms': _r(res.ttfb_ms), 'ms': _r(res.elapsed_ms),
               'content_range': res.headers.get('content-range'), 'error': res.error, 'retry': retry or None,
               'new_conn': res.new_connections}
        self.chunks.append(row)
        return res, ok, code, row

    def startup(self, streams, ad_wait_s, schedule):
        """Honour the answer's pre-roll wait, then retry each stream's first request on the schedule
        (slots relative to the /player answer, shifted by ad_wait_s). An init segment or playlist
        is followed at once by the first media request."""
        base = float(ad_wait_s or 0)
        slots = [base + x for x in schedule]
        for st in streams:
            last, served = None, False
            for slot in slots:
                wait = self.t_resp + slot - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                req = st.next_request()
                if req is None:
                    served = True
                    break
                res, ok, code, row = self.fetch(st, req, phase='startup')
                last = (res.status, code)
                while ok:
                    self._on_ok(st, req, res)
                    st.first_ok_after_s = row['wall_s']
                    if req['kind'] not in ('init', 'playlist'):
                        break
                    req = st.next_request()
                    if req is None:
                        break
                    res, ok, code, row = self.fetch(st, req, phase='startup')
                    last = (res.status, code)
                if ok:
                    served = True
                    break
            if not served:
                st.failed = {'t_s': None, 'code': last[1] if last else 'STARTUP', 'status': last[0] if last else None}
                return False
        return True

    def _on_ok(self, st, req, res):
        if req['kind'] == 'playlist':
            st.reloads += 1
            st.load(res.text(), getattr(res, 'final_url', None) or req['url'])
            if not st.segments:  # nothing new at the live edge yet: next reload in 2 s
                st.hold_until = time.monotonic() + 2.0
            return
        st.advance(req)
        st.chunks_ok += 1 if req['kind'] in ('range', 'segment') else 0
        st.bytes += len(res.body)
        st.buffered_until += req['media_s']

    def play(self, streams, duration_s, prebuffer_s, seek_at_s, seek_frac):
        self.play0 = time.monotonic()
        seek_info = None
        while True:
            now = time.monotonic() - self.play0
            if now >= duration_s:
                break
            if seek_info is None and seek_at_s is not None and now >= seek_at_s:
                seek_info = {'at_s': round(now, 2), 'streams': {}}
                for st in streams:
                    if st.failed:
                        continue
                    st.eof = False
                    seek_info['streams'][st.name] = st.seek(seek_frac)
                    st.buffered_until = now
            active = [st for st in streams if not st.eof and not st.failed]
            if not active:
                break
            ready = [st for st in active if st.hold_until <= time.monotonic()]
            if not ready:
                time.sleep(max(0.05, min(st.hold_until for st in active) - time.monotonic()))
                continue
            active = ready
            # the stream whose buffer is shortest goes next, when it falls within the prebuffer window
            st = min(active, key=lambda s: s.buffered_until)
            due = st.buffered_until - prebuffer_s
            if due > now:
                wake = min(due, duration_s, seek_at_s if (seek_info is None and seek_at_s is not None) else duration_s)
                time.sleep(max(0.0, wake - now))
                continue
            req = st.next_request()
            if req is None:
                continue
            res, ok, code, row = self.fetch(st, req, phase='play')
            if seek_info is not None:
                row['after_seek'] = True
            if ok:
                self._on_ok(st, req, res)
                continue
            first_t = row['play_s']
            time.sleep(RETRY_AFTER_S)
            res2, ok2, code2, row2 = self.fetch(st, req, phase='retry', retry=True)
            if ok2:
                self._on_ok(st, req, res2)
                st.blips.append({'t_s': first_t, 'code': code})
                continue
            st.failed = {'t_s': first_t, 'code': code, 'status': res.status, 'retry_code': code2,
                         'media_pos_s': row.get('media_pos_s'), 'media_end_s': row.get('media_end_s')}
            break
        return seek_info


def _r(v):
    return None if v is None else round(v, 1)


def _r2(v):
    return None if v is None else round(v, 2)


# ------------------------------------------------------------------ one cell
def sustain_cell(net, player_js, identities, prober, cell, args, run_id, trial):
    variant = cell['variant']
    src = cell['source']
    vid = src['video_id']
    delivery = pick_delivery(args.delivery, src)
    before = net.ledger.snapshot()
    rec = {'type': 'sustain', 'ts': now_iso(), 'run_id': run_id, 'network': args.network,
           'source_run': cell['source_run'], 'source_network': cell['source_network'],
           'source_label': (src.get('verdict') or {}).get('label'), 'video_id': vid,
           'category': src.get('category'), 'subcategory': src.get('subcategory'),
           'variant_id': variant['id'], 'tags': variant.get('tags') or {}, 'delivery': delivery,
           'params': {'duration_s': args.duration_s, 'chunk_s': args.chunk_s, 'prebuffer_s': args.prebuffer_s,
                      'seek_at_s': args.seek_at_s, 'seek_frac': args.seek_frac},
           'player': {}, 'playability': None, 'streams': {}, 'chunks': [], 'verdict': None}
    try:
        idc = variant['identity']
        ident = None
        if idc.get('pages'):
            ua = variant.get('user_agent') or (variant.get('client') or {}).get('userAgent')
            ident, fresh = identities.get(variant, trial, vid, ua, variant.get('transport'))
            rec['identity'] = {'visitor_fp': fingerprint(ident.visitor), 'flags_fp': fingerprint(ident.flags),
                               'pages_fetched_now': [p['kind'] for p in fresh]}
            if any(p.get('bot_wall') for p in fresh):
                raise StopRun('bot-wall', f'bot wall on an identity page ({vid}, {variant["id"]})')
        req = build_request(variant, vid, ident, player_js.sts)
        tr = variant.get('transport') or {}
        res = net.request(req['method'], req['url'], headers=req['headers'], data=req['body'],
                          impersonate=tr.get('impersonate'), http_version=tr.get('http_version'),
                          ja3=tr.get('ja3'), akamai=tr.get('akamai'), browser_headers=tr.get('browser_headers'),
                          purpose=f'sustain-player:{variant["id"]}')
        t_resp = time.monotonic()
        rec['player'] = {'status': res.status, 'ms': round(res.elapsed_ms, 1), 'error': res.error}
        pr = None
        if res.error is None and res.status == 200:
            pr, _perr = parse_player_response(variant, res)
        if pr is None:
            rec['verdict'] = {'code': 'NO-MEDIA', 'reason': f'player HTTP {res.status} {res.error or ""}'.strip()}
            return rec
        an = analyse(pr)
        rec['playability'] = an['playability']
        if an['playability']['bot_wall']:
            rec['verdict'] = {'code': 'BOTWALL', 'reason': an['playability']['reason']}
            rec['stop'] = {'code': 'bot-wall', 'message': f'bot wall: {an["playability"]["reason"]} ({vid}, {variant["id"]})'}
            return rec
        if an['playability']['status'] != 'OK':
            rec['verdict'] = {'code': 'NO-MEDIA', 'reason': f'{an["playability"]["status"]} {an["playability"]["reason_code"] or ""}'.strip()}
            return rec
        ads = player_js.ad_wait(pr, vid, variant['id'])
        rec['ad_wait_s'] = ads.get('ad_wait_s')
        targets, solve = prober.build_targets(pr, vid, variant)
        rec['solve'] = {k: solve.get(k) for k in ('n_requested', 'sig_requested', 'n_missing', 'sig_missing', 'ms', 'error')}
        by = {t.name: t for t in targets}
        player = Player(net, variant, req['vars'], args, t_resp, rec)
        streams = []
        if delivery == 'adaptive':
            for name in ('adaptive_video', 'adaptive_audio'):
                t = by.get(name)
                if t and t.url:
                    streams.append(RangeStream(name, t.url, t.fmt, args.chunk_s))
        elif delivery == 'progressive':
            t = by.get('progressive')
            if t and t.url:
                streams.append(RangeStream('progressive', t.url, t.fmt, args.chunk_s))
        elif delivery == 'hls':
            streams = open_hls(net, by.get('hls'), player)
        if not streams:
            f = an['formats']
            why = ('SABR' if delivery == 'adaptive' and f['adaptive_total'] and not (f['adaptive_url'] + f['adaptive_cipher'])
                   else f'no-{delivery}')
            rec['verdict'] = {'code': 'NO-MEDIA', 'reason': why}
            return rec
        unsupported = [s.unsupported() for s in streams if isinstance(s, RangeStream) and s.unsupported()]
        if unsupported:
            rec['verdict'] = {'code': 'UNSUPPORTED', 'reason': unsupported[0]}
            return rec
        started = player.startup(streams, rec['ad_wait_s'], args.startup_schedule)
        rec['startup'] = {'ad_wait_s': rec['ad_wait_s'],
                          'first_ok_after_s': {s.name: s.first_ok_after_s for s in streams}}
        if not started:
            bad = next(s for s in streams if s.failed)
            rec['verdict'] = {'code': 'STARTUP-FAIL', 'first_failure': {'stream': bad.name, **bad.failed}}
        else:
            seek = player.play(streams, args.duration_s, args.prebuffer_s, args.seek_at_s, args.seek_frac)
            rec['seek'] = seek
            played = round(time.monotonic() - player.play0, 1)
            failed = [s for s in streams if s.failed]
            if failed:
                s = failed[0]
                rec['verdict'] = {'code': 'FAIL', 'first_failure': {'stream': s.name, **s.failed}, 'played_s': played}
            else:
                eof = all(s.eof for s in streams)
                rec['verdict'] = {'code': 'SUSTAIN-OK', 'played_s': played, 'eof': eof}
        for s in streams:
            rec['streams'][s.name] = {**s.describe(), 'chunks_ok': s.chunks_ok, 'bytes': s.bytes, 'eof': s.eof,
                                      'failed': s.failed, 'blips': s.blips,
                                      'first_ok_after_s': s.first_ok_after_s}
        blips = [b for s in streams for b in s.blips]
        if blips:
            rec['verdict']['blips'] = blips
    except StopRun as e:
        rec['verdict'] = rec.get('verdict') or {'code': 'STOPPED', 'reason': e.code}
        rec['stop'] = {'code': e.code, 'message': e.message}
    except Exception as e:
        rec['verdict'] = {'code': 'HARNESS_ERROR', 'reason': f'{type(e).__name__}: {str(e)[:200]}'}
        rec['traceback'] = traceback.format_exc()[-1500:]
    finally:
        rec['bytes'] = Ledger.delta(before, net.ledger.snapshot())
        if rec.get('verdict'):
            rec['verdict']['label'] = sustain_label(rec)
    return rec


def open_hls(net, target, player):
    """Master -> variant playlist (+ audio group playlist). Returns HlsStreams with segments loaded."""
    if not target or not target.url:
        return []
    media = player.variant.get('media') or {}
    rec = player.rec

    def get(url, purpose):
        return net.request('GET', url, headers=player._headers(), impersonate=media.get('impersonate') or 'chrome',
                           http_version=media.get('http_version'), body_cap=SUSTAIN_BODY_CAP, purpose=purpose)
    res = get(target.url, 'sustain:hls:master')
    rec['hls_master'] = {'status': res.status, 'bytes': len(res.body)}
    if res.status != 200:
        return []
    variants, mlist = _hls_parse_master(res.text(), res.final_url or target.url)
    if not variants:
        return []
    pick = _hls_choose(variants)
    out = [('hls_video', pick['uri'])]
    group = pick.get('audio')
    if group:
        with_uri = [m for m in mlist if m.get('type') == 'AUDIO' and m.get('group-id') == group and m.get('uri')]
        chosen = next((m for m in with_uri if m.get('default') == 'YES'), with_uri[0] if with_uri else None)
        if chosen:
            out.append(('hls_audio', chosen['uri']))
    streams = []
    for name, url in out:
        r = get(url, f'sustain:{name}:playlist')
        if r.status != 200:
            return []
        st = HlsStream(name, url)
        st.load(r.text(), r.final_url or url)
        streams.append(st)
    return streams


def sustain_label(rec):
    v = rec['verdict']
    code = v['code']
    d = {'adaptive': 'A', 'progressive': 'P', 'hls': 'H'}.get(rec.get('delivery'), '?')
    if code == 'SUSTAIN-OK':
        blips = f' {len(v["blips"])} blip(s)' if v.get('blips') else ''
        end = f'EOF@{v["played_s"]:.0f}s' if v.get('eof') else f'{v["played_s"]:.0f}s'
        return f'SUSTAIN-OK {d} {end}{blips}'
    if code == 'FAIL':
        ff = v['first_failure']
        return f'FAIL@{ff["t_s"]:.0f}s ({ff["code"]}) {d}:{ff["stream"]}'
    if code == 'STARTUP-FAIL':
        ff = v['first_failure']
        return f'STARTUP-FAIL ({ff.get("code")}) {d}:{ff["stream"]}'
    return f'{code} ({v.get("reason")})' if v.get('reason') else code


# ------------------------------------------------------------------ command
def run(args) -> int:
    only = {x for spec in (args.only or ['PLAY']) for x in spec.split(',') if x}
    vglobs = [x for spec in (args.variant_ids or []) for x in spec.split(',') if x]
    vids = {x for spec in (args.videos or []) for x in spec.split(',') if x}
    cats = {x for spec in (args.categories or []) for x in spec.split(',') if x}
    cells, sources = load_cells(args.source, only, vglobs, vids, cats)
    if not cells:
        log('error: no cell matches (check --from / --only / --variant-ids / --videos)')
        return EXIT_USAGE
    if len(cells) > args.max_cells:
        log(f'error: {len(cells)} cells selected, more than --max-cells {args.max_cells}; narrow the selection '
            f'or raise --max-cells deliberately')
        return EXIT_USAGE
    args.startup_schedule = sorted({float(x) for x in args.startup_schedule.split(',') if x.strip()})
    if args.dry_run:
        for c in cells:
            log(f'{c["variant"]["id"]:<34} {c["source"]["video_id"]}  from {c["source_run"]} '
                f'({c["source"]["verdict"].get("label")})  -> delivery {pick_delivery(args.delivery, c["source"])}')
        log(f'dry-run: {len(cells)} cell(s), ~{args.duration_s:.0f} s of playback each; no network used')
        return EXIT_OK

    run_id = args.run_id or f'sustain-{time.strftime("%Y%m%d-%H%M%S")}-{args.network}'
    out_dir = pathlib.Path(args.out_dir or RESULTS_DIR)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f'{run_id}.jsonl'
    vlog = log if args.verbose else (lambda *a, **k: None)
    net = Net(proxy=args.proxy, delay_youtube=max(3.0, args.delay_s), delay_media=max(1.0, args.media_delay_s),
              max_requests=args.max_requests, max_media_requests=args.max_media_requests, log=vlog)
    player_id = args.player_id or next((s['player_id'] for s in sources if s['player_id']), None)
    log(f'netbench sustain {run_id}: {len(cells)} cell(s) x {args.duration_s:.0f} s, network={args.network} '
        f'proxy={args.proxy or "none"} -> {out_path}')
    fh = out_path.open('w')

    def write(o):
        fh.write(jdump(o) + '\n')
        fh.flush()

    header = {'type': 'header', 'mode': 'sustain', 'run_id': run_id, 'ts': now_iso(), 'network': args.network,
              'proxy': args.proxy, 'harness_version': harness_version(), 'curl_cffi': curl_cffi.__version__,
              'python': platform.python_version(), 'deno': deno_version(), 'ytdlp': ytdlp_commit(),
              'sources': sources, 'argv': sys.argv[1:], 'planned_attempts': len(cells),
              'trials': 1, 'schedule_s': args.startup_schedule,
              'params': {'duration_s': args.duration_s, 'chunk_s': args.chunk_s, 'prebuffer_s': args.prebuffer_s,
                         'seek_at_s': args.seek_at_s, 'seek_frac': args.seek_frac, 'delivery': args.delivery},
              'cells': [{'variant_id': c['variant']['id'], 'video_id': c['source']['video_id'],
                         'source_run': c['source_run']} for c in cells],
              'variants': [c['variant'] for c in cells]}
    pj = PlayerJS(net, log=vlog)
    stopped, code, n = None, EXIT_OK, 0
    try:
        header['player'] = pj.bootstrap(player_id)
        write(header)
        identities = IdentityStore(net, log=vlog)
        prober = MediaProber(net, pj, [0.0], 0.0, log=vlog)
        for i, cell in enumerate(cells, 1):
            n += 1
            rec = sustain_cell(net, pj, identities, prober, cell, args, run_id, trial=i if args.fresh_identity else 1)
            write(rec)
            v = rec['verdict'] or {}
            ok = sum(1 for c in rec['chunks'] if c['ok'])
            log(f'[{i}/{len(cells)}] {cell["variant"]["id"]:<30} {rec["video_id"]}  {v.get("label", ""):<40} '
                f'chunks {ok}/{len(rec["chunks"])} down={rec["bytes"]["total"]["down"]:,}')
            if rec.get('stop'):
                raise StopRun(rec['stop']['code'], rec['stop']['message'])
    except StopRun as e:
        stopped = {'code': e.code, 'message': e.message}
        code = EXIT_CAP if e.code == 'request-cap' else EXIT_STOPPED
        log(f'STOPPED: {e.message}')
    except KeyboardInterrupt:
        stopped = {'code': 'interrupted', 'message': 'KeyboardInterrupt'}
        code = EXIT_STOPPED
    finally:
        if 'player' not in header:
            write(header)
        totals = Ledger.delta(Ledger().snapshot(), net.ledger.snapshot())
        write({'type': 'footer', 'run_id': run_id, 'ts': now_iso(), 'stopped': stopped, 'attempts': n,
               'totals': totals})
        fh.close()
        net.close()
    log(f'done: {n} cell(s); youtube requests={totals["youtube"]["requests"]} media requests='
        f'{totals["media"]["requests"]}; bytes up={totals["total"]["up"]:,} down={totals["total"]["down"]:,}')
    log(f'results: {out_path}')
    return code
