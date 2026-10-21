"""Media probes: does googlevideo SERVE the URLs a /player answer handed out, and WHEN.

"PLAY" in this harness means "media bytes were served" (HTTP 200/206 with a body, for every
resource a delivery needs) - not decoded, not played for N seconds. Decode-proof is the phone stage.

Every delivery type the answer offers is probed independently:
  adaptive_video  best <=720p video with a url/signatureCipher (range 0-4095)
  adaptive_audio  best audio with a url/signatureCipher (range 0-4095)
  progressive     itag 18 (or another muxed format) with a url/signatureCipher
  hls             master -> <=720p variant playlist -> [EXT-X-MAP init] -> first segment, and when
                  the variant names an AUDIO group with its own URI: that audio playlist -> [init] ->
                  first segment. EXT-X-BYTERANGE honoured; relative URIs resolve against the final
                  (post-redirect) URL of the playlist that contains them.
  dash            MPD -> <=720p video representation [init] + first segment, and the best audio
                  representation [init] + first segment (BaseURL inherited MPD > Period > AdaptationSet >
                  Representation; SegmentList, SegmentTemplate $Number$/$Time$/SegmentTimeline, SegmentBase)

Time axis: t=0 is the moment the /player response arrived. Each target has its own schedule (default
0,2,4,6,10,20,40 s); a target that was not served is retried with the SAME URL at the next slot still
in the future. A manifest chain resumes at the step that failed. Availability is reported
interval-censored: (last failed send, first successful send]; left-censored if the first probe
succeeded, right-censored if none did.
"""

from __future__ import annotations

import hashlib
import re
import time
import urllib.parse
import xml.etree.ElementTree as ET

from .net import Net, StopRun
from .util import url_summary, expand_placeholders, strip_nulls, merge_patch

PROBE_BYTES = 4096
PROBE_BODY_CAP = 65_536       # curl aborts a probe whose body would exceed this (range ignored)
MANIFEST_BODY_CAP = 4_000_000 # MPDs must be parsed whole (live MPDs run ~1.4 MB)
PLAYLIST_HEAD = 262_144       # HLS media playlists: only the head is read (first segment + map)


# ------------------------------------------------------------------ format picking
def _has_url(f):
    return bool(f.get('url') or f.get('signatureCipher') or f.get('cipher'))


def _pick_video(adaptive):
    cands = []
    for f in adaptive:
        if not (f.get('mimeType') or '').startswith('video/') or not _has_url(f):
            continue
        h = f.get('height') or 0
        if h and h > 720:
            continue
        otf = f.get('type') == 'FORMAT_STREAM_TYPE_OTF'
        cands.append(((0 if otf else 1), h, f.get('bitrate') or 0, f))
    if not cands:
        return None
    cands.sort(key=lambda x: x[:3], reverse=True)
    return cands[0][3]


def _pick_audio(adaptive):
    cands = []
    for f in adaptive:
        if not (f.get('mimeType') or '').startswith('audio/') or not _has_url(f):
            continue
        track = f.get('audioTrack') or {}
        default = 1 if (track.get('audioIsDefault') or not track) else 0
        otf = f.get('type') == 'FORMAT_STREAM_TYPE_OTF'
        cands.append(((0 if otf else 1), default, f.get('bitrate') or 0, f))
    if not cands:
        return None
    cands.sort(key=lambda x: x[:3], reverse=True)
    return cands[0][3]


def _pick_progressive(progressive):
    usable = [f for f in progressive if _has_url(f)]
    if not usable:
        return None
    for f in usable:
        if f.get('itag') == 18:
            return f
    return usable[0]


def _cipher_parts(f):
    if f.get('url'):
        return f['url'], None, None, 'url'
    sc = urllib.parse.parse_qs(f.get('signatureCipher') or f.get('cipher') or '')
    return (sc.get('url') or [None])[0], (sc.get('s') or [None])[0], (sc.get('sp') or [None])[0], 'cipher'


