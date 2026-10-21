"""Identity recipes: which anonymous visitor (and embed flags / page context) a request presents.

A variant lists page kinds to fetch (identity.pages); they are fetched in order with one
in-memory cookie jar per identity session, and the variant picks which page supplies the
visitor, the encryptedHostFlags and (yt-dlp style) the whole INNERTUBE_CONTEXT.

Identities are cached per (session, trial, scope): scope=run reuses one identity for every
video of a trial (cheapest; what an app with a persistent visitor does), scope=video fetches per
video, scope=attempt per request. --trials N always gives each trial a fresh identity.
"""

from __future__ import annotations

import dataclasses
import json
import re
import time
import urllib.parse

from .net import Net, looks_like_bot_wall, StopRun
from .util import fingerprint

PAGE_URLS = {
    'embed': 'https://www.youtube.com/embed/{video_id}?html5=1',
    'watch': 'https://www.youtube.com/watch?v={video_id}&bpctr=9999999999&has_verified=1',
    'home': 'https://www.youtube.com/',
    'mweb': 'https://m.youtube.com/',
    'tv': 'https://www.youtube.com/tv',
    'music': 'https://music.youtube.com/',
    'sw_js_data': 'https://www.youtube.com/sw.js_data',
}
DEFAULT_REFERERS = {'embed': 'https://www.reddit.com/'}
FALLBACK_PAGE_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
                    'Chrome/147.0.0.0 Safari/537.36')

_YTCFG_SET = re.compile(r'ytcfg\.set\s*\(\s*\{')
_VISITOR_RE = re.compile(r'"visitorData"\s*:\s*"([^"]+)"')
_PLAYER_RE = re.compile(r'"(?:PLAYER_JS_URL|jsUrl|player_url)"\s*:\s*"([^"]+base\.js|[^"]+\.js)"')


def parse_ytcfg(html: str) -> dict:
    """Merge every ytcfg.set({...}) object on the page (pages call it several times)."""
    cfg = {}
    dec = json.JSONDecoder()
    for m in _YTCFG_SET.finditer(html):
        try:
            obj, _ = dec.raw_decode(html, m.end() - 1)
        except ValueError:
            continue
        if isinstance(obj, dict):
            cfg.update(obj)
    return cfg


def _parse_set_cookie(header: str):
    first = header.split(';', 1)[0]
    if '=' not in first:
        return None, None, False
    name, value = first.split('=', 1)
    attrs = header.lower()
    expired = 'max-age=0' in attrs or 'expires=thu, 01 jan 1970' in attrs or 'expires=thu, 01-jan-1970' in attrs
    return name.strip(), value.strip(), expired


@dataclasses.dataclass
class Identity:
    key: tuple
    visitor: str | None = None
    flags: str | None = None
    context: dict | None = None
    client_version: str | None = None
    client_name_id: str | None = None
    cookies: dict = dataclasses.field(default_factory=dict)
    pages: dict = dataclasses.field(default_factory=dict)     # kind -> parsed page info (in memory)
    records: list = dataclasses.field(default_factory=list)   # what goes into results
    fetched_at: float = 0.0
    errors: list = dataclasses.field(default_factory=list)


