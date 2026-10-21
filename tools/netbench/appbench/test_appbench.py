#!/usr/bin/env python3
"""appbench.parse on a fixed log: the phase fields, and the pre-existing fields left as they were.

Offline: python3 tools/netbench/appbench/test_appbench.py (no adb, no device).
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import appbench  # noqa: E402

V = "_WB5hh7WOb4"
# A cold kids open (VISIONOS refused, TV_TIZEN played), as a Pixel logged it on 2026-09-29, plus
# the lines this build adds: the warm's Cronet breakdown, the still-lift path, the embed identity.
LOG = f"""\
09-29 00:27:35.835 D/NetPath (22152): ep=1 video={V} tap
09-29 00:27:35.910 D/NetPath (22152): player-context video={V} client=VISIONOS cver=1.02 visitorSource=web-pot visitor=ae192e7163 visitorAgeMs=-1 playerPot=n
09-29 00:27:35.954 D/NetPath (22152): player-http[S] rid=1 video={V} client=101 cver=1.02
09-29 00:27:36.219 D/NetPath (22152): player-http[C] rid=1 video={V} code=200 ms=265 net=cell:183 protocol=h2
09-29 00:27:36.228 D/NetPath (22152): player-result video={V} client=VISIONOS attempt=1 status=UNPLAYABLE playable=n auth=n srvAuth=? formats=0+0 usableAdaptive=0 dash=n hls=n sabr=n reason="Este video no esta disponible"
09-29 00:27:36.230 D/NetPath (22152): embed-identity source=restored ageMin=42
09-29 00:27:36.384 D/NetPath (22152): player-result video={V} client=TV_TIZEN attempt=2 status=OK playable=y auth=n srvAuth=n formats=26+1 usableAdaptive=26 dash=n hls=n sabr=n reason="null"
09-29 00:27:36.499 D/NetPath (22152): v8-player cached=y challenges=2
09-29 00:27:36.515 D/NetPath (22152): warm rr4---sn-uxax4vopj5xn-cjoe.googlevideo.com +130ms dns=4 connect=96 ssl=61 wait=28 reused=n proto=h3
09-29 00:27:36.624 D/NetPath (22152): v8-run reused=y initMs=0 solveMs=125 stdinKb=3783
09-29 00:27:36.625 D/NetPath (22152): player-sig video={V} holders=28 n=2/27 s=0/0 nOut=27/27,unchanged=0,size=match sOut=0/0,unchanged=0,size=absent ms=239
09-29 00:27:36.627 D/NetPath (22152): player-transform video={V} client=TV_TIZEN ms=242
09-29 00:27:36.631 D/NetPath (22152): ep=1 video={V} info +796 dash=26 hls=n sabr=n live=n
09-29 00:27:36.660 D/NetPath (22152): ep=1 video={V} suggest ready +825
09-29 00:27:36.665 D/NetPath (22152): ep=1 video={V} prepare +830 type=dash-mpd
09-29 00:27:36.690 D/NetPath (22152): ep=1 video={V} media-load init +855 track=video id=244 h=480
09-29 00:27:36.736 D/NetPath (22152): ep=1 video={V} media-init done +901 track=audio loadMs=43 bytes=494
09-29 00:27:36.737 D/NetPath (22152): ep=1 video={V} media-init done +902 track=video loadMs=45 bytes=657
09-29 00:27:36.896 D/NetPath (22152): ep=1 video={V} decoder init +1061 type=video name=c2.exynos.vp9.decoder initMs=152
09-29 00:27:36.934 D/NetPath (22152): ep=1 video={V} decoder init +1099 type=audio name=c2.android.opus.decoder initMs=25
09-29 00:27:36.946 D/NetPath (22152): ep=1 video={V} first-frame +1111
09-29 00:27:36.960 D/NetPath (22152): ep=1 video={V} renderer-ready +1125 type=video
09-29 00:27:37.029 D/NetPath (22152): ep=1 video={V} ready +1194 bufferedMs=2867
09-29 00:27:37.030 D/NetPath (22152): ep=1 video={V} picture-visible +1195 state=ready-texture-overlay-gone lift=ready
09-29 00:29:38.708 D/NetPath (22152): warm rr5---sn-uxax4vopj5xn-cjol.googlevideo.com +610ms metrics=none
09-29 00:29:38.882 D/NetPath (22152): v8-run reused=y initMs=0 solveMs=145 stdinKb=3783
09-29 00:29:38.883 D/NetPath (22152): player-sig video={V} holders=26 n=2/25 s=0/0 ms=274
""".splitlines()


class PhasesTest(unittest.TestCase):
    def test_phase_fields(self):
        res = appbench.parse(LOG, V)
        self.assertEqual(res["first_frame_ms"], 1111)
        self.assertEqual(res["ready_ms"], 1194)  # not "suggest ready +825"
        self.assertEqual(res["picture_visible_ms"], 1195)
        self.assertEqual(res["picture_lift"], "ready")
        self.assertEqual(res["sig_ms"], 239)  # the played answer's, not the later one's
        self.assertEqual(res["v8_solve_ms"], 125)
        self.assertEqual(res["answer_client"], "TV_TIZEN")
        self.assertEqual(res["answer_ms"], 549)  # 36.384 - 35.835
        self.assertEqual(res["mli_ms"], 855)
        self.assertEqual(res["init_done_ms"], 902)
        self.assertEqual(res["dec_video_ms"], 152)
        self.assertEqual(res["dec_audio_ms"], 25)
        self.assertEqual(res["warm_host"], "rr4---sn-uxax4vopj5xn-cjoe.googlevideo.com")
        self.assertEqual((res["warm_ms"], res["warm_ok"], res["warm_done_ms"]), (130, True, 680))
        self.assertEqual((res["warm_dns_ms"], res["warm_connect_ms"], res["warm_ssl_ms"],
                          res["warm_wait_ms"], res["warm_reused"], res["warm_proto"]),
                         (4, 96, 61, 28, False, "h3"))
        self.assertEqual((res["embed_identity"], res["embed_fetch_ms"]), ("restored", None))

    def test_existing_fields_unchanged(self):
        res = appbench.parse(LOG, V)
        self.assertEqual(res["winner"], "TV_TIZEN")
        self.assertEqual(res["prepare"], {"ms": 830, "type": "dash-mpd"})
        self.assertEqual(res["info"], {"ms": 796, "detail": "dash=26 hls=n sabr=n live=n"})
        self.assertEqual([r["client"] for r in res["results"]], ["VISIONOS", "TV_TIZEN"])
        self.assertEqual(res["verdict"], "STALL@0s")  # no bench ticks in this excerpt

    def test_old_logs_and_missing_lines(self):
        old = [ln.replace(" lift=ready", "") for ln in LOG
               if "embed-identity" not in ln and "picture-visible" not in ln]
        old = [ln.split(" dns=")[0] for ln in old]
        res = appbench.parse(old, V)
        self.assertIsNone(res["picture_visible_ms"])
        self.assertIsNone(res["picture_lift"])
        self.assertIsNone(res["embed_identity"])
        self.assertEqual(res["warm_ms"], 130)
        self.assertIsNone(res["warm_dns_ms"])
        # A fetched identity and a failed warm with no metrics.
        res = appbench.parse(LOG[:1] + [
            "09-29 00:27:36.100 D/NetPath (1): embed-identity source=fetched ms=231",
            "09-29 00:27:36.200 D/NetPath (1): warm-failed rr1---x.googlevideo.com +8004ms reason=timeout metrics=none",
        ], V)
        self.assertEqual((res["embed_identity"], res["embed_fetch_ms"]), ("fetched", 231))
        self.assertEqual((res["warm_ok"], res["warm_ms"], res["warm_dns_ms"]), (False, 8004, None))
        self.assertIsNone(res["answer_ms"])

    def test_nothing_before_the_tap_counts(self):
        res = appbench.parse(["09-29 00:27:35.000 D/NetPath (1): warm rr1---x.googlevideo.com +90ms"] + LOG, V)
        self.assertEqual(res["warm_ms"], 130)



M = "w664JpkrDio"
# A members-only refusal settled at request 4, then the app's autoplay into a suggestion (v17 Wi-Fi).
REFUSED = f"""\
09-29 10:26:51.551 D/NetPath ( 3346): ep=3 video={M} tap
09-29 10:26:52.274 D/NetPath ( 3346): player-result video={M} client=IOS attempt=4 status=UNPLAYABLE playable=n auth=n srvAuth=n formats=0+0 usableAdaptive=0 dash=n hls=n sabr=n reason="Hazte miembro"
09-29 10:26:52.276 D/NetPath ( 3346): player-ring definitive-unplayable video={M} clients=VISIONOS,WEB_EMBED,ANDROID_VR,IOS reason-hash=1b9e3fe0 attempts=4 skipped=4
09-29 10:26:52.277 D/NetPath ( 3346): ep=3 video={M} info +726 dash=0 hls=n sabr=n live=n
""".splitlines()
AUTOPLAY = ['09-29 10:26:57.287 D/NetPath ( 3346): ep=4 video=orrMu1rSUUU open +0 "Cervical Stenosis"']


def ticks(video, *positions):
    return [f"09-29 00:28:{10 + i:02d}.000 D/NetPath (1): bench-tick video={video} pos={p} dur=213000 "
            f"buf=5000 state=READY playing=y t={i}" for i, p in enumerate(positions)]


class SettleTest(unittest.TestCase):
    def setUp(self):
        appbench.SETTLE_INFO_SEEN.clear()

    def test_the_window_is_judged_on_its_own_length(self):
        self.assertEqual(appbench.parse(LOG + ticks(V, 8000, 18000), V, 15)["verdict"], "PLAY-OK")
        self.assertEqual(appbench.parse(LOG + ticks(V, 8000, 9000), V, 15)["verdict"], "STALL@9s")
        # A full cell is judged as before.
        self.assertEqual(appbench.parse(LOG + ticks(V, 8000, 18000), V)["verdict"], "STALL@18s")

    def test_a_played_window_settles(self):
        self.assertFalse(appbench.settled(LOG + ticks(V, 8000), V, 15))
        self.assertTrue(appbench.settled(LOG + ticks(V, 8000, 18000), V, 15))

    def test_a_refusal_settles_when_the_app_moves_on(self):
        self.assertTrue(appbench.settled(REFUSED + AUTOPLAY, M, 15))

    def test_a_refusal_settles_after_a_grace(self):
        self.assertFalse(appbench.settled(REFUSED, M, 15))
        appbench.SETTLE_INFO_SEEN[M] -= 6
        self.assertTrue(appbench.settled(REFUSED, M, 15))

    def test_another_videos_playback_is_not_the_watched_one(self):
        # The autoplayed suggestion's ticks never count as the refused video's window.
        self.assertEqual(appbench.parse(REFUSED + AUTOPLAY + ticks("orrMu1rSUUU", 18000), M, 15)["verdict"],
                         "NO-START")

    def test_a_walk_still_running_does_not_settle(self):
        self.assertFalse(appbench.settled(LOG[:5], V, 15))


class SenderTurnTest(unittest.TestCase):
    """Two runs on one network take turns per open: the second waits for the first's open to end."""

    def setUp(self):
        import tempfile
        self.dir = tempfile.mkdtemp()
        appbench.SENDER_LOCK = os.path.join(self.dir, "sub", "sender.lock")

    def tearDown(self):
        appbench.SENDER_LOCK = None

    def test_a_turn_waits_for_the_other_runs_open(self):
        import subprocess
        import time
        holder = subprocess.Popen([sys.executable, "-c",
            "import fcntl,os,sys,time\n"
            f"os.makedirs(os.path.dirname({appbench.SENDER_LOCK!r}), exist_ok=True)\n"
            f"fh=open({appbench.SENDER_LOCK!r},'a'); fcntl.flock(fh, fcntl.LOCK_EX)\n"
            "print('held', flush=True); time.sleep(2)"], stdout=subprocess.PIPE)
        self.assertEqual(holder.stdout.readline().strip(), b"held")
        t = time.time()
        with appbench.sender_turn():
            waited = time.time() - t
        holder.wait()
        self.assertGreater(waited, 1.5)

    def test_off_means_no_wait(self):
        appbench.SENDER_LOCK = None
        with appbench.sender_turn():
            pass

    def test_battery_stop(self):
        class Args:
            min_battery = 8
        real = appbench.shell
        appbench.shell = lambda cmd, timeout=60: "Current Battery Service state:\n  AC powered: false\n  level: 3\n  scale: 100\n"
        try:
            self.assertEqual(appbench.run_cell(Args, "RING", V, 1, None), {"stop": "battery 3% < 8%"})
        finally:
            appbench.shell = real