def collect_challenges(pr: dict) -> tuple[set, set]:
    """n challenges and signature lengths needed by the formats/manifests we might probe."""
    sd = pr.get('streamingData') or {}
    n, sig = set(), set()
    for f in (sd.get('adaptiveFormats') or []) + (sd.get('formats') or []):
        url, s, _sp, _src = _cipher_parts(f)
        if s:
            sig.add(len(s))
        if url:
            q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
            if q.get('n'):
                n.add(q['n'][0])
    for key in ('hlsManifestUrl', 'dashManifestUrl'):
        url = sd.get(key)
        if url:
            m = re.search(r'/n/([^/]+)/', urllib.parse.urlparse(url).path)
            if m:
                n.add(m.group(1))
    return n, sig


# ------------------------------------------------------------------ targets
class Target:
    def __init__(self, name, fmt=None, url=None, finalize_error=None, source=None):
        self.name = name
        self.fmt = fmt or {}
        self.url = url
        self.finalize_error = finalize_error
        self.source = source
        self.attempts = []
        self.first_ok_after_s = None
        self.first_ok_slot = None
        self.done = False
        self.parse_error = None
        # manifest chains: ordered steps; each {step, url, range:(off,len)|None, kind}
        self.steps = [{'step': 'master' if name == 'hls' else 'mpd', 'url': url, 'range': None,
                       'kind': 'manifest'}] if name in ('hls', 'dash') else []
        self.pos = 0
        self.chain = {}

    def availability(self) -> dict:
        fails_before = [a['at_s'] for a in self.attempts if a.get('ok') is False
                        and (self.first_ok_after_s is None or a['at_s'] < self.first_ok_after_s)]
        lo = max(fails_before) if fails_before else None
        hi = self.first_ok_after_s
        if hi is None:
            cens = 'right' if self.attempts else 'none'
        elif lo is None:
            cens = 'left'
        else:
            cens = 'interval'
        return {'lo_s': lo, 'hi_s': hi, 'censoring': cens}

    def record(self) -> dict:
        out = {'source': self.source, 'finalize_error': self.finalize_error,
               'attempts': self.attempts, 'first_ok_after_s': self.first_ok_after_s,
               'first_ok_slot_s': self.first_ok_slot, 'availability': self.availability(),
               'result': self.result()}
        if self.fmt:
            out.update({'itag': self.fmt.get('itag'), 'mime': (self.fmt.get('mimeType') or '').split(';')[0],
                        'height': self.fmt.get('height'), 'width': self.fmt.get('width'),
                        'bitrate': self.fmt.get('bitrate'), 'content_length': self.fmt.get('contentLength'),
                        'otf': self.fmt.get('type') == 'FORMAT_STREAM_TYPE_OTF',
                        'live': bool(self.fmt.get('targetDurationSec'))})
        if self.url:
            out['url'] = url_summary(self.url)
        if self.steps:
            out['steps'] = [{'step': s['step'], 'kind': s['kind'], 'range': s['range'],
                             'url': url_summary(s['url'])} for s in self.steps]
        if self.chain:
            out['chain'] = self.chain
        if self.parse_error:
            out['parse_error'] = self.parse_error
        return out

    def result(self) -> str:
        if self.finalize_error:
            return {'n-unsolved': 'NSIG', 'sig-unsolved': 'SIG'}.get(self.finalize_error, 'NOURL')
        if self.first_ok_after_s is not None:
            return 'PLAY'
        if self.parse_error:
            return 'UNPARSABLE'
        if not self.attempts:
            return 'SKIPPED'
        st = self.attempts[-1].get('status')
        if st == 403:
            return '403'
        if st is None:
            return 'NETERR'
        return f'HTTP{st}'


