"""Offline tests for `netbench sustain` (and the report's sustain / bot-wall sections).

    cd harness && python3 -m unittest discover -s tests -v

Every request goes to a local fake server (tests/fake_yt.py). A guard wraps Net.request for the
whole module and FAILS the test if anything targets a host other than 127.0.0.1 - these tests cannot
reach YouTube or googlevideo even by mistake.
"""

from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import time
import types
import unittest
import urllib.parse

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

from fake_yt import FakeYT, FakePlayerJS  # noqa: E402
from nb import net as netmod  # noqa: E402
from nb import report, sustain  # noqa: E402
from nb.identity import IdentityStore  # noqa: E402
from nb.media import MediaProber  # noqa: E402
from nb.util import merge_patch  # noqa: E402
from nb.variants import DEFAULT_VARIANT  # noqa: E402

_real_request = netmod.Net.request


def _local_only(self, method, url, **kw):
    host = urllib.parse.urlparse(url).hostname
    if host not in ('127.0.0.1', 'localhost'):
        raise AssertionError(f'offline test tried to reach {host}: {url[:80]}')
    return _real_request(self, method, url, **kw)


def setUpModule():
    netmod.Net.request = _local_only


def tearDownModule():
    netmod.Net.request = _real_request


def make_args(**kw):
    base = dict(network='test', duration_s=8.0, chunk_s=1.0, prebuffer_s=2.0, seek_at_s=3.0, seek_frac=0.7,
                startup_schedule=[0.0, 0.3, 0.6, 0.9], delivery='auto', fresh_identity=False)
    base.update(kw)
    return types.SimpleNamespace(**base)


def make_variant(fake, **over):
    v = merge_patch(DEFAULT_VARIANT, {
        'id': 'x-fake', 'tags': {'client': 'fake'},
        'client': {'clientName': 'WEB', 'clientVersion': '2.0', 'userAgent': 'fake-ua'},
        'client_name_id': 1,
        'endpoint': {'scheme': 'http', 'host': f'127.0.0.1:{fake.port}', 'path': '/youtubei/v1/player', 'query': {}},
        'identity': {'pages': []},
        'transport': {'impersonate': None},
        'media': {'headers': {'User-Agent': '${user_agent}'}, 'range_mode': 'header', 'impersonate': None},
    })
    return merge_patch(v, over)


def make_cell(variant, deliveries=None, live=False):
    return {'variant': variant, 'source_run': 'fixture', 'source_network': 'test', 'source_player': None,
            'source': {'video_id': 'FAKEVIDEO01', 'category': 'test', 'verdict': {'code': 'PLAY', 'label': 'PLAY A@0s'},
                       'deliveries': deliveries or {'adaptive': {'result': 'PLAY'}}, 'video': {'is_live': live}}}


def run_cell(fake, args, variant=None, pj=None, deliveries=None, live=False):
    net = netmod.Net(proxy=None, delay_youtube=3, delay_media=1, max_requests=50, max_media_requests=500)
    pj = pj or FakePlayerJS()
    prober = MediaProber(net, pj, [0.0], 0.0)
    rec = sustain.sustain_cell(net, pj, IdentityStore(net), prober,
                               make_cell(variant or make_variant(fake), deliveries, live), args, 'test-run', 1)
    net.close()
    return rec


def media_rows(rec, stream=None):
    return [c for c in rec['chunks'] if c['kind'] in ('range', 'segment') and (stream is None or c['stream'] == stream)]