# The LTE bot wall of 2026-09-29 (v16) and a private video's sign-in text (v17: not a wall).
WALL = ['09-29 09:59:46.637 D/NetPath (29152): bot-check walk-on client=WEB_EMBED signal=explicit attempt=2']
PRIVATE = ['09-29 10:25:40.100 D/NetPath ( 3346): bot-check walk-on client=ANDROID_VR signal=repeated-login attempt=3',
           '09-29 10:25:40.200 D/NetPath ( 3346): repeated-login unconfirmed reason=one-video']


class WalkTurnTest(unittest.TestCase):
    """Phones on one network never walk at once, but play at once; a bot check stops them all."""

    def setUp(self):
        import tempfile
        appbench.SENDER_LOCK = os.path.join(tempfile.mkdtemp(), "sender.lock")
        appbench.SERIAL = "test-serial"

    def tearDown(self):
        appbench.SENDER_LOCK = None

    def test_the_walk_is_decided_by_a_frame_or_a_refusal(self):
        self.assertTrue(appbench.walk_decided(LOG, V))
        self.assertTrue(appbench.walk_decided(REFUSED, M))
        self.assertFalse(appbench.walk_decided(LOG[:5], V))

    def test_a_released_turn_lets_the_other_run_walk_while_this_one_plays(self):
        import subprocess
        import time
        with appbench.sender_turn() as turn:
            turn.release()
            t = time.time()
            other = subprocess.run([sys.executable, "-c",
                "import fcntl\n"
                f"fh=open({appbench.SENDER_LOCK!r},'a'); fcntl.flock(fh, fcntl.LOCK_EX); print('walked')"],
                capture_output=True, timeout=10)
            self.assertEqual(other.stdout.strip(), b"walked")
            self.assertLess(time.time() - t, 1.0)

    def test_only_youtubes_bot_check_counts(self):
        self.assertIn("signal=explicit", appbench.bot_signal(LOG + WALL))
        self.assertIsNone(appbench.bot_signal(LOG + PRIVATE))
        self.assertIsNone(appbench.bot_signal(REFUSED + AUTOPLAY))

    def test_a_host_stop_stops_every_run(self):
        self.assertIsNone(appbench.host_stopped())
        appbench.stop_host("v17-mi8-cat X: bot-check walk-on client=IOS signal=explicit")
        self.assertIn("test-serial", appbench.host_stopped())

        class Args:
            min_battery = 8
        r = appbench.run_cell(Args, "RING", V, 1, None)
        self.assertTrue(r["stop"].startswith("host STOP"), r)