class MediaProber:
    def __init__(self, net: Net, player, schedule: list[float], max_wait_s: float, log=None, only_targets=None,
                 target_order=None):
        self.only_targets = set(only_targets) if only_targets else None
        self.target_order = target_order
        self.net = net
        self.player = player
        self.schedule = schedule
        self.max_wait_s = max_wait_s
        self.log = log or (lambda *a: None)

    def build_targets(self, pr: dict, video_id: str, variant: dict) -> tuple[list[Target], dict]:
        sd = pr.get('streamingData') or {}
        adaptive = sd.get('adaptiveFormats') or []
        progressive = sd.get('formats') or []
        wanted = set((variant.get('media') or {}).get('targets') or ['adaptive', 'progressive', 'hls', 'dash'])
        if self.only_targets:
            wanted &= self.only_targets
        targets = []
        n, sig = collect_challenges(pr)
        solve = self.player.solve(video_id, n, sig) if (n or sig) else {'n_requested': 0, 'sig_requested': 0, 'ms': 0}

        def fmt_target(name, f):
            url, s, sp, src = _cipher_parts(f)
            if not url:
                return Target(name, f, None, 'no-url', src)
            final, err = self.player.finalize_url(url, s, sp)
            return Target(name, f, final, err, src)

        if 'adaptive' in wanted:
            v = _pick_video(adaptive)
            a = _pick_audio(adaptive)
            if v:
                targets.append(fmt_target('adaptive_video', v))
            if a:
                targets.append(fmt_target('adaptive_audio', a))
        if 'progressive' in wanted:
            p = _pick_progressive(progressive)
            if p:
                targets.append(fmt_target('progressive', p))
        for name, key in (('hls', 'hlsManifestUrl'), ('dash', 'dashManifestUrl')):
            if name in wanted and sd.get(key):
                final, err = self.player.finalize_manifest_url(sd[key])
                targets.append(Target(name, None, final, err, 'manifest'))
        return targets, solve

    # -- one request ------------------------------------------------------------------------
    def _headers(self, variant, variables, extra=None):
        media = variant.get('media') or {}
        h = strip_nulls(expand_placeholders(merge_patch({}, media.get('headers') or {}), variables))
        h.setdefault('Accept', '*/*')
        h['Accept-Encoding'] = 'identity'
        if extra:
            h.update(extra)
        return h

    def _get(self, url, variant, variables, *, rng=None, manifest=False, segment=False, purpose='',
             head_only=False):
        """rng = (offset, length) or None. Adaptive URLs honour media.range_mode (query|header);
        path-style segment URLs always get a Range header (a range= query answers HTTP 400)."""
        media = variant.get('media') or {}
        extra = {}
        req_url = url
        if rng is not None:
            off, ln = rng
            if (media.get('range_mode', 'query') == 'query' and not segment
                    and 'googlevideo.com/videoplayback?' in url):
                req_url = url + ('&' if '?' in url else '?') + f'range={off}-{off + ln - 1}'
            else:
                extra['Range'] = f'bytes={off}-{off + ln - 1}'
        res = self.net.request('GET', req_url, headers=self._headers(variant, variables, extra),
                               impersonate=media.get('impersonate') or 'chrome',
                               http_version=media.get('http_version'),
                               body_cap=MANIFEST_BODY_CAP if manifest else PROBE_BODY_CAP,
                               stream_max=PLAYLIST_HEAD if head_only else None, purpose=purpose)
        if res.status == 429:
            raise StopRun('http-429', f'HTTP 429 from googlevideo ({purpose}) - stopping the run')
        return res

    # -- probes ------------------------------------------------------------------------------
    def _probe_format(self, t: Target, variant, variables):
        f = t.fmt or {}
        if f.get('type') == 'FORMAT_STREAM_TYPE_OTF':
            return self._get(t.url + '&sq=0', variant, variables, segment=True, purpose=f'media:{t.name}'), 'media', None
        if f.get('targetDurationSec'):
            # live adaptive: the URL answers with the head segment; Range header keeps it to 4 KiB
            rng = (0, PROBE_BYTES)
            return self._get(t.url, variant, variables, rng=rng, segment=True, purpose=f'media:{t.name}'), 'media', rng
        rng = (0, PROBE_BYTES)
        return self._get(t.url, variant, variables, rng=rng, purpose=f'media:{t.name}'), 'media', rng

    def _probe_chain(self, t: Target, variant, variables):
        """Run the chain from the first unfinished step until a step fails or all are done.
        Returns (last NetResult, step name, requested range, finished)."""
        res, step, rng = None, None, None
        while t.pos < len(t.steps):
            st = t.steps[t.pos]
            step, rng = st['step'], st['range']
            res = self._get(st['url'], variant, variables, rng=rng, manifest=st['kind'] == 'manifest',
                            segment=st['kind'] != 'manifest', purpose=f'{t.name}:{step}',
                            head_only=t.name == 'hls' and step.endswith('_playlist'))
            self._note(t, st, res)
            if not (res.status in (200, 206) and (res.body or res.truncated)):
                return res, step, rng, False
            if st['kind'] == 'manifest':
                try:
                    added = (self._plan_hls if t.name == 'hls' else self._plan_dash)(t, st, res)
                except Exception as e:  # parser bug or unexpected manifest: never loop on it
                    added = False
                    t.chain[f'{step}_error'] = f'{type(e).__name__}: {str(e)[:120]}'
                if not added:
                    t.parse_error = f'{step}-unparsable'
                    return res, step, rng, False
            t.pos += 1
            if t.pos < len(t.steps):
                # the chain's own next request: record this step, continue after Net's spacing
                t.attempts.append(self._attempt_row(t, res, step, rng, ok=True, partial=True))
        return res, step, rng, True

    def _note(self, t, st, res):
        t.chain.setdefault(st['step'], {})
        t.chain[st['step']].update({'status': res.status, 'bytes': len(res.body),
                                    'redirects': res.redirects or 0})

    # -- manifest planning ------------------------------------------------------------------
    def _plan_hls(self, t: Target, st: dict, res) -> bool:
        base = res.final_url or st['url']
        text = res.text()
        if st['step'] == 'master':
            if '#EXT-X-STREAM-INF' not in text and '#EXTINF' in text:
                return self._plan_hls_media(t, 'video', text, base)  # the manifest is already a media playlist
            variants, media = _hls_parse_master(text, base)
            if not variants:
                return False
            pick = _hls_choose(variants)
            t.chain['variant'] = {k: pick.get(k) for k in ('resolution', 'bandwidth', 'codecs', 'audio')}
            t.steps.append({'step': 'video_playlist', 'url': pick['uri'], 'range': None, 'kind': 'manifest'})
            group = pick.get('audio')
            if group:
                renditions = [m for m in media if m.get('type') == 'AUDIO' and m.get('group-id') == group]
                with_uri = [m for m in renditions if m.get('uri')]
                chosen = next((m for m in with_uri if m.get('default') == 'YES'), with_uri[0] if with_uri else None)
                t.chain['audio_group'] = {'group': group, 'renditions': len(renditions),
                                          'with_uri': len(with_uri), 'chosen_lang': chosen and chosen.get('language')}
                if chosen:
                    t.steps.append({'step': 'audio_playlist', 'url': chosen['uri'], 'range': None, 'kind': 'manifest'})
            return True
        track = 'audio' if st['step'] == 'audio_playlist' else 'video'
        return self._plan_hls_media(t, track, text, base)

    def _plan_hls_media(self, t, track, text, base) -> bool:
        init, seg = _hls_parse_media(text, base)
        if not seg:
            return False
        insert = t.pos + 1
        new = []
        if init:
            new.append({'step': f'{track}_init', 'url': init['uri'], 'range': init['range'], 'kind': 'init'})
        rng = seg['range']
        new.append({'step': f'{track}_segment', 'url': seg['uri'],
                    'range': (rng[0], min(rng[1], PROBE_BYTES)) if rng else (0, PROBE_BYTES), 'kind': 'segment'})
        t.steps[insert:insert] = new
        return True

    def _plan_dash(self, t: Target, st: dict, res) -> bool:
        base = res.final_url or st['url']
        picks = _dash_plan(res.text(), base)
        if not picks:
            return False
        for track, p in picks:
            t.chain[f'{track}_rep'] = {k: p.get(k) for k in ('id', 'mime', 'height', 'bandwidth', 'addressing')}
            if p.get('init'):
                t.steps.append({'step': f'{track}_init', 'url': p['init'][0], 'range': p['init'][1], 'kind': 'init'})
            t.steps.append({'step': f'{track}_segment', 'url': p['segment'][0],
                            'range': p['segment'][1] or (0, PROBE_BYTES), 'kind': 'segment'})
        return True

    # -- the schedule --------------------------------------------------------------------------
    def _attempt_row(self, t, res, step, rng, ok, partial=False, slot=None):
        row = {'slot_s': slot, 'at_s': round(res.t_start - self._t0, 2), 'step': step, 'status': res.status,
               'bytes': len(res.body), 'ok': ok, 'ttfb_ms': _r(res.ttfb_ms), 'ms': _r(res.elapsed_ms),
               'ctype': (res.headers.get('content-type') or '')[:40] or None,
               'content_range': res.headers.get('content-range'),
               'content_length': res.headers.get('content-length'),
               'requested_range': list(rng) if rng else None,
               'sha': hashlib.sha256(res.body).hexdigest()[:12] if res.body else None,
               'truncated': res.truncated or None, 'error': res.error, 'http': res.http_version,
               'new_conn': res.new_connections}
        if partial:
            row['chain_step_ok'] = True
        if res.status and res.status >= 400 and res.body:
            row['body_snippet'] = ''.join(
                c for c in res.body[:160].decode('utf-8', 'replace') if c.isprintable())[:120]
        return row

    DEFAULT_ORDER = ['adaptive_video', 'adaptive_audio', 'progressive', 'hls', 'dash']

    def order_for(self, trial: int) -> list[str]:
        """Tie-break order within a slot. --target-order a,b,... names deliveries (adaptive expands to
        video+audio); 'rotate' shifts the default order by one delivery per trial (astra x-target-order),
        so first-slot advantage can be separated from delivery type without extra requests."""
        spec = self.target_order
        if not spec:
            return list(self.DEFAULT_ORDER)
        if spec == 'rotate':
            groups = [['adaptive_video', 'adaptive_audio'], ['progressive'], ['hls'], ['dash']]
            k = (trial - 1) % len(groups)
            return [x for g in groups[k:] + groups[:k] for x in g]
        out = []
        for name in spec.split(','):
            name = name.strip()
            out += ['adaptive_video', 'adaptive_audio'] if name == 'adaptive' else [name]
        return out + [x for x in self.DEFAULT_ORDER if x not in out]

    def run(self, targets: list[Target], t0: float, variant: dict, variables: dict, ad_wait_s: float | None,
            trial: int = 1) -> dict:
        """Per-target schedules; the most overdue target goes next (ties: adaptive video, audio,
        progressive, HLS, DASH); Net keeps >= --media-delay-s between googlevideo requests."""
        self._t0 = t0
        slots = list(self.schedule)
        if ad_wait_s and ad_wait_s > (slots[-1] if slots else 0) and ad_wait_s + 1 <= self.max_wait_s:
            slots.append(float(ad_wait_s) + 1.0)
        order = {name: i for i, name in enumerate(self.order_for(trial))}
        pending = [t for t in targets if t.url and not t.finalize_error]
        slot_idx = {t.name: 0 for t in pending}
        first_probe_at = None
        while pending:
            now = time.monotonic() - t0
            t = min(pending, key=lambda x: (slots[slot_idx[x.name]], order.get(x.name, 9)))
            slot = slots[slot_idx[t.name]]
            if slot > now:
                time.sleep(slot - now)
            if t.name in ('hls', 'dash'):
                res, step, rng, finished = self._probe_chain(t, variant, variables)
                ok = finished
            else:
                res, step, rng = self._probe_format(t, variant, variables)
                ok = res.status in (200, 206) and bool(res.body or res.truncated)
            if first_probe_at is None:
                first_probe_at = res.t_start - t0
            row = self._attempt_row(t, res, step, rng, ok=ok, slot=slot)
            t.attempts.append(row)
            if ok:
                t.first_ok_after_s = row['at_s']
                t.first_ok_slot = slot
                t.done = True
                pending.remove(t)
                continue
            if t.parse_error:
                pending.remove(t)
                continue
            now = time.monotonic() - t0
            i = slot_idx[t.name] + 1
            while i < len(slots) and slots[i] <= now:
                i += 1
            if i >= len(slots):
                pending.remove(t)
            else:
                slot_idx[t.name] = i
        # the first request of all (a manifest chain's first step comes before its final row)
        all_at = [a['at_s'] for t in targets for a in t.attempts]
        first_probe_at = min(all_at) if all_at else None
        return {'slots_s': slots, 'first_probe_after_s': None if first_probe_at is None else round(first_probe_at, 2),
                'target_order': sorted(order, key=order.get)}