class IdentityStore:
    def __init__(self, net: Net, log=None):
        self.net = net
        self.log = log or (lambda *a: None)
        self._sessions: dict[tuple, Identity] = {}
        self._attempt_counter = 0
        self.player_js_urls: list[str] = []

    def scope_key(self, variant: dict, trial: int, video_id: str) -> tuple:
        ident = variant['identity']
        session = ident.get('session') or variant['id']
        scope = ident.get('scope') or 'run'
        if scope == 'run':
            return (session, trial)
        if scope == 'video':
            return (session, trial, video_id)
        self._attempt_counter += 1
        return (session, trial, video_id, self._attempt_counter)

    def get(self, variant: dict, trial: int, video_id: str, user_agent: str | None,
            transport: dict | None = None) -> tuple[Identity, list]:
        """Returns the identity and the page records fetched FOR THIS CALL (cached pages excluded)."""
        ident_cfg = variant['identity']
        pages = ident_cfg.get('pages') or []
        key = self.scope_key(variant, trial, video_id)
        ident = self._sessions.get(key)
        if ident is None:
            ident = Identity(key=key, fetched_at=time.time())
            self._sessions[key] = ident
        fresh = []
        referers = dict(DEFAULT_REFERERS)
        referers.update(ident_cfg.get('page_referer') or {})
        per_kind = ident_cfg.get('pages_config') or {}
        use_jar = bool(ident_cfg.get('page_cookie_jar'))
        mine = {}
        for kind in pages:
            pc = per_kind.get(kind) or {}
            page_ua = pc.get('ua') or ident_cfg.get('page_ua') or user_agent or FALLBACK_PAGE_UA
            page_cookies = dict(pc['cookies'] if 'cookies' in pc else (ident_cfg.get('page_cookies') or {}))
            referer = pc['referer'] if 'referer' in pc else referers.get(kind)
            # One fetch per (kind, UA, cookies, referer) within the session scope; variants
            # sharing a session with the same page settings reuse it.
            pkey = (kind, page_ua, json.dumps(page_cookies, sort_keys=True), referer, use_jar,
                    (transport or {}).get('impersonate'), json.dumps(pc.get('headers') or {}, sort_keys=True))
            mine[kind] = pkey
            if pkey in ident.pages:
                continue
            info = self._fetch_page(kind, video_id, page_ua, page_cookies, referer, ident, transport or {}, use_jar,
                                    pc.get('headers') or {})
            ident.pages[pkey] = info
            fresh.append(info['record'])
            ident.records.append(info['record'])

        def page_for(kind):
            if not kind:
                return None
            return ident.pages.get(mine.get(kind))

        vis_page = page_for(ident_cfg.get('visitor'))
        flag_page = page_for(ident_cfg.get('flags'))
        ctx_page = page_for(ident_cfg.get('context'))
        ident.visitor = vis_page.get('visitor') if vis_page else None
        ident.flags = flag_page.get('flags') if flag_page else None
        ident.context = ctx_page.get('context') if ctx_page else None
        ident.client_version = ctx_page.get('client_version') if ctx_page else None
        ident.client_name_id = ctx_page.get('client_name_id') if ctx_page else None
        return ident, fresh

    def _fetch_page(self, kind: str, video_id: str, ua: str, cookies: dict, referer: str | None,
                    ident: Identity, transport: dict, use_jar: bool, extra_headers: dict) -> dict:
        url = PAGE_URLS[kind].format(video_id=video_id)
        headers = {
            'User-Agent': ua,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Accept-Encoding': 'gzip, deflate, br',
        }
        if referer:
            headers['Referer'] = referer
        for k, v in extra_headers.items():  # null deletes a default page header
            for existing in [h for h in headers if h.lower() == k.lower()]:
                headers.pop(existing)
            if v is not None:
                headers[k] = v
        jar = dict(cookies)
        if use_jar:
            jar.update(ident.cookies)  # cookies set by earlier pages of this identity session (yt-dlp's jar)
        if jar:
            headers['Cookie'] = '; '.join(f'{k}={v}' for k, v in jar.items())
        res = self.net.request('GET', url, headers=headers, impersonate=transport.get('impersonate') or 'chrome',
                               http_version=transport.get('http_version'), ja3=transport.get('ja3'),
                               akamai=transport.get('akamai'), purpose=f'identity:{kind}')
        for sc in res.set_cookies:
            name, value, expired = _parse_set_cookie(sc)
            if not name:
                continue
            if expired or not value:
                ident.cookies.pop(name, None)
            else:
                ident.cookies[name] = value
        text = res.text() if res.body else ''
        info = {'kind': kind}
        record = {'kind': kind, 'video_id': video_id if '{video_id}' in PAGE_URLS[kind] else None,
                  **res.summary()}
        if res.error or res.status != 200:
            ident.errors.append(f'{kind}: {res.error or res.status}')
        # Bot wall on a PAGE: only a real interstitial counts. App pages (the /tv Cobalt page, the watch
        # page) ship their UI strings - including "Sign in to confirm you're not a bot" - in their JS,
        # so raw text is not evidence (a 191 KB /tv page with a full ytcfg tripped that on 2026-09-28).
        final = urllib.parse.urlparse(res.final_url or url)
        if (final.hostname or '').endswith('google.com') and final.path.startswith('/sorry'):
            record['bot_wall'] = 'sorry-interstitial'
        elif (final.hostname or '').startswith('consent.'):
            record['consent_redirect'] = True
        if kind == 'watch':
            m = re.search(r'ytInitialPlayerResponse\s*=\s*\{', text)
            if m:
                try:
                    ipr, _ = json.JSONDecoder().raw_decode(text, m.end() - 1)
                    ps = ipr.get('playabilityStatus') or {}
                    if looks_like_bot_wall(json.dumps(ps)[:4000]):
                        record['bot_wall'] = 'watch-page-playability'
                except ValueError:
                    pass
        elif looks_like_bot_wall(text) and not _YTCFG_SET.search(text):
            record['bot_wall'] = 'challenge-page-without-ytcfg'
        if kind == 'sw_js_data':
            try:
                data = json.loads(text[text.index('['):])
                info['visitor'] = data[0][2][0][0][13]
            except Exception as e:
                ident.errors.append(f'sw_js_data parse: {e}')
        else:
            cfg = parse_ytcfg(text)
            info['ytcfg_keys'] = len(cfg)
            visitor = cfg.get('VISITOR_DATA') or (((cfg.get('INNERTUBE_CONTEXT') or {}).get('client') or {}).get('visitorData'))
            if not visitor:
                m = _VISITOR_RE.search(text)
                visitor = m.group(1) if m else None
            if visitor and '\\u' in visitor:
                visitor = json.loads(f'"{visitor}"')  # regex fallback on raw HTML: decode JSON escapes only
            info['visitor'] = visitor
            info['flags'] = (((cfg.get('WEB_PLAYER_CONTEXT_CONFIGS') or {})
                              .get('WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER') or {}).get('encryptedHostFlags'))
            if isinstance(cfg.get('INNERTUBE_CONTEXT'), dict):
                info['context'] = cfg['INNERTUBE_CONTEXT']
            info['client_version'] = cfg.get('INNERTUBE_CLIENT_VERSION')
            info['client_name_id'] = cfg.get('INNERTUBE_CONTEXT_CLIENT_NAME')
            info['sts'] = cfg.get('STS')
            pj = cfg.get('PLAYER_JS_URL')
            if not pj:
                m = _PLAYER_RE.search(text)
                pj = m.group(1).replace('\\/', '/') if m else None
            info['player_js_url'] = pj
            if pj:
                self.player_js_urls.append(pj)
            record.update({
                'ytcfg_keys': len(cfg), 'has_context': 'context' in info,
                'client_version': info['client_version'], 'sts': info['sts'],
                'player_js': pj,
            })
        record['visitor_fp'] = fingerprint(info.get('visitor'))
        record['flags_fp'] = fingerprint(info.get('flags'))
        record['cookies_set'] = sorted(n for n in (_parse_set_cookie(c)[0] for c in res.set_cookies) if n)
        record['cookies_sent'] = sorted(jar)   # names only; the explicit page-fetch cookie policy
        info['cookies_sent'] = record['cookies_sent']
        info['record'] = record
        self.log(f'    identity page {kind}: visitor={record["visitor_fp"]} flags={record["flags_fp"]} '
                 f'cookies={record["cookies_set"]}')
        return info
