"""Every byte the harness sends goes through Net: one place for the proxy, the rate limits, the
request caps, byte accounting and the bot-wall stop.

Host classes:
  youtube  - www/m/music.youtube.com, youtubei.googleapis.com (pages, /player, player JS).
             Spaced >= --delay-s apart (default 3 s) and capped by --max-requests.
  media    - *.googlevideo.com (media, HLS/DASH manifests). Spaced >= --media-delay-s (1 s),
             capped by --max-media-requests.
  other    - anything else (GitHub for yt-dlp's solver script, connectivity check). Uncapped
             but counted.

Cookies: curl_cffi's session jar is disabled (discard_cookies) - a request carries exactly the
Cookie header its caller built, which only ever holds cookies YouTube set on this run's own
anonymous page fetches. Nothing is ever read from disk or a browser.
"""

from __future__ import annotations

import dataclasses
import re
import threading
import time
import urllib.parse

from curl_cffi import CurlInfo, CurlOpt
from curl_cffi import requests as cffi

# Hard cap on any googlevideo response body (curl aborts with error 63 beyond it). Range probes
# ask for 4 KiB; the cap only matters if a server ignores the range.
MEDIA_BODY_CAP = 2_000_000

# Rough TLS 1.3 handshake cost per NEW connection to a Google front end (certificate chain
# dominates). Reported separately as an estimate - curl cannot see TLS bytes.
TLS_HANDSHAKE_EST_DOWN = 6500
TLS_HANDSHAKE_EST_UP = 700

YOUTUBE_HOSTS = re.compile(r'(^|\.)(youtube\.com|youtubei\.googleapis\.com|youtube-nocookie\.com)$')
MEDIA_HOSTS = re.compile(r'(^|\.)googlevideo\.com$')

BOT_WALL_PATTERNS = [
    re.compile(r"confirm (that )?you.{0,3}re not a bot", re.I),
    # "you're not a bot" / "you’re…", never "your age" (an age gate is not a bot wall; the
    # 2026-09-28 sweep2b run stopped on WaOKSUlf4TM's "Sign in to confirm your age").
    re.compile(r'sign in to confirm you(?!r\b)', re.I),
]