class AdaptiveSustain(unittest.TestCase):
    def test_ok_paced_with_seek_to_eof(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False)
            rec = run_cell(fake, make_args())
        v = rec['verdict']
        self.assertEqual(v['code'], 'SUSTAIN-OK', rec.get('traceback'))
        self.assertTrue(v['eof'])
        self.assertTrue(v['label'].startswith('SUSTAIN-OK A EOF@'))
        vid = media_rows(rec, 'adaptive_video')
        before = [c for c in vid if not c.get('after_seek')]
        after = [c for c in vid if c.get('after_seek')]
        # 800 kbit/s -> 100,000 B per 1 s chunk, sequential from 0
        self.assertEqual([c['range'][0] for c in before], [i * 100_000 for i in range(len(before))])
        self.assertTrue(all(c['range'][1] == 100_000 for c in before))
        self.assertEqual(after[0]['range'][0], 700_000)            # seek to 70 %
        self.assertEqual(after[-1]['range'][0] + after[-1]['range'][1], 1_000_000)  # read to the end
        aud_after = [c for c in media_rows(rec, 'adaptive_audio') if c.get('after_seek')]
        self.assertEqual(aud_after[0]['range'][0], 280_000)
        self.assertTrue(all(c['status'] == 206 and c['ok'] for c in rec['chunks']))
        # pacing: past the 2 s prebuffer, chunk k (1 s of media each) is not requested before ~k-2 s
        for k, c in enumerate(before):
            self.assertGreaterEqual(c['play_s'] if c['play_s'] is not None else 0, k - 2 - 0.3)
        self.assertGreaterEqual(rec['seek']['at_s'], 3.0)

    def test_audio_refused_mid_play_is_fail_with_retry(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False, a_deny_from_offset=131_072)   # the 3rd 64 KiB audio chunk
            rec = run_cell(fake, make_args(seek_at_s=None))
            gets = len(fake.gets('/media/'))
        v = rec['verdict']
        self.assertEqual(v['code'], 'FAIL')
        ff = v['first_failure']
        self.assertEqual((ff['stream'], ff['code'], ff['retry_code']), ('adaptive_audio', 'HTTP403', 'HTTP403'))
        self.assertTrue(v['label'].startswith('FAIL@') and '(HTTP403) A:adaptive_audio' in v['label'])
        last, retry = rec['chunks'][-2], rec['chunks'][-1]
        self.assertEqual((last['status'], retry['status'], retry['retry']), (403, 403, True))
        self.assertGreaterEqual(retry['play_s'] - last['play_s'], 1.9)   # retried after ~2 s
        self.assertEqual(gets, len(rec['chunks']))                        # nothing requested after the retry
        self.assertAlmostEqual(ff['t_s'], last['play_s'], places=2)
        # 160 kbit/s = 20,000 B/s: the refused 3rd chunk (offset 131,072) covers media 6.55-9.83 s
        self.assertEqual((ff['media_pos_s'], ff['media_end_s']), (6.55, 9.83))

    def test_transient_refusal_is_a_blip(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False, v_deny_once_at_offset=300_000)
            rec = run_cell(fake, make_args())
        v = rec['verdict']
        self.assertEqual(v['code'], 'SUSTAIN-OK')
        self.assertEqual(len(v['blips']), 1)
        self.assertIn('1 blip', v['label'])
        retry = [c for c in rec['chunks'] if c.get('retry')]
        self.assertEqual(len(retry), 1)
        self.assertTrue(retry[0]['ok'])

    def test_startup_honours_first_ok(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False, v_deny_first_n=2)
            rec = run_cell(fake, make_args(duration_s=2.0, seek_at_s=None))
        start = [c for c in rec['chunks'] if c['phase'] == 'startup' and c['stream'] == 'adaptive_video']
        self.assertEqual([c['status'] for c in start], [403, 403, 206])
        self.assertGreaterEqual(rec['startup']['first_ok_after_s']['adaptive_video'], 0.55)
        self.assertEqual(rec['verdict']['code'], 'SUSTAIN-OK')

    def test_startup_fail(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False, v_deny_first_n=100)
            rec = run_cell(fake, make_args())
            audio_gets = len(fake.gets('/media/audio'))
        self.assertEqual(rec['verdict']['code'], 'STARTUP-FAIL')
        self.assertEqual(rec['verdict']['label'], 'STARTUP-FAIL (HTTP403) A:adaptive_video')
        self.assertEqual(len(media_rows(rec, 'adaptive_video')), 4)   # one per startup slot
        self.assertEqual(audio_gets, 0)

    def test_ad_wait_is_honoured_before_the_first_chunk(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False)
            rec = run_cell(fake, make_args(duration_s=1.0, seek_at_s=None), pj=FakePlayerJS(ad_wait_s=1))
        self.assertEqual(rec['ad_wait_s'], 1)
        self.assertGreaterEqual(rec['chunks'][0]['wall_s'], 0.95)

    def test_sabr_only_answer_is_no_media(self):
        with FakeYT() as fake:
            fake.standard_player(sabr_only=True, hls=False)
            rec = run_cell(fake, make_args())
            gets = fake.gets('/media/')
        self.assertEqual(rec['verdict']['code'], 'NO-MEDIA')
        self.assertEqual(rec['verdict']['label'], 'NO-MEDIA (SABR)')
        self.assertEqual(gets, [])

    def test_429_stops_the_run(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False)
            fake.player_response['streamingData']['adaptiveFormats'][0]['url'] = f'{fake.base}/status/429'
            rec = run_cell(fake, make_args())
        self.assertEqual(rec['stop']['code'], 'http-429')
        self.assertEqual(rec['verdict']['code'], 'STOPPED')

    def test_player_request_is_the_variant_s(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False)
            run_cell(fake, make_args(duration_s=0.5, seek_at_s=None))
            body = json.loads(fake.last_player_body)
        self.assertEqual(body['videoId'], 'FAKEVIDEO01')
        self.assertEqual(body['context']['client']['clientName'], 'WEB')
        self.assertEqual(body['playbackContext']['contentPlaybackContext']['signatureTimestamp'], 20719)