E = "e_04ZrNroTo"
# MWEB on the Pixel's LTE (2026-09-28, app1b-lte-mweb): ~60 s of media, then a 403 at every reload
# until the auto-reload cap: the "plays a minute, then Unknown source error" shape.
MINUTE = f"""\
09-28 21:46:40.649 D/NetPath (29455): ep=1 video={E} tap
09-28 21:46:41.236 D/NetPath (29455): web-pot-session new reason=initial visitorSource=app visitor=ae192e7163 prevAgeMs=-1 buildMs=541 binding=streaming:visitor,player:video generator=PoTokenWebView
09-28 21:46:41.276 D/NetPath (29455): player-http[S] rid=1 video={E} client=2 cver=2.20260708.05.00 visitor=ae192e7163 pot=y auth=n
09-28 21:46:41.883 D/NetPath (29455): player-result video={E} client=MWEB attempt=1 status=OK playable=y auth=n srvAuth=n formats=49+1 usableAdaptive=49 dash=n hls=n sabr=y reason="null"
09-28 21:46:42.110 D/NetPath (29455): player-transform video={E} client=MWEB ms=226
09-28 21:46:42.116 D/NetPath (29455): ep=1 video={E} info +1467 dash=49 hls=n sabr=n live=n
09-28 21:46:42.503 D/NetPath (29455): ep=1 video={E} first-frame +1854
09-28 21:46:50.708 D/NetPath (29455): bench-tick video={E} pos=15871 dur=229000 buf=44129 state=READY playing=y t=268165204
09-28 21:47:00.710 D/NetPath (29455): bench-tick video={E} pos=25868 dur=229000 buf=34132 state=READY playing=y t=268175206
09-28 21:47:10.709 D/NetPath (29455): bench-tick video={E} pos=35873 dur=229000 buf=24127 state=READY playing=y t=268185205
09-28 21:47:20.710 D/NetPath (29455): bench-tick video={E} pos=45870 dur=229000 buf=14130 state=READY playing=y t=268195206
09-28 21:47:30.710 D/NetPath (29455): bench-tick video={E} pos=55866 dur=229000 buf=4134 state=READY playing=y t=268205206
09-28 21:47:34.792 W/NetPath (29455): ep=1 video={E} error +54143 ExoPlaybackException: Source error causes=ExoPlaybackException(Source error)<-InvalidResponseCodeException(http=403)
09-28 21:47:34.795 D/NetPath (29455): ep=1 video={E} recovery-error type=0 renderer=-1 pos=59921 duration=229000 net=cell:172
09-28 21:47:34.799 D/NetPath (29455): ep=1 video={E} recovery-source http403=y freshUrls=y subtitles=off
09-28 21:47:34.799 D/NetPath (29455): ep=1 video={E} recovery-action action=remint-reload attempt=1 samePos=1
09-28 21:47:35.455 D/NetPath (29455): player-http[S] rid=2 video={E} client=2 cver=2.20260708.05.00 visitor=ae192e7163 pot=y auth=n
09-28 21:47:36.195 D/NetPath (29455): player-transform video={E} client=MWEB ms=242
09-28 21:47:36.260 D/NetPath (29455): ep=2 video={E} resume-seek target=59921 armed cacheMB=384
09-28 21:47:36.512 D/NetPath (29455): ep=2 video={E} first-frame +1612
09-28 21:47:40.716 D/NetPath (29455): bench-tick video={E} pos=58495 dur=229000 buf=1505 state=READY playing=y t=268215212
09-28 21:47:45.578 W/NetPath (29455): ep=4 video={E} error +3217 ExoPlaybackException: Source error causes=ExoPlaybackException(Source error)<-InvalidResponseCodeException(http=403)
09-28 21:47:45.581 D/NetPath (29455): ep=4 video={E} recovery-error type=0 renderer=-1 pos=59994 duration=229000 net=cell:172
09-28 21:47:45.582 W/NetPath (29455): auto-reload cap hit (consecutive=1 samePos=4 at 59994ms) for {E} — stopping; last error: Response code: 403
09-28 21:47:45.585 D/NetPath (29455): ep=4 video={E} recovery-capped connectivity=n net=cell:172
09-28 21:47:50.717 D/NetPath (29455): bench-tick video={E} pos=59994 dur=229000 buf=0 state=IDLE playing=n t=268225213
09-28 21:48:00.718 D/NetPath (29455): bench-tick video={E} pos=59994 dur=229000 buf=0 state=IDLE playing=n t=268235214
""".splitlines()