def _r(v):
    return None if v is None else round(v, 1)


# ------------------------------------------------------------------ HLS parsing
_ATTR = re.compile(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)')


def _attrs(line: str) -> dict:
    body = line.split(':', 1)[1] if ':' in line else ''
    return {k.lower(): v.strip('"') for k, v in _ATTR.findall(body)}


def _hls_parse_master(text: str, base: str):
    lines = [l.strip() for l in text.splitlines()]
    variants, media = [], []
    for i, line in enumerate(lines):
        if line.startswith('#EXT-X-MEDIA:'):
            a = _attrs(line)
            if a.get('uri'):
                a['uri'] = urllib.parse.urljoin(base, a['uri'])
            media.append(a)
        elif line.startswith('#EXT-X-STREAM-INF'):
            a = _attrs(line)
            uri = next((l for l in lines[i + 1:] if l and not l.startswith('#')), None)
            if not uri:
                continue
            h = 0
            if a.get('resolution') and 'x' in a['resolution']:
                try:
                    h = int(a['resolution'].split('x')[1])
                except ValueError:
                    h = 0
            variants.append({'uri': urllib.parse.urljoin(base, uri), 'height': h,
                             'resolution': a.get('resolution'), 'bandwidth': a.get('bandwidth'),
                             'codecs': a.get('codecs'), 'audio': a.get('audio')})
    return variants, media


