"""Player JS, signatureTimestamp, and signature / n-challenge solving via the local yt-dlp checkout.

The yt-dlp checkout is used as a LIBRARY only:
  * YoutubeDL is constructed with explicit params - library use never reads a yt-dlp config
    file, and we pass no cookiefile / cookiesfrombrowser (asserted below).
  * Plugins are disabled (YTDLP_NO_PLUGINS) so nothing from ~/.config/yt-dlp or the uv tool env
    is imported.
  * Its cache dir is harness/.cache/yt-dlp, never ~/.cache/yt-dlp.
  * It never downloads anything itself: the player JS and the EJS solver lib are fetched through
    Net (proxy, rate limit, byte accounting) and injected into its caches; its jsc director then
    runs deno locally.
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
import urllib.parse

from . import HARNESS_DIR
from .net import Net

YTDLP_DIR = pathlib.Path(os.environ.get('NETBENCH_YTDLP', str(pathlib.Path.home() / 'projects' / 'yt-dlp')))
DENO_PATH = os.environ.get('NETBENCH_DENO') or shutil.which('deno') or str(pathlib.Path.home() / '.deno' / 'bin' / 'deno')
CACHE_DIR = HARNESS_DIR / '.cache'
PLAYER_VARIANT_PATH = 'player_ias.vflset/en_US/base.js'


def ytdlp_commit() -> dict:
    try:
        out = subprocess.run(['git', '-C', str(YTDLP_DIR), 'log', '-1', '--format=%h %cI'],
                             capture_output=True, text=True, timeout=10).stdout.strip()
        commit, date = out.split(' ', 1)
        dirty = subprocess.run(['git', '-C', str(YTDLP_DIR), 'status', '--porcelain', '--untracked-files=no'],
                               capture_output=True, text=True, timeout=10).stdout.strip()
        return {'commit': commit, 'date': date, 'dirty': bool(dirty), 'path': str(YTDLP_DIR)}
    except Exception as e:
        return {'error': str(e), 'path': str(YTDLP_DIR)}


def deno_version() -> str | None:
    try:
        out = subprocess.run([DENO_PATH, '--version'], capture_output=True, text=True, timeout=20).stdout
        return out.splitlines()[0].strip() if out else None
    except Exception:
        return None


def import_ytdlp():
    os.environ['YTDLP_NO_PLUGINS'] = '1'
    if str(YTDLP_DIR) not in sys.path:
        sys.path.insert(0, str(YTDLP_DIR))
    import yt_dlp  # noqa: F401
    if not str(pathlib.Path(yt_dlp.__file__).resolve()).startswith(str(YTDLP_DIR.resolve())):
        raise RuntimeError(f'imported yt_dlp from {yt_dlp.__file__}, expected the checkout at {YTDLP_DIR}')
    return yt_dlp


class _Logger:
    def __init__(self, log):
        self.log = log
        self.warnings = []

    def debug(self, msg):
        if msg.startswith('[debug] ') and ('challenge' in msg.lower() or 'jsc' in msg.lower()):
            self.log('    ytdlp ' + msg[:200])

    def info(self, msg):
        pass

    def warning(self, msg):
        self.warnings.append(msg)
        self.log('    ytdlp WARNING ' + msg[:300])

    def error(self, msg):
        self.warnings.append(msg)
        self.log('    ytdlp ERROR ' + msg[:300])


class PlayerJS:
    """Run-wide player: id, url, code, sts, and a solver bound to it."""

    def __init__(self, net: Net, log=None):
        self.net = net
        self.log = log or (lambda *a: None)
        self.player_id = None
        self.player_url = None
        self.sts = None
        self.code_sha = None
        self.source = None
        self.ie = None
        self.ydl = None
        self._logger = _Logger(self.log)
        self.n_cache: dict[str, str] = {}
        self.sig_cache: dict[int, list] = {}
        self.solve_errors: list[str] = []

    # -- bootstrap ------------------------------------------------------------------------
    def bootstrap(self, player_id: str | None = None, sts_override: int | None = None) -> dict:
        info = {}
        if not player_id:
            res = self.net.request('GET', 'https://www.youtube.com/iframe_api',
                                   headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                                                          '(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36',
                                            'Accept-Encoding': 'gzip, deflate, br'},
                                   impersonate='chrome', purpose='bootstrap:iframe_api')
            m = re.search(r'player\\?/([0-9a-fA-F]{8})\\?/', res.text())
            if not m:
                raise RuntimeError(f'could not find the player id in iframe_api (HTTP {res.status} {res.error})')
            player_id = m.group(1)
            info['iframe_api'] = res.summary()
        self.player_id = player_id
        self.player_url = f'https://www.youtube.com/s/player/{player_id}/{PLAYER_VARIANT_PATH}'
        path = CACHE_DIR / 'player' / f'{player_id}-main.js'
        if path.exists():
            code = path.read_text()
            self.source = 'disk-cache'
        else:
            res = self.net.request('GET', self.player_url,
                                   headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                                                          '(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36',
                                            'Accept-Encoding': 'gzip, deflate, br'},
                                   impersonate='chrome', purpose='bootstrap:player_js')
            if res.status != 200 or not res.body:
                raise RuntimeError(f'player JS download failed: HTTP {res.status} {res.error}')
            code = res.text()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(code)
            self.source = 'downloaded'
            info['player_js_download'] = res.summary()
        self.code = code
        self.code_sha = hashlib.sha256(code.encode()).hexdigest()[:16]
        m = re.search(r'(?:signatureTimestamp|sts)\s*:\s*(?P<sts>[0-9]{5})', code)
        self.sts = sts_override or (int(m.group('sts')) if m else None)
        self._init_ytdlp()
        info.update({'player_id': self.player_id, 'player_url': self.player_url, 'player_variant': 'main',
                     'player_js_sha256': self.code_sha, 'player_js_bytes': len(code), 'player_js_source': self.source,
                     'sts': self.sts, 'sts_overridden': bool(sts_override)})
        return info

    def _init_ytdlp(self):
        yt_dlp = import_ytdlp()
        from yt_dlp.extractor.youtube.jsc._builtin import vendor
        params = {
            'quiet': True,
            'no_warnings': False,
            'logger': self._logger,
            'cachedir': str(CACHE_DIR / 'yt-dlp'),
            'js_runtimes': {'deno': {'path': DENO_PATH}},
            'remote_components': [],
            'noprogress': True,
        }
        # SAFETY: never cookies, never a config. Library construction does not read config files.
        for forbidden in ('cookiefile', 'cookiesfrombrowser', 'usenetrc', 'username', 'password'):
            assert forbidden not in params
        self._seed_solver_lib(vendor)
        self.ydl = yt_dlp.YoutubeDL(params)
        assert not self.ydl.params.get('cookiefile') and not self.ydl.params.get('cookiesfrombrowser')
        self.ie = self.ydl.get_info_extractor('Youtube')
        self.ie.initialize()
        key = self.ie._player_js_cache_key(self.player_url)
        self.ie._code_cache[key] = self.code
        self.ytdlp_version = yt_dlp.version.__version__
        self.ejs_version = vendor.VERSION

    def _seed_solver_lib(self, vendor):
        """yt-dlp's EJS core script is vendored; the lib is not. Fetch the minified lib from the
        pinned GitHub release through Net (so it is proxied and counted), verify its sha3-512
        against the checkout's pinned hash, and store it where yt-dlp's cache looks."""
        cache_file = CACHE_DIR / 'yt-dlp' / 'challenge-solver' / 'lib.json'
        if cache_file.exists():
            try:
                data = json.loads(cache_file.read_text())
                if data.get('version') == vendor.VERSION:
                    return
            except Exception:
                pass
        url = f'https://github.com/yt-dlp/ejs/releases/download/{vendor.VERSION}/yt.solver.lib.min.js'
        res = self.net.request('GET', url, headers={'User-Agent': 'netbench', 'Accept-Encoding': 'gzip'},
                               purpose='bootstrap:ejs-lib')
        if res.status != 200:
            raise RuntimeError(f'EJS lib download failed: HTTP {res.status} {res.error}')
        code = res.text()
        digest = hashlib.sha3_512(code.encode()).hexdigest()
        if digest != vendor.HASHES['yt.solver.lib.min.js']:
            raise RuntimeError('EJS lib hash mismatch - refusing to run an unverified solver script')
        cache_file.parent.mkdir(parents=True, exist_ok=True)
        cache_file.write_text(json.dumps({'version': vendor.VERSION, 'variant': 'minified', 'code': code}))

    # -- solving ----------------------------------------------------------------------------
    def solve(self, video_id: str, n_challenges: set, sig_lengths: set) -> dict:
        from yt_dlp.extractor.youtube.jsc.provider import (
            JsChallengeRequest, JsChallengeType, NChallengeInput, SigChallengeInput)
        n_todo = sorted(c for c in n_challenges if c not in self.n_cache)
        s_todo = sorted(n for n in sig_lengths if n not in self.sig_cache)
        t0 = time.monotonic()
        out = {'n_requested': len(n_challenges), 'sig_requested': len(sig_lengths),
               'n_new': len(n_todo), 'sig_new': len(s_todo), 'error': None}
        if n_todo or s_todo:
            reqs = []
            if n_todo:
                reqs.append(JsChallengeRequest(type=JsChallengeType.N, video_id=video_id,
                                               input=NChallengeInput(challenges=n_todo, player_url=self.player_url)))
            if s_todo:
                reqs.append(JsChallengeRequest(type=JsChallengeType.SIG, video_id=video_id,
                                               input=SigChallengeInput(
                                                   challenges=[''.join(map(chr, range(n))) for n in s_todo],
                                                   player_url=self.player_url)))
            try:
                for _req, resp in self.ie._jsc_director.bulk_solve(reqs):
                    if resp.type == JsChallengeType.N:
                        self.n_cache.update(resp.output.results)
                    else:
                        for challenge, result in resp.output.results.items():
                            self.sig_cache[len(challenge)] = [ord(c) for c in result]
            except Exception as e:
                out['error'] = f'{type(e).__name__}: {str(e)[:200]}'
                self.solve_errors.append(out['error'])
        out['ms'] = round((time.monotonic() - t0) * 1000, 1)
        out['n_missing'] = sum(1 for c in n_challenges if c not in self.n_cache)
        out['sig_missing'] = sum(1 for n in sig_lengths if n not in self.sig_cache)
        return out

    def finalize_url(self, url: str, s: str | None = None, sp: str | None = None) -> tuple[str | None, str | None]:
        """Apply the solved signature and n. Returns (url, error)."""
        if s:
            spec = self.sig_cache.get(len(s))
            if not spec:
                return None, 'sig-unsolved'
            sig = ''.join(s[i] for i in spec)
            url = f'{url}&{sp or "signature"}={sig}'  # unquoted, exactly as yt-dlp appends it
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        if 'n' in qs:
            n = qs['n'][0]
            solved = self.n_cache.get(n)
            if not solved:
                return None, 'n-unsolved'
            url = _replace_query_param(url, 'n', solved)
        return url, None

    def finalize_manifest_url(self, url: str) -> tuple[str | None, str | None]:
        m = re.search(r'/n/([^/]+)/', urllib.parse.urlparse(url).path)
        if not m:
            return url, None
        solved = self.n_cache.get(m.group(1))
        if not solved:
            return None, 'n-unsolved'
        return url.replace(f'/n/{m.group(1)}/', f'/n/{solved}/'), None

    def ad_wait(self, player_response: dict, video_id: str, client: str) -> dict:
        """yt-dlp's own pre-playback wait: now + the pre-roll ad duration (or its skip offset).
        This is what its downloader sleeps for before the first media request."""
        try:
            now = time.time()
            available_at = self.ie._get_available_at_timestamp(player_response, video_id, client)
            return {'ad_wait_s': max(0, available_at - int(now))}
        except Exception as e:
            return {'ad_wait_s': None, 'ad_wait_error': str(e)[:120]}


def _replace_query_param(url: str, key: str, value: str) -> str:
    parsed = urllib.parse.urlparse(url)
    parts = []
    for item in parsed.query.split('&'):
        k = item.split('=', 1)[0]
        parts.append(f'{key}={urllib.parse.quote(value, safe="")}' if k == key else item)
    return urllib.parse.urlunparse(parsed._replace(query='&'.join(parts)))