class StopRun(Exception):
    """Abort the whole run now (bot wall, 429, request cap). Carries a machine-readable code."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def host_class(url: str) -> str:
    host = urllib.parse.urlparse(url).hostname or ''
    if MEDIA_HOSTS.search(host):
        return 'media'
    if YOUTUBE_HOSTS.search(host):
        return 'youtube'
    return 'other'


def looks_like_bot_wall(text: str | None) -> bool:
    if not text:
        return False
    return any(p.search(text) for p in BOT_WALL_PATTERNS)


@dataclasses.dataclass
class NetResult:
    url: str
    method: str
    status: int | None = None
    headers: dict = dataclasses.field(default_factory=dict)
    set_cookies: list = dataclasses.field(default_factory=list)
    body: bytes = b''
    truncated: bool = False
    error: str | None = None
    elapsed_ms: float | None = None
    ttfb_ms: float | None = None
    up_bytes: int = 0
    down_bytes: int = 0
    new_connections: int = 0
    http_version: str | None = None
    host_class: str = 'other'
    t_start: float = 0.0          # time.monotonic() when the request was actually sent
    final_url: str | None = None  # after redirects (relative manifest URIs resolve against this)
    redirects: int = 0

    def text(self) -> str:
        return self.body.decode('utf-8', 'replace')

    def summary(self) -> dict:
        return {
            'status': self.status, 'error': self.error, 'ms': _r(self.elapsed_ms), 'ttfb_ms': _r(self.ttfb_ms),
            'up': self.up_bytes, 'down': self.down_bytes, 'body_bytes': len(self.body),
            'truncated': self.truncated, 'new_conn': self.new_connections, 'http': self.http_version,
        }


def _r(v):
    return None if v is None else round(v, 1)


_HTTP_VERSIONS = {0: None, 1: '1.0', 2: '1.1', 3: '2', 4: '3', 30: '3'}


class Ledger:
    """Byte and request totals, per host class. Attempts snapshot it before/after to get deltas."""

    def __init__(self):
        self.lock = threading.Lock()
        self.totals = {c: {'requests': 0, 'up': 0, 'down': 0, 'new_conn': 0} for c in ('youtube', 'media', 'other')}

    def add(self, res: NetResult):
        with self.lock:
            t = self.totals[res.host_class]
            t['requests'] += 1
            t['up'] += res.up_bytes
            t['down'] += res.down_bytes
            t['new_conn'] += res.new_connections

    def snapshot(self) -> dict:
        with self.lock:
            return {c: dict(v) for c, v in self.totals.items()}

    @staticmethod
    def delta(before: dict, after: dict) -> dict:
        out = {}
        for c in after:
            out[c] = {k: after[c][k] - before[c][k] for k in after[c]}
        up = sum(v['up'] for v in out.values())
        down = sum(v['down'] for v in out.values())
        conns = sum(v['new_conn'] for v in out.values())
        out['total'] = {
            'up': up, 'down': down, 'new_conn': conns,
            'tls_est_up': conns * TLS_HANDSHAKE_EST_UP, 'tls_est_down': conns * TLS_HANDSHAKE_EST_DOWN,
        }
        return out


class Net:
    def __init__(self, *, proxy: str | None, delay_youtube: float, delay_media: float,
                 max_requests: int, max_media_requests: int, timeout: float = 25.0, log=None):
        if proxy and proxy.startswith('socks5://'):
            raise ValueError('use socks5h:// (remote DNS through the proxy), not socks5://')
        self.proxy = proxy
        self.delay = {'youtube': delay_youtube, 'media': delay_media, 'other': 0.0}
        self.caps = {'youtube': max_requests, 'media': max_media_requests, 'other': 10_000}
        self.timeout = timeout
        self.ledger = Ledger()
        self._last_done = {'youtube': 0.0, 'media': 0.0, 'other': 0.0}
        self._sessions = {}
        self.log = log or (lambda *a, **k: None)
        self.dry_run = False

    # -- sessions -----------------------------------------------------------------------
    def _session(self, impersonate, ja3, akamai, cap=None):
        key = (impersonate, ja3, akamai, cap)
        sess = self._sessions.get(key)
        if sess is None:
            kwargs = {'curl_infos': [CurlInfo.STARTTRANSFER_TIME, CurlInfo.NUM_CONNECTS, CurlInfo.TOTAL_TIME]}
            if cap:
                kwargs['curl_options'] = {CurlOpt.MAXFILESIZE_LARGE: cap}
            if impersonate:
                kwargs['impersonate'] = impersonate
            if ja3:
                kwargs['ja3'] = ja3
            if akamai:
                kwargs['akamai'] = akamai
            sess = cffi.Session(**kwargs)
            self._sessions[key] = sess
        return sess

    def close(self):
        for s in self._sessions.values():
            try:
                s.close()
            except Exception:
                pass

    # -- the one request method -----------------------------------------------------------
    def request(self, method: str, url: str, *, headers: dict | None = None, data: bytes | None = None,
                impersonate: str | None = None, http_version: str | None = None, ja3: str | None = None,
                akamai: str | None = None, browser_headers: bool = False, max_bytes: int | None = None,
                body_cap: int | None = None, stream_max: int | None = None, allow_redirects: bool = True,
                purpose: str = '') -> NetResult:
        if self.dry_run:
            raise RuntimeError('network request attempted in --dry-run: ' + url)
        cls = host_class(url)
        res = NetResult(url=url, method=method, host_class=cls)
        if self.ledger.totals[cls]['requests'] >= self.caps[cls]:
            raise StopRun('request-cap', f'{cls} request cap reached ({self.caps[cls]}); raise --max-requests / '
                                         f'--max-media-requests deliberately if the run needs more')
        # Rate limit: measured from the END of the previous request of the same class.
        wait = self._last_done[cls] + self.delay[cls] - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        # a head read (stream_max) stops itself; the size cap would refuse a big Content-Length up front
        cap = None if stream_max else (body_cap or (MEDIA_BODY_CAP if cls == 'media' else None))
        sess = self._session(impersonate, ja3, akamai, cap=cap)
        hdrs = {k: str(v) for k, v in (headers or {}).items() if v is not None}
        # curl only DECODES what it negotiated itself, so Accept-Encoding goes through its option.
        accept_encoding = None
        for k in list(hdrs):
            if k.lower() == 'accept-encoding':
                accept_encoding = hdrs.pop(k)
        kwargs = dict(headers=hdrs, data=data, timeout=self.timeout, allow_redirects=allow_redirects,
                      default_headers=bool(browser_headers), discard_cookies=True, accept_encoding=accept_encoding)
        if self.proxy:
            kwargs['proxy'] = self.proxy
        if http_version:
            kwargs['http_version'] = {'v1': 'v1', '1.1': 'v1', 'v2': 'v2', '2': 'v2', 'v3': 'v3', '3': 'v3'}.get(
                str(http_version), http_version)
        t0 = time.monotonic()
        res.t_start = t0
        r = None
        try:
            streamed = None
            try:
                if stream_max:
                    # Read only the head of a big text resource (e.g. a live HLS playlist) and hang up.
                    r = sess.request(method, url, stream=True, **kwargs)
                    chunks, got = [], 0
                    for chunk in r.iter_content():
                        chunks.append(chunk)
                        got += len(chunk)
                        if got >= stream_max:
                            res.truncated = True
                            break
                    r.close()
                    streamed = b''.join(chunks)
                else:
                    r = sess.request(method, url, **kwargs)
            except cffi.exceptions.RequestException as e:
                if getattr(e, 'code', None) != 63 and 'curl: (63)' not in str(e):
                    raise
                # Over the body cap (curl error 63): keep what curl parsed (status, headers), mark it.
                r = getattr(e, 'response', None)
                res.truncated = True
                res.error = f'body-over-cap({cap})'
                if r is None:
                    raise
            headers_at = time.monotonic()
            res.body = streamed if streamed is not None else (r.content or b'')
            if max_bytes is not None and len(res.body) > max_bytes:
                res.body = res.body[:max_bytes]
                res.truncated = True
            res.status = r.status_code or None
            res.final_url = getattr(r, 'url', None) or url
            res.redirects = int(getattr(r, 'redirect_count', 0) or 0)
            res.headers = {k.lower(): v for k, v in r.headers.items()}
            try:
                res.set_cookies = list(r.headers.get_list('set-cookie'))
            except Exception:
                res.set_cookies = [v for k, v in r.headers.multi_items() if k.lower() == 'set-cookie'] \
                    if hasattr(r.headers, 'multi_items') else []
            res.up_bytes = int(r.request_size or 0)
            res.down_bytes = int((r.header_size or 0) + (r.download_size or 0))
            if streamed is not None:  # an early hang-up can leave curl's counter behind what we consumed
                res.down_bytes = max(res.down_bytes, int((r.header_size or 0) + len(streamed)))
            infos = r.infos or {}
            st = infos.get(CurlInfo.STARTTRANSFER_TIME)
            res.ttfb_ms = st * 1000 if st else (headers_at - t0) * 1000
            res.new_connections = int(infos.get(CurlInfo.NUM_CONNECTS) or 0)
            res.http_version = _HTTP_VERSIONS.get(getattr(r, 'http_version', 0), str(getattr(r, 'http_version', '')))
        except Exception as e:  # transport error: record, never raise into the matrix
            res.error = res.error or f'{type(e).__name__}: {str(e)[:300]}'
            # Count what we can: the request was (probably) sent.
            res.up_bytes = res.up_bytes or (len(data) if data else 0) + 400
        finally:
            res.elapsed_ms = (time.monotonic() - t0) * 1000
            self._last_done[cls] = time.monotonic()
            self.ledger.add(res)
        self.log(f'  {method} {cls:7s} {res.status or res.error[:40]:>4} {res.elapsed_ms:7.0f}ms '
                 f'{res.down_bytes:>8}B {purpose} {_short(url)}')
        if cls == 'youtube' and res.status == 429:
            raise StopRun('http-429', f'HTTP 429 from {urllib.parse.urlparse(url).hostname} ({purpose}) - YouTube is '
                                      f'rate-limiting this IP; stopping the run')
        return res


def _short(url: str) -> str:
    p = urllib.parse.urlparse(url)
    return f'{p.hostname}{p.path[:60]}'
