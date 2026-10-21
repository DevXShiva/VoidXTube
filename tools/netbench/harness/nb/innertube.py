"""Build a /player request from a resolved variant + identity; parse and analyse the answer."""

from __future__ import annotations

import copy
import json
import re
import urllib.parse

from .net import looks_like_bot_wall
from .util import expand_placeholders, merge_patch, rand_str, set_path, get_path, strip_nulls
from .variants import DEFAULT_HEADERS

FALLBACK_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
               'Chrome/147.0.0.0 Safari/537.36')
_INITIAL_PR = re.compile(r'ytInitialPlayerResponse\s*=\s*\{')


def resolve_sts(mode, player_sts):
    if mode is None or mode == 'none':
        return None
    if isinstance(mode, int):
        return mode
    if player_sts is None:
        return None
    if mode == 'player':
        return int(player_sts)
    if mode == 'player+001':
        s = str(player_sts)
        return int(s + '001') if len(s) == 5 else int(s)
    raise ValueError(f'bad sts mode {mode!r}')


def build_request(variant: dict, video_id: str, ident, player_sts) -> dict:
    """Returns {method, url, headers, body(bytes|None), body_obj, vars, notes}."""
    idc = variant['identity']
    ep = variant['endpoint']
    notes = []
    if idc.get('context') and ident is not None and ident.context:
        context = copy.deepcopy(ident.context)
        context_source = f'page:{idc["context"]}'
    else:
        if idc.get('context'):
            notes.append(f'context page {idc["context"]} had no INNERTUBE_CONTEXT; used `client`')
        context = {'client': merge_patch({}, variant.get('client') or {})}
        context_source = 'variant'
    context['client'] = merge_patch(context.get('client') or {}, variant.get('client_overrides') or {})

    visitor = ident.visitor if ident is not None else None
    if idc.get('visitor') and not visitor:
        notes.append(f'visitor page {idc["visitor"]} gave no visitorData')
    if visitor and idc.get('visitor_in_body', True):
        context['client']['visitorData'] = visitor

    body = {'context': context, 'videoId': '${video_id}'}
    sts = resolve_sts(variant.get('sts', 'player'), player_sts)
    if sts is not None:
        set_path(body, 'playbackContext.contentPlaybackContext.signatureTimestamp', '${sts}')
    flags = ident.flags if ident is not None else None
    if idc.get('flags'):
        if flags:
            set_path(body, 'playbackContext.contentPlaybackContext.encryptedHostFlags', '${flags}')
        else:
            notes.append(f'flags page {idc["flags"]} had no encryptedHostFlags')
    body = merge_patch(body, variant.get('body') or {})

    ua = None
    if idc.get('user_agent_from_context'):
        ua = context['client'].get('userAgent')
    ua = ua or variant.get('user_agent') or context['client'].get('userAgent') or FALLBACK_UA
    client_name_id = variant.get('client_name_id')
    if idc.get('context') and ident is not None and ident.client_name_id:
        client_name_id = ident.client_name_id
    variables = {
        'video_id': video_id,
        'sts': sts,
        'visitor_data': visitor,
        'visitor_header': visitor if idc.get('visitor_header', True) else None,
        'flags': flags,
        'cpn': rand_str(16),
        't': rand_str(12),
        'user_agent': ua,
        'client_name': context['client'].get('clientName'),
        'client_version': context['client'].get('clientVersion'),
        'client_name_id': None if client_name_id is None else str(client_name_id),
        'embed_url': 'https://www.reddit.com/',
    }
    body = strip_nulls(expand_placeholders(body, variables))
    headers = strip_nulls(expand_placeholders(merge_patch(DEFAULT_HEADERS, variant.get('headers') or {}), variables))
    if idc.get('cookies'):
        jar = dict(idc.get('page_cookies') or {})
        if ident is not None:
            jar.update(ident.cookies)
        if jar:
            headers['Cookie'] = '; '.join(f'{k}={v}' for k, v in jar.items())

    if ep.get('kind', 'innertube') == 'watch_page':
        url = f'https://{ep.get("host") or "www.youtube.com"}/watch?v={video_id}&bpctr=9999999999&has_verified=1'
        for k in list(headers):
            if k.lower() in ('content-type', 'x-youtube-client-name', 'x-youtube-client-version', 'origin',
                             'x-goog-visitor-id'):
                headers.pop(k)
        headers.setdefault('Accept', 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8')
        return {'method': 'GET', 'url': url, 'headers': headers, 'body': None, 'body_obj': None,
                'vars': variables, 'notes': notes, 'context_source': context_source}

    query = expand_placeholders(merge_patch({}, ep.get('query') or {}), variables)
    qs = urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
    url = f'{ep.get("scheme") or "https"}://{ep.get("host") or "www.youtube.com"}{ep.get("path") or "/youtubei/v1/player"}'
    if qs:
        url += '?' + qs
    raw = json.dumps(body, separators=(',', ':'), ensure_ascii=False).encode()
    return {'method': 'POST', 'url': url, 'headers': headers, 'body': raw, 'body_obj': body,
            'vars': variables, 'notes': notes, 'context_source': context_source}


def parse_player_response(variant: dict, res) -> tuple[dict | None, str | None]:
    ep = variant['endpoint']
    text = res.text() if res.body else ''
    if ep.get('kind', 'innertube') == 'watch_page':
        m = _INITIAL_PR.search(text)
        if not m:
            return None, 'no-ytInitialPlayerResponse'
        try:
            obj, _ = json.JSONDecoder().raw_decode(text, m.end() - 1)
            return obj, None
        except ValueError as e:
            return None, f'json: {e}'
    try:
        obj = json.loads(text)
    except ValueError as e:
        return None, f'json: {str(e)[:80]}'
    if ep.get('response_path'):
        obj = get_path(obj, ep['response_path'])
        if obj is None:
            return None, f'missing {ep["response_path"]}'
    return obj, None


def _runs_text(node) -> str | None:
    if not isinstance(node, dict):
        return node if isinstance(node, str) else None
    if 'simpleText' in node:
        return node['simpleText']
    if 'runs' in node:
        return ''.join(r.get('text', '') for r in node['runs'] if isinstance(r, dict))
    return None


def reason_code(status: str | None, reason: str | None, subreason: str | None) -> str | None:
    text = ' '.join(x for x in (reason, subreason) if x)
    if not text:
        return None
    m = re.search(r'Error code:\s*(\d+)\s*-\s*(\d+)', text)
    if m:
        return f'{m.group(1)}-{m.group(2)}'
    if looks_like_bot_wall(text):
        return 'bot'
    low = text.lower()
    table = [
        ('page needs to be reloaded', 'reload'),
        ('not available', 'not-available'),
        ('unavailable', 'unavailable'),
        ('confirm your age', 'age'),
        ('inappropriate for some users', 'age'),
        ('private video', 'private'),
        ('latest version of youtube', 'latest-version'),
        ('members', 'members-only'),
        ('premiere', 'premiere'),
        ('live event will begin', 'upcoming'),
        ('playback on other websites', 'embed-disabled'),
        ('copyright', 'copyright'),
        ('country', 'geo'),
        ('sign in', 'sign-in'),
    ]
    for needle, code in table:
        if needle in low:
            return code
    return re.sub(r'[^a-z0-9]+', '-', low)[:24].strip('-')


def analyse(pr: dict) -> dict:
    ps = pr.get('playabilityStatus') or {}
    status = ps.get('status')
    reason = ps.get('reason')
    esr = ((ps.get('errorScreen') or {}).get('playerErrorMessageRenderer') or {})
    subreason = _runs_text(esr.get('subreason'))
    screen_reason = _runs_text(esr.get('reason'))
    messages = ps.get('messages') or []
    all_text = ' '.join(x for x in [reason, subreason, screen_reason, *[m for m in messages if isinstance(m, str)]] if x)
    sd = pr.get('streamingData') or {}
    adaptive = sd.get('adaptiveFormats') or []
    progressive = sd.get('formats') or []

    def kind(f):
        if f.get('url'):
            return 'url'
        if f.get('signatureCipher') or f.get('cipher'):
            return 'cipher'
        return 'none'

    ak = [kind(f) for f in adaptive]
    pk = [kind(f) for f in progressive]
    vd = pr.get('videoDetails') or {}
    bot_wall = looks_like_bot_wall(all_text) and status in ('LOGIN_REQUIRED', 'ERROR', 'UNPLAYABLE', None)
    return {
        'playability': {
            'status': status, 'reason': (reason or '')[:200] or None, 'subreason': (subreason or '')[:200] or None,
            'error_screen': (screen_reason or '')[:200] or None, 'reason_code': reason_code(status, reason or screen_reason, subreason),
            'playable_in_embed': ps.get('playableInEmbed'), 'bot_wall': bot_wall,
            'live_streamability': 'liveStreamability' in ps,
        },
        'video': {
            'video_id': vd.get('videoId'), 'is_live': vd.get('isLive'), 'is_live_content': vd.get('isLiveContent'),
            'is_upcoming': vd.get('isUpcoming'), 'length_s': _int(vd.get('lengthSeconds')),
            'is_private': vd.get('isPrivate'),
        },
        'formats': {
            'adaptive_total': len(adaptive), 'adaptive_url': ak.count('url'), 'adaptive_cipher': ak.count('cipher'),
            'adaptive_none': ak.count('none'),
            'progressive_total': len(progressive), 'progressive_url': pk.count('url'),
            'progressive_cipher': pk.count('cipher'), 'progressive_none': pk.count('none'),
            'hls': bool(sd.get('hlsManifestUrl')), 'dash': bool(sd.get('dashManifestUrl')),
            'sabr': bool(sd.get('serverAbrStreamingUrl')),
            'drm': any(f.get('drmFamilies') for f in adaptive + progressive),
            'expires_in_s': _int(sd.get('expiresInSeconds')),
            'itags_adaptive': sorted({f.get('itag') for f in adaptive if f.get('itag')}),
        },
        'ads': ad_data(pr),
        'attestation': 'attestation' in pr,
        'response_keys': sorted(pr.keys())[:40],
    }


def _int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def po_token_hints(analysis: dict, variant: dict, pr: dict) -> list[str]:
    """We never send a PO token; list what suggests one would be needed."""
    hints = []
    if analysis.get('attestation'):
        hints.append('response-carries-attestation(botguard)')
    f = analysis['formats']
    if f['sabr'] and f['adaptive_url'] + f['adaptive_cipher'] == 0 and f['adaptive_total']:
        hints.append('sabr-only(ump needs pot past ~60s)')
    name = ((variant.get('client') or {}).get('clientName') or '').upper()
    if name in ('WEB', 'MWEB', 'WEB_REMIX', 'WEB_CREATOR', 'TVHTML5_SIMPLY'):
        hints.append('ytdlp-policy:gvs-pot-required(https/dash)')
    if name in ('ANDROID', 'ANDROID_VR', 'IOS'):
        hints.append('ytdlp-policy:gvs-pot-required-unless-player-pot')
    ps = (pr.get('playabilityStatus') or {})
    if 'PO' in json.dumps(ps.get('messages') or [])[:400]:
        hints.append('playability-message-mentions-po')
    return hints


def ad_data(pr: dict) -> dict:
    """Pre-roll ad data AS DATA (the harness measures the enforced wait; it never alters it).

    Every instreamVideoAdRenderer found under adPlacements / adSlots is listed with its JSON path,
    the placement kind or slot trigger of its top-level entry, its duration and skip offset, and
    the identifiers that let duplicates across the two schemas be told apart. Wait estimates:
      preroll_full_s   sum of full durations of pre-content ads (all renderers)
      preroll_skip_s   yt-dlp's rule: skip offset when present, else full duration
      preroll_dedup_s  yt-dlp's rule after dropping renderers whose ad video id repeats
    ad_wait_s (added by the runner) is yt-dlp's own available_at - now, computed by the checkout."""
    found = []

    def walk(node, path, top_kind, top_path):
        if isinstance(node, dict):
            for k, v in node.items():
                p = f'{path}.{k}'
                if k == 'instreamVideoAdRenderer' and isinstance(v, dict):
                    found.append((p, top_kind, top_path, v))
                else:
                    walk(v, p, top_kind, top_path)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, f'{path}[{i}]', top_kind, top_path)

    for i, ap in enumerate(pr.get('adPlacements') or []):
        kind = get_path(ap, 'adPlacementRenderer.config.adPlacementConfig.kind')
        walk(ap, f'adPlacements[{i}]', kind, f'adPlacements[{i}]')
    for i, sl in enumerate(pr.get('adSlots') or []):
        trig = get_path(sl, 'adSlotRenderer.adSlotMetadata.triggerEvent')
        walk(sl, f'adSlots[{i}]', trig, f'adSlots[{i}]')

    renderers = []
    for path, kind, top, r in found:
        pv = urllib.parse.parse_qs(r.get('playerVars') or '')
        dur = _int((pv.get('length_seconds') or [None])[-1])
        skip = r.get('skipOffsetMilliseconds')
        renderers.append({
            'path': path, 'kind': kind,
            'pre_content': kind in ('AD_PLACEMENT_KIND_START', 'SLOT_TRIGGER_EVENT_BEFORE_CONTENT'),
            'duration_s': dur, 'skip_s': None if skip is None else round(skip / 1000, 3),
            'ad_video_id': (pv.get('video_id') or [None])[-1],
            'layout_id': r.get('layoutId'),
            'slot_id': get_path(pr, top.replace('[', '.').replace(']', '') + '.adSlotRenderer.adSlotMetadata.slotId')
                       if top.startswith('adSlots') else None,
        })
    pre = [r for r in renderers if r['pre_content']]

    def rule(r):
        return r['skip_s'] if r['skip_s'] is not None else (r['duration_s'] or 0)

    seen, dedup = set(), []
    for r in pre:
        key = r['ad_video_id'] or r['path']
        if key in seen:
            continue
        seen.add(key)
        dedup.append(r)
    return {
        'ad_placements': len(pr.get('adPlacements') or []),
        'ad_slots': len(pr.get('adSlots') or []),
        'player_ads': len(pr.get('playerAds') or []),
        'renderers': renderers[:20],
        'preroll_count': len(pre),
        'preroll_full_s': sum(r['duration_s'] or 0 for r in pre),
        'preroll_skip_s': round(sum(rule(r) for r in pre), 3),
        'preroll_dedup_s': round(sum(rule(r) for r in dedup), 3),
        'duplicate_ad_ids': len(pre) - len(dedup),
    }