def _hls_choose(variants):
    ok = [v for v in variants if v['height'] and v['height'] <= 720]
    if ok:
        return max(ok, key=lambda v: (v['height'], int(v.get('bandwidth') or 0)))
    return min(variants, key=lambda v: (v['height'] or 10 ** 6, int(v.get('bandwidth') or 0)))


def _byterange(value: str, prev_end: int | None):
    """'len[@off]' -> (off, len); an absent offset continues after the previous sub-range."""
    ln, _, off = value.partition('@')
    ln = int(ln)
    off = int(off) if off else (prev_end or 0)
    return off, ln


def _hls_parse_media(text: str, base: str):
    lines = [l.strip() for l in text.splitlines()]
    init = None
    pending_range = None
    prev_end = None
    for i, line in enumerate(lines):
        if line.startswith('#EXT-X-MAP:'):
            a = _attrs(line)
            if a.get('uri'):
                rng = _byterange(a['byterange'], 0) if a.get('byterange') else None
                init = {'uri': urllib.parse.urljoin(base, a['uri']), 'range': rng}
        elif line.startswith('#EXT-X-BYTERANGE:'):
            pending_range = _byterange(line.split(':', 1)[1], prev_end)
        elif line and not line.startswith('#'):
            # first media segment URI
            return init, {'uri': urllib.parse.urljoin(base, line), 'range': pending_range}
    return init, None