class HlsSustain(unittest.TestCase):
    def test_vod_init_first_audio_group_seek_eof(self):
        with FakeYT() as fake:
            fake.standard_player(hls=True)
            rec = run_cell(fake, make_args(delivery='hls'))
            order = [e[2] for e in fake.log if e[1] == 'GET']
        self.assertEqual(rec['verdict']['code'], 'SUSTAIN-OK', rec.get('traceback'))
        self.assertEqual(set(rec['streams']), {'hls_video', 'hls_audio'})
        self.assertEqual(order[:3], ['/hls/master.m3u8', '/hls/v.m3u8', '/hls/a.m3u8'])   # 360p, not 1080p
        for kind, name in (('v', 'hls_video'), ('a', 'hls_audio')):
            rows = [c for c in rec['chunks'] if c['stream'] == name]
            self.assertEqual(rows[0]['kind'], 'init')
            segs = [e for e in order if e.startswith(f'/hls/{kind}seg')]
            idx = [int(s.split('seg')[1].split('.')[0]) for s in segs]
            before = [i for i, c in zip(idx, [r for r in rows if r['kind'] == 'segment']) if not c.get('after_seek')]
            self.assertEqual(before, list(range(len(before))))
            self.assertIn(7, idx[len(before):][:1])            # seek to int(10 * 0.7)
            self.assertEqual(idx[-1], 9)                         # played to the end
        self.assertTrue(rec['verdict']['eof'])
        self.assertTrue(rec['verdict']['label'].startswith('SUSTAIN-OK H EOF@'))

    def test_live_reloads_and_never_repeats_a_segment(self):
        with FakeYT() as fake:
            fake.standard_player(hls=True)
            fake.player_response['streamingData']['hlsManifestUrl'] = f'{fake.base}/hls/master-live.m3u8'
            rec = run_cell(fake, make_args(delivery='hls', duration_s=5.0), live=True)
            segs = [e[2] for e in fake.log if e[2].startswith('/hls/live') and e[2].endswith('.ts')]
            reloads = len([e for e in fake.log if e[2] == '/hls/live.m3u8'])
        self.assertEqual(rec['verdict']['code'], 'SUSTAIN-OK', rec.get('traceback'))
        self.assertFalse(rec['verdict']['eof'])
        seqs = [int(s[len('/hls/live'):-3]) for s in segs]
        self.assertEqual(seqs, sorted(set(seqs)))            # increasing, no duplicates
        self.assertGreaterEqual(reloads, 3)
        self.assertEqual(rec['seek']['streams']['hls_video'], {'skipped': 'live'})


class Selection(unittest.TestCase):
    def test_load_cells_and_delivery(self):
        header = {'type': 'header', 'run_id': 'src', 'network': 'wifi', 'player': {'player_id': 'fb50cd46'},
                  'variants': [{'id': 'nt-A'}, {'id': 'nt-B'}]}
        atts = [
            {'type': 'attempt', 'variant_id': 'nt-A', 'video_id': 'v1', 'category': 'kids',
             'verdict': {'code': 'PLAY'}, 'deliveries': {'adaptive': {'result': 'PLAY'}, 'hls': {'result': 'PLAY'}}},
            {'type': 'attempt', 'variant_id': 'nt-B', 'video_id': 'v1', 'category': 'kids', 'verdict': {'code': 'SABR'}},
            {'type': 'attempt', 'variant_id': 'nt-A', 'video_id': 'v2', 'category': 'live', 'video': {'is_live': True},
             'verdict': {'code': 'PLAY'}, 'deliveries': {'adaptive': {'result': 'PLAY'}, 'hls': {'result': 'PLAY'}}},
            {'type': 'attempt', 'variant_id': 'nt-B', 'video_id': 'v3', 'category': 'kids',
             'verdict': {'code': 'PLAY'}, 'deliveries': {'adaptive': {'result': 'SABR'}, 'progressive': {'result': 'PLAY'}}},
        ]
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / 'src.jsonl'
            p.write_text('\n'.join(json.dumps(x) for x in [header] + atts) + '\n')
            cells, sources = sustain.load_cells([str(p)], {'PLAY'}, [], set(), set())
            self.assertEqual([(c['variant']['id'], c['source']['video_id']) for c in cells],
                             [('nt-A', 'v1'), ('nt-A', 'v2'), ('nt-B', 'v3')])
            self.assertEqual(sources[0]['player_id'], 'fb50cd46')
            self.assertEqual([sustain.pick_delivery('auto', c['source']) for c in cells], ['adaptive', 'hls', 'progressive'])
            only_b, _ = sustain.load_cells([str(p)], {'PLAY'}, ['nt-B'], set(), set())
            self.assertEqual(len(only_b), 1)
            kids, _ = sustain.load_cells([str(p)], {'PLAY', 'SABR'}, [], {'v1'}, set())
            self.assertEqual(len(kids), 2)