class SoakTest(unittest.TestCase):
    def test_a_minute_then_the_cap(self):
        res = appbench.parse(MINUTE, E)
        self.assertEqual(res["verdict"], "FAIL@59s")  # the old verdict, unchanged
        self.assertTrue(res["auto_reload_cap"])
        self.assertEqual(res["start_pos_ms"], 0)  # the reload's resume-seek is not the start
        self.assertEqual((res["continuous_ms"], res["first_stop_pos_ms"]), (59921, 59921))
        self.assertEqual(res["routes"], ["MWEB"])
        self.assertEqual(len(res["recoveries"]), 2)
        first, last = res["recoveries"]
        self.assertEqual((first["pos_ms"], first["http403"], first["action"], first["before"], first["after"]),
                         (59921, True, "remint-reload", "MWEB", "MWEB"))
        self.assertTrue(last["capped"])
        self.assertEqual(res["stall_ticks"], 3)  # 55.9->58.5->60.0->60.0 s
        self.assertEqual((res["pot_generators"], res["player_pot_requests"]), (["PoTokenWebView"], 2))
        self.assertEqual(res["visitor"], "ae192e7163")
        self.assertEqual(res["wall_media_ms"], 55866 + 4134)  # the last tick's pos + buf

    def test_a_resumed_clean_play_and_an_end(self):
        lines = [f"09-29 00:27:35.835 D/NetPath (1): ep=1 video={V} tap",
                 f"09-29 00:27:36.000 D/NetPath (1): ep=1 video={V} resume-seek target=77040 armed cacheMB=510",
                 f"09-29 00:27:36.500 D/NetPath (1): ep=1 video={V} first-frame +665"]
        lines += [f"09-29 00:28:{10 + i:02d}.000 D/NetPath (1): bench-tick video={V} pos={p} dur=213000 "
                  f"buf=5000 state={s} playing={'y' if s == 'READY' else 'n'} t={10_000 * i}"
                  for i, (p, s) in enumerate([(85000, "READY"), (95000, "READY"), (105000, "BUFFERING"),
                                              (113000, "READY"), (213000, "ENDED"), (213000, "ENDED")])]
        res = appbench.parse(lines, V)
        self.assertEqual(res["start_pos_ms"], 77040)
        self.assertEqual(res["stall_ticks"], 0)  # 8 s in 10 s is not a stall; ENDED never is
        self.assertEqual((res["buffering_ticks"], res["paused_ticks"]), (1, 0))
        # 7960 before the first tick + 28000 of ticks; the jump to the end is not played time.
        self.assertEqual(res["continuous_ms"], 85000 - 77040 + 28000)
        self.assertIsNone(res["first_stop_pos_ms"])
        self.assertEqual((res["played_ms"], res["jumps"]), (28000, 1))