# ------------------------------------------------------------------ DASH parsing
def _dash_plan(text: str, base: str):
    """[(track, {id, mime, height, bandwidth, addressing, init:(url, range)|None, segment:(url, range)})]
    for the best <=720p video representation and the best audio representation."""
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return []
    ns = root.tag.split('}')[0] + '}' if root.tag.startswith('{') else ''

    def join(b, el):
        e = el.find(f'{ns}BaseURL')
        return urllib.parse.urljoin(b, e.text.strip()) if e is not None and e.text else b

    mpd_base = join(base, root)
    cands = {'video': [], 'audio': []}
    for period in root.findall(f'{ns}Period'):
        p_base = join(mpd_base, period)
        for aset in period.findall(f'{ns}AdaptationSet'):
            a_base = join(p_base, aset)
            for rep in aset.findall(f'{ns}Representation'):
                mime = rep.get('mimeType') or aset.get('mimeType') or ''
                ctype = aset.get('contentType') or mime.split('/')[0]
                track = 'video' if ctype.startswith('video') else 'audio' if ctype.startswith('audio') else None
                if not track:
                    continue
                cands[track].append((rep, aset, join(a_base, rep), mime))
    out = []
    for track, items in cands.items():
        if not items:
            continue
        if track == 'video':
            ok = [x for x in items if 0 < int(x[0].get('height') or 0) <= 720]
            pick = max(ok or items, key=lambda x: (int(x[0].get('height') or 0), int(x[0].get('bandwidth') or 0)))
        else:
            pick = max(items, key=lambda x: int(x[0].get('bandwidth') or 0))
        rep, aset, rbase, mime = pick
        addr = _dash_addresses(ns, rep, aset, rbase)
        if not addr:
            continue
        addr.update({'id': rep.get('id'), 'mime': mime, 'height': rep.get('height'), 'bandwidth': rep.get('bandwidth')})
        out.append((track, addr))
    return out