class Report(unittest.TestCase):
    def test_sustain_section_and_bot_wall_note(self):
        with FakeYT() as fake:
            fake.standard_player(hls=False, a_deny_from_offset=131_072)
            rec = run_cell(fake, make_args())
        sus_run = {'header': {'run_id': 'sus', 'network': 'test', 'mode': 'sustain', 'ts': '2026-09-28T19:00:00+0200'},
                   'footer': {'totals': {'youtube': {'requests': 1}}}, 'attempts': [], 'sustains': [rec]}
        wall = {'type': 'attempt', 'variant_id': 'x-wall', 'video_id': 'v9', 'category': 'kids',
                'ts': '2026-09-28T19:03:00+0200', 'verdict': {'code': 'BOTWALL', 'label': 'BOTWALL bot'},
                'playability': {'reason': "Sign in to confirm you're not a bot"},
                'stop': {'code': 'bot-wall'}, 'player': {'status': 200, 'ms': 120.0},
                'bytes': {'youtube': {'requests': 2}, 'total': {'up': 2000, 'down': 3000}}}
        ok = dict(wall, variant_id='x-ok', verdict={'code': 'SABR', 'label': 'SABR'}, stop=None,
                  ts='2026-09-28T19:01:00+0200')
        wall_run = {'header': {'run_id': 'w', 'network': 'test', 'ts': '2026-09-28T19:00:00+0200'},
                    'footer': {'stopped': {'code': 'bot-wall'}, 'totals': {'youtube': {'requests': 5}}},
                    'attempts': [ok, wall], 'sustains': []}
        text = report.render([sus_run, wall_run])
        self.assertIn('### Sustain', text)
        self.assertIn(rec['verdict']['label'], text)
        self.assertIn('### Bot walls', text)
        self.assertIn('**`x-wall`** on `v9` (kids) hit a bot wall in its /player answer after **5 YouTube requests** '
                      '(2 /player attempts, 1 bootstrap), 3.0 min into the run', text)
        self.assertIn('| tv | `x-wall` |', text.replace('| other |', '| tv |'))
        row = next(l for l in text.splitlines() if l.startswith('| ') and '`x-wall`' in l)
        self.assertTrue(row.rstrip(' |').endswith('BOTWALL'), row)


if __name__ == '__main__':
    unittest.main()


class EndToEnd(unittest.TestCase):
    """`netbench sustain` as the CLI runs it: source file -> cells -> real PlayerJS from the disk cache
    (no request; the module guard would fail the test) -> fake /player + media -> results file -> report."""

    def test_cli_run_from_source_file(self):
        from nb.playerjs import CACHE_DIR
        if not (CACHE_DIR / 'player' / 'fb50cd46-main.js').exists() or \
                not (CACHE_DIR / 'yt-dlp' / 'challenge-solver' / 'lib.json').exists():
            self.skipTest('needs the cached player JS fb50cd46 and the EJS lib (from an earlier online run)')
        from nb.cli import main
        with FakeYT() as fake, tempfile.TemporaryDirectory() as d:
            fake.standard_player(hls=False)
            variant = make_variant(fake)
            header = {'type': 'header', 'run_id': 'src', 'network': 'wifi', 'player': {'player_id': 'fb50cd46'},
                      'variants': [variant]}
            att = {'type': 'attempt', 'variant_id': 'x-fake', 'video_id': 'FAKEVIDEO01', 'category': 'test',
                   'verdict': {'code': 'PLAY', 'label': 'PLAY A@0s'}, 'deliveries': {'adaptive': {'result': 'PLAY'}}}
            src = pathlib.Path(d) / 'src.jsonl'
            src.write_text(json.dumps(header) + '\n' + json.dumps(att) + '\n')
            code = main(['sustain', '--from', str(src), '--network', 'test', '--duration-s', '3', '--chunk-s', '1',
                         '--prebuffer-s', '1', '--seek-at-s', '2', '--startup-schedule', '0,0.5',
                         '--run-id', 'e2e', '--out-dir', d])
            self.assertEqual(code, 0)
            lines = [json.loads(l) for l in (pathlib.Path(d) / 'e2e.jsonl').read_text().splitlines()]
            self.assertEqual([l['type'] for l in lines], ['header', 'sustain', 'footer'])
            self.assertEqual(lines[0]['player']['player_id'], 'fb50cd46')
            self.assertEqual(lines[0]['player']['player_js_source'], 'disk-cache')
            self.assertEqual(lines[1]['verdict']['code'], 'SUSTAIN-OK', lines[1].get('traceback'))
            self.assertEqual(lines[2]['totals']['youtube']['requests'], 0)
            text = report.render(report.load([str(pathlib.Path(d) / 'e2e.jsonl')]))
            self.assertIn('| `x-fake` | SUSTAIN-OK A 3s |', text)