class InCallTest(unittest.TestCase):
    """The owner's in-call rule: the active output's media index goes to 0 and comes back."""

    AUDIO = ("- STREAM_RING:\n   Devices: speaker(2)\n- STREAM_MUSIC:\n   Muted: false\n   streamVolume:15\n"
             "   Current: 2 (speaker): 0, 80 (bt_a2dp): 15\n   Devices: bt_a2dp(80)\n- STREAM_ALARM:\n")

    def setUp(self):
        self.real = appbench.shell
        self.vol = {"bt_a2dp(80)": 15}
        self.calls = "    mCallState=0\n    mCallState=2\n"

        def fake(cmd, timeout=60):
            if cmd == "dumpsys audio":
                return self.AUDIO
            if cmd.startswith("dumpsys telephony.registry"):
                return self.calls
            if "--get" in cmd:
                return f"[V] volume is {self.vol['bt_a2dp(80)']} in range [0..25]"
            if "--set" in cmd:
                self.vol["bt_a2dp(80)"] = int(cmd.split()[-1])
            return ""
        appbench.shell = fake
        appbench.ZEROED.clear()
        self.sleep = appbench.time.sleep
        appbench.time.sleep = lambda s: None

    def tearDown(self):
        appbench.shell = self.real
        appbench.time.sleep = self.sleep
        appbench.ZEROED.clear()

    def test_parsing(self):
        self.assertEqual(appbench.audio_device(), "bt_a2dp(80)")  # the music stream's, not the ring's
        self.assertTrue(appbench.call_active())
        self.calls = "    mCallState=0\n"
        self.assertFalse(appbench.call_active())
        self.calls = ""
        self.assertIsNone(appbench.call_active())  # an empty read is not "no call"

    def test_zero_remembers_the_prior_index(self):
        self.assertTrue(appbench.zero_media_volume())
        self.assertEqual(self.vol["bt_a2dp(80)"], 0)
        self.assertEqual(appbench.ZEROED, {"bt_a2dp(80)": 15})
        appbench.zero_media_volume()  # already 0: the remembered index stays the owner's
        self.assertEqual(appbench.ZEROED, {"bt_a2dp(80)": 15})


if __name__ == "__main__":
    unittest.main()