def _dash_addresses(ns, rep, aset, rbase):
    def find(tag):
        e = rep.find(f'{ns}{tag}')
        return e if e is not None else aset.find(f'{ns}{tag}')

    seglist = find('SegmentList')
    if seglist is not None:
        init = seglist.find(f'{ns}Initialization')
        seg = seglist.find(f'{ns}SegmentURL')
        if seg is None:
            return None
        init_t = None
        if init is not None and init.get('sourceURL'):
            init_t = (urllib.parse.urljoin(rbase, init.get('sourceURL')), _range_attr(init.get('range')))
        media_url = urllib.parse.urljoin(rbase, seg.get('media')) if seg.get('media') else rbase
        return {'addressing': 'SegmentList', 'init': init_t,
                'segment': (media_url, _range_attr(seg.get('mediaRange')))}
    tmpl = find('SegmentTemplate')
    if tmpl is not None and tmpl.get('media'):
        rid = rep.get('id') or ''
        bw = rep.get('bandwidth') or ''
        number = int(tmpl.get('startNumber') or 1)
        time_v = 0
        tl = tmpl.find(f'{ns}SegmentTimeline')
        addressing = 'SegmentTemplate$Number$'
        if tl is not None:
            s = tl.find(f'{ns}S')
            if s is not None:
                time_v = int(s.get('t') or 0)
            addressing = 'SegmentTemplate+SegmentTimeline'

        def fill(pattern):
            out = pattern.replace('$RepresentationID$', rid).replace('$Bandwidth$', bw)
            out = re.sub(r'\$Number(%0(\d+)d)?\$', lambda m: str(number).zfill(int(m.group(2) or 0)), out)
            out = re.sub(r'\$Time(%0(\d+)d)?\$', lambda m: str(time_v).zfill(int(m.group(2) or 0)), out)
            return out.replace('$$', '$')
        if '$Time' in tmpl.get('media'):
            addressing = 'SegmentTemplate$Time$'
        init_t = (urllib.parse.urljoin(rbase, fill(tmpl.get('initialization'))), None) if tmpl.get('initialization') else None
        return {'addressing': addressing, 'init': init_t, 'segment': (urllib.parse.urljoin(rbase, fill(tmpl.get('media'))), None)}
    sbase = find('SegmentBase')
    if sbase is not None:
        init = sbase.find(f'{ns}Initialization')
        init_t = (rbase, _range_attr(init.get('range'))) if init is not None and init.get('range') else None
        return {'addressing': 'SegmentBase', 'init': init_t, 'segment': (rbase, None)}
    return {'addressing': 'BaseURL-only', 'init': None, 'segment': (rbase, None)}


def _range_attr(value):
    if not value or '-' not in value:
        return None
    a, b = value.split('-', 1)
    try:
        a, b = int(a), int(b)
    except ValueError:
        return None
    return (a, min(b - a + 1, PROBE_BYTES))
