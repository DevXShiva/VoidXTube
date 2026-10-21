"""A local stand-in for googlevideo + the InnerTube /player endpoint, for offline tests.

Nothing here talks to YouTube. It serves, on 127.0.0.1:<random port>:
  POST /youtubei/v1/player      a crafted player response pointing at the resources below
  GET  /media/<name>            byte-range resources (Range header or range= query) with scripted refusals
  GET  /hls/master.m3u8         master: one 360p variant with an AUDIO group
  GET  /hls/v.m3u8, /hls/a.m3u8 VOD media playlists (EXT-X-MAP init + N one-second segments, ENDLIST)
  GET  /hls/live.m3u8           a live playlist whose window advances one segment per second
  GET  /hls/<file>              init / segments
  GET  /status/<code>           any status (e.g. 429)
Every request is logged in FakeYT.log as (monotonic time, method, path, range tuple or None, status).
"""

from __future__ import annotations

import json
import re
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Resource:
    def __init__(self, size, deny_from_offset=None, deny_first_n=0, deny_once_at_offset=None):
        self.size = size
        self.deny_from_offset = deny_from_offset
        self.deny_first_n = deny_first_n
        self.deny_once_at_offset = deny_once_at_offset
        self.hits = 0
        self._denied_once = False


class FakeYT:
    def __init__(self):
        self.resources = {}
        self.log = []
        self.player_response = None
        self.hls_segments = 10
        self.t0 = time.monotonic()
        self.lock = threading.Lock()
        fake = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *a):
                pass

            def _send(self, status, body=b'', ctype='application/octet-stream', extra=None):
                self.send_response(status)
                self.send_header('Content-Type', ctype)
                self.send_header('Content-Length', str(len(body)))
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                if body:
                    self.wfile.write(body)

            def _range(self, query):
                h = self.headers.get('Range')
                m = re.match(r'bytes=(\d+)-(\d+)', h or '')
                if m:
                    return int(m.group(1)), int(m.group(2))
                q = urllib.parse.parse_qs(query).get('range')
                if q:
                    a, b = q[0].split('-')
                    return int(a), int(b)
                return None

            def do_POST(self):
                ln = int(self.headers.get('Content-Length') or 0)
                body = self.rfile.read(ln) if ln else b''
                path = urllib.parse.urlparse(self.path).path
                with fake.lock:
                    fake.log.append((time.monotonic(), 'POST', path, None, 200))
                    fake.last_player_body = body
                if path == '/youtubei/v1/player':
                    self._send(200, json.dumps(fake.player_response).encode(), 'application/json')
                else:
                    self._send(404)

            def do_GET(self):
                parsed = urllib.parse.urlparse(self.path)
                path, query = parsed.path, parsed.query
                rng = self._range(query)
                status, body, ctype, extra = 404, b'', 'text/plain', {}
                if path.startswith('/status/'):
                    status = int(path.rsplit('/', 1)[1])
                elif path.startswith('/media/'):
                    res = fake.resources.get(path.split('/', 2)[2])
                    if res is not None:
                        with fake.lock:
                            res.hits += 1
                            hit = res.hits
                        start = rng[0] if rng else 0
                        if hit <= res.deny_first_n:
                            status, body = 403, b'Forbidden'
                        elif res.deny_from_offset is not None and start >= res.deny_from_offset:
                            status, body = 403, b'Forbidden'
                        elif (res.deny_once_at_offset is not None and start >= res.deny_once_at_offset
                              and not res._denied_once):
                            res._denied_once = True
                            status, body = 403, b'Forbidden'
                        elif rng:
                            a, b = rng[0], min(rng[1], res.size - 1)
                            status, body = 206, bytes((i % 251) for i in range(a, b + 1))
                            extra = {'Content-Range': f'bytes {a}-{b}/{res.size}'}
                        else:
                            status, body = 200, bytes((i % 251) for i in range(res.size))
                elif path == '/hls/master.m3u8':
                    status, ctype = 200, 'application/vnd.apple.mpegurl'
                    body = ('#EXTM3U\n'
                            '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",NAME="en",DEFAULT=YES,URI="a.m3u8"\n'
                            '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="a1"\n'
                            'v.m3u8\n'
                            '#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,AUDIO="a1"\n'
                            'v1080.m3u8\n').encode()
                elif path in ('/hls/v.m3u8', '/hls/a.m3u8'):
                    kind = path[5]
                    lines = ['#EXTM3U', '#EXT-X-TARGETDURATION:1', '#EXT-X-MEDIA-SEQUENCE:0',
                             f'#EXT-X-MAP:URI="{kind}init.mp4"']
                    for i in range(fake.hls_segments):
                        lines += ['#EXTINF:1.0,', f'{kind}seg{i}.m4s']
                    lines.append('#EXT-X-ENDLIST')
                    status, ctype, body = 200, 'application/vnd.apple.mpegurl', ('\n'.join(lines) + '\n').encode()
                elif path == '/hls/master-live.m3u8':
                    status, ctype = 200, 'application/vnd.apple.mpegurl'
                    body = ('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlive.m3u8\n').encode()
                elif path == '/hls/live.m3u8':
                    now = int(time.monotonic() - fake.t0)
                    lines = ['#EXTM3U', '#EXT-X-TARGETDURATION:1', f'#EXT-X-MEDIA-SEQUENCE:{now}']
                    for i in range(now, now + 4):
                        lines += ['#EXTINF:1.0,', f'live{i}.ts']
                    status, ctype, body = 200, 'application/vnd.apple.mpegurl', ('\n'.join(lines) + '\n').encode()
                elif path.startswith('/hls/'):
                    status, body = 200, b'S' * 2000
                    if rng:
                        status, body = 206, body[rng[0]:rng[1] + 1]
                with fake.lock:
                    fake.log.append((time.monotonic(), 'GET', path, rng, status))
                self._send(status, body, ctype, extra)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.port = self.server.server_address[1]
        self.base = f'http://127.0.0.1:{self.port}'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    def media(self, name, **kw):
        self.resources[name] = Resource(**kw)
        return f'{self.base}/media/{name}?itag=1'

    def gets(self, prefix):
        return [e for e in self.log if e[1] == 'GET' and e[2].startswith(prefix)]

    def standard_player(self, *, sabr_only=False, hls=True, video_size=1_000_000, audio_size=400_000, **media_kw):
        v_kw = {k[2:]: val for k, val in media_kw.items() if k.startswith('v_')}
        a_kw = {k[2:]: val for k, val in media_kw.items() if k.startswith('a_')}
        video = {'itag': 247, 'mimeType': 'video/webm; codecs="vp9"', 'height': 360, 'width': 640,
                 'bitrate': 900_000, 'averageBitrate': 800_000, 'contentLength': str(video_size)}
        audio = {'itag': 251, 'mimeType': 'audio/webm; codecs="opus"', 'bitrate': 170_000,
                 'averageBitrate': 160_000, 'contentLength': str(audio_size),
                 'audioTrack': {'audioIsDefault': True}}
        if not sabr_only:
            video['url'] = self.media('video', size=video_size, **v_kw)
            audio['url'] = self.media('audio', size=audio_size, **a_kw)
        sd = {'adaptiveFormats': [video, audio], 'expiresInSeconds': '21540',
              'serverAbrStreamingUrl': f'{self.base}/sabr'}
        if hls:
            sd['hlsManifestUrl'] = f'{self.base}/hls/master.m3u8'
        self.player_response = {'playabilityStatus': {'status': 'OK'}, 'streamingData': sd,
                                'videoDetails': {'videoId': 'FAKEVIDEO01', 'lengthSeconds': '10'}}
        return self.player_response


class FakePlayerJS:
    """Stands in for nb.playerjs.PlayerJS: no challenges, URLs pass through, no ad wait."""
    sts = 20719
    solve_errors = []

    def __init__(self, ad_wait_s=0):
        self._ad_wait = ad_wait_s

    def solve(self, video_id, n, sig):
        return {'n_requested': len(n), 'sig_requested': len(sig), 'n_missing': 0, 'sig_missing': 0, 'ms': 0.0,
                'error': None}

    def finalize_url(self, url, s=None, sp=None):
        return url, None

    def finalize_manifest_url(self, url):
        return url, None

    def ad_wait(self, pr, video_id, client):
        return {'ad_wait_s': self._ad_wait}
