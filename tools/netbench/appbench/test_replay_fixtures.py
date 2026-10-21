#!/usr/bin/env python3
"""replay_fixtures on fixed logs: the answers and outcome of each walk, the process boundaries, the
debug playability line, and that nothing private leaves the log.

Offline: python3 tools/netbench/appbench/test_replay_fixtures.py (no adb, no device).
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import replay_fixtures  # noqa: E402

K = "_WB5hh7WOb4"
A = "qkO6iBwcoe4"
# A kids open as the Pixel logged it (2026-09-29, v16): VISIONOS refused, TV_TIZEN (no account)
# served, the player hit a media 403 and the recovery walk found WEB_EMBED; then the next video's
# preload; then a walk the cell cut short. Plus another app's NetPath lines (pid 999), which are not
# this app's and must be ignored, and the lines that carry what must never leave the log.
KIDS = f"""\
09-29 09:56:40.300 D/NetPath (  999): player-result video={K} client=WEB attempt=1 status=OK playable=y auth=y srvAuth=y formats=1+0 usableAdaptive=1 dash=n hls=n sabr=n reason="null"
09-29 09:56:40.517 D/NetPath (27774): player-ring plan video={K} lane=signed-out order=[VISIONOS, WEB_EMBED, ANDROID_VR, IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI]
09-29 09:56:40.523 D/NetPath (27774): ep=1 video={K} tap
09-29 09:56:40.576 D/NetPath (27774): ep=1 video={K} open +53 "Cinci Maimutele"
09-29 09:56:40.600 D/NetPath (27774): player-context video={K} client=VISIONOS cver=1.02 visitorSource=web-pot visitor=ae192e7163 visitorAgeMs=-1 playerPot=n
09-29 09:56:40.700 D/NetPath (27774): player-http[S] rid=1 video={K} client=101 cver=1.02 visitor=ae192e7163 pot=n auth=n cookie=n net=cell:191
09-29 09:56:40.947 D/NetPath (27774): player-result video={K} client=VISIONOS attempt=1 status=UNPLAYABLE playable=n auth=n srvAuth=? formats=0+0 usableAdaptive=0 dash=n hls=n sabr=n reason="Este vídeo no está disponible • Más en https://support.google.com/x?id=1"
09-29 09:56:40.948 D/NetPath (27774): player-ring anon-tizen next after=VISIONOS attempt=1 reason=unplayable
09-29 09:56:41.108 D/NetPath (27774): player-result video={K} client=TV_TIZEN attempt=2 status=OK playable=y auth=n srvAuth=n formats=26+1 usableAdaptive=26 dash=n hls=n sabr=n reason="null"
09-29 09:56:41.342 D/NetPath (27774): player-transform video={K} client=TV_TIZEN ms=233
09-29 09:56:41.416 W/NetPath (27774): ep=1 video={K} error +892 ExoPlaybackException: Source error
09-29 09:56:41.419 W/NetPath (27774): player-ring account-route failed client=TV_TIZEN reason=media-403 network=cell:191 scope=video ttlMs=1800000
09-29 09:56:41.419 D/NetPath (27774): ep=1 video={K} recovery-source http403=y freshUrls=y subtitles=off
09-29 09:56:41.528 D/NetPath (27774): player-ring plan video={K} lane=signed-out suspect=TV_TIZEN order=[VISIONOS, WEB_EMBED, ANDROID_VR, IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI, TV_TIZEN]
09-29 09:56:41.614 D/NetPath (27774): player-result video={K} client=VISIONOS attempt=1 status=UNPLAYABLE playable=n auth=n srvAuth=? formats=0+0 usableAdaptive=0 dash=n hls=n sabr=n reason="Este vídeo no está disponible"
09-29 09:56:42.176 D/NetPath (27774): player-result video={K} client=WEB_EMBED attempt=2 status=OK playable=y auth=n srvAuth=n formats=26+1 usableAdaptive=26 dash=n hls=y sabr=y reason="null"
09-29 09:56:42.388 D/NetPath (27774): player-transform video={K} client=WEB_EMBED ms=213
09-29 09:58:40.000 D/NetPath (27774): player-ring plan video=TfOzK0rn0zI lane=signed-out order=[VISIONOS, WEB_EMBED, ANDROID_VR, IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI]
09-29 09:58:40.200 D/NetPath (27774): player-result video=TfOzK0rn0zI client=VISIONOS attempt=1 status=OK playable=y auth=n srvAuth=? formats=24+0 usableAdaptive=24 dash=n hls=y sabr=y reason="null"
09-29 09:58:40.210 D/NetPath (27774): player-transform video=TfOzK0rn0zI client=VISIONOS ms=3
09-29 09:58:40.211 D/NetPath (27774): player-ring speculative video=TfOzK0rn0zI client=VISIONOS routing=kept
09-29 09:59:00.000 D/NetPath (27774): player-ring plan video=dQw4w9WgXcQ lane=signed-out order=[VISIONOS, WEB_EMBED, ANDROID_VR, IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI]
""".splitlines()

# An 18+ open that is not embeddable, from a debug build that logs the exact playability line (the
# line is PlayabilityLogTest.anAgeGate's expected output, MediaServiceCore): the age gate is read,
# not guessed.
ADULT = f"""\
09-29 09:44:14.302 D/NetPath (23070): player-ring plan video={A} lane=signed-out order=[VISIONOS, WEB_EMBED, ANDROID_VR, IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI]
09-29 09:44:14.654 D/NetPath (23070): player-result video={A} client=VISIONOS attempt=1 status=LOGIN_REQUIRED playable=n auth=n srvAuth=? formats=0+0 usableAdaptive=0 dash=n hls=n sabr=n reason="Inicia sesión y confirma tu edad • Puede que este vídeo no sea adecuado para algunos usuarios. https://support.google.com/youtube/answer/2802167 "más""
09-29 09:44:14.655 D/NetPath (23070): player-playability video={A} client=VISIONOS attempt=1 {{"status":"LOGIN_REQUIRED","reason":"Inicia sesión y confirma tu edad","subreason":"Puede que este vídeo no sea adecuado para algunos usuarios. <url> \\"más\\"","desktopLegacyAgeGateReason":1,"live":false,"liveContent":false,"liveStart":false,"trailer":false,"cut":false}} channel=none
09-29 09:44:14.988 D/NetPath (23070): player-result video={A} client=WEB_EMBED attempt=2 status=UNPLAYABLE playable=n auth=n srvAuth=n formats=0+0 usableAdaptive=0 dash=n hls=n sabr=n reason="Este contenido tiene restricción de edad"
09-29 09:44:14.990 D/NetPath (23070): player-ring age-gate-settled video={A} refused=[WEB_EMBED, VISIONOS] attempts=2 skipped=6
09-29 09:44:14.990 D/NetPath (23070): player-transform video={A} client=VISIONOS ms=0
""".splitlines()


def write(directory, name, lines):
    path = os.path.join(directory, name)
    with open(path, "w") as fh:
        fh.write("\n".join(lines) + "\n\x00")  # logcat dumps carry NUL bytes
    return path


class ReplayFixturesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        write(self.dir, f"v16-lte-recovery.RING.{K}.t1.log", KIDS)
        write(self.dir, f"v17-wifi-cat.RING.{A}.t1.log", ADULT)
        with open(os.path.join(self.dir, "v16-lte-recovery.jsonl"), "w") as fh:
            row = {"network": "lte", "video": K, "trial": 1, "anon_tizen": False, "support_xhr": "none",
                   "props": {"debug.arc.poison_once_itag": "any", "not.a.switch": "x",
                             "debug.arc.player_client": "a b; rm"}}
            fh.write(json.dumps(dict(row, network="wifi")) + "\n")  # an earlier round of the same id
            fh.write(json.dumps(row) + "\n")

    def tearDown(self):
        self.tmp.cleanup()

    def case(self, *logs):
        return replay_fixtures.build_case(self.dir, "c", list(logs))

    def test_walks_answers_and_outcomes(self):
        (open_,) = self.case(f"v16-lte-recovery.RING.{K}.t1.log")["opens"]
        self.assertEqual((open_["run"], open_["build"], open_["video"], open_["trial"], open_["network"],
                          open_["lane"], open_["newProcess"]),
                         ("v16-lte-recovery", "v16", K, 1, "lte", "signed-out", True))
        # The switches the app ran with, from the run's last row for this open; only debug.arc.* keys
        # with plain values.
        self.assertEqual(open_["props"], {"debug.arc.poison_once_itag": "any"})
        types = [s["type"] for s in open_["steps"]]
        self.assertEqual(types, ["walk", "media-failure", "walk", "walk"])
        first, failure, recovery, preload = open_["steps"]
        self.assertEqual(first["expect"], {
            "asked": ["VISIONOS", "TV_TIZEN"], "result": "playable", "client": "TV_TIZEN",
            "botCheckTrip": False, "markers": ["anon-tizen next after=VISIONOS"]})
        visionos = first["answers"][0]
        self.assertEqual((visionos["status"], visionos["playable"], visionos["auth"], visionos["srvAuth"],
                          visionos["adaptive"], visionos["dash"]),
                         ("UNPLAYABLE", False, False, None, 0, False))
        tizen = first["answers"][1]
        self.assertEqual((tizen["adaptive"], tizen["regular"], tizen["usableAdaptive"], tizen["srvAuth"],
                          tizen["reason"]), (26, 1, 26, False, None))
        self.assertEqual(visionos["atMs"], 430)  # from the case's first line of this app: 40.517
        self.assertEqual(failure, {"type": "media-failure", "atMs": 902, "video": K, "http403": True,
                                   # v22: the media 403's request starts (none logged in this sample)
                                   "media403": {"forbiddenStartMs": -1, "lowestServedStartMs": -1,
                                                "highestServedStartMs": -1, "exact": False}})
        self.assertEqual((recovery["suspect"], recovery["expect"]["asked"], recovery["expect"]["client"]),
                         ("TV_TIZEN", ["VISIONOS", "WEB_EMBED"], "WEB_EMBED"))
        self.assertEqual((preload["video"], preload["role"]), ("TfOzK0rn0zI", "speculative"))
        self.assertEqual(open_["markers"], ["account-route failed client=TV_TIZEN reason=media-403 scope=video"])
        self.assertEqual(open_["droppedWalks"], ["dQw4w9WgXcQ: the cell ended mid-walk"])

    def test_another_apps_lines_are_ignored(self):
        (open_,) = self.case(f"v16-lte-recovery.RING.{K}.t1.log")["opens"]
        self.assertNotIn("WEB", open_["steps"][0]["expect"]["asked"])
        self.assertEqual(open_["atMs"], 0)

    def test_nothing_private_leaves_the_log(self):
        text = json.dumps(self.case(f"v16-lte-recovery.RING.{K}.t1.log"), ensure_ascii=False)
        for secret in ("ae192e7163", "visitor", "cell:191", "net=", "Cinci", "https:", "27774", "rid="):
            self.assertNotIn(secret, text)
        self.assertIn("Este vídeo no está disponible • Más en <url>", text)

    def test_age_gates(self):
        (open_,) = self.case(f"v17-wifi-cat.RING.{A}.t1.log")["opens"]
        (walk,) = open_["steps"]
        self.assertEqual(walk["expect"]["asked"], ["VISIONOS", "WEB_EMBED"])
        self.assertEqual((walk["expect"]["result"], walk["expect"]["client"]), ("unplayable", "VISIONOS"))
        visionos, embed = walk["answers"]
        # The exact line: YouTube's own split and marker, the logged reason kept as logged.
        self.assertEqual((visionos["exact"], visionos["ageGate"], visionos["ageGateFrom"]), (True, True, "logged"))
        self.assertEqual(visionos["exactReason"], "Inicia sesión y confirma tu edad")
        self.assertTrue(visionos["exactSubreason"].startswith("Puede que este vídeo"))
        self.assertTrue(visionos["reason"].startswith("Inicia sesión y confirma tu edad • "))
        self.assertEqual((embed["exact"], embed["ageGate"]), (False, False))  # an embed refusal, not a gate
        # Builds before router v20 end the line with the JSON (no channel tag): read the same.
        old = [ln.replace("}} channel=none", "}}").replace("} channel=none", "}") for ln in ADULT]
        write(self.dir, f"v17-wifi-old.RING.{A}.t1.log", old)
        (walk,) = self.case(f"v17-wifi-old.RING.{A}.t1.log")["opens"][0]["steps"]
        self.assertEqual((walk["answers"][0]["exact"], walk["answers"][0]["ageGateFrom"]), (True, "logged"))

    def test_age_gate_without_the_debug_line(self):
        lines = [ln for ln in ADULT if "player-playability" not in ln]
        write(self.dir, f"v16-lte-cat.RING.{A}.t1.log", lines)
        (walk,) = self.case(f"v16-lte-cat.RING.{A}.t1.log")["opens"][0]["steps"]
        self.assertEqual((walk["answers"][0]["ageGate"], walk["answers"][0]["ageGateFrom"]), (True, "reason-text"))
        # An unknown wording: the settled walk returned it, so it was the gate.
        other = [ln.replace("Inicia sesión y confirma tu edad", "Melde dich an") for ln in lines]
        write(self.dir, f"v16-lte-de.RING.{A}.t1.log", other)
        (walk,) = self.case(f"v16-lte-de.RING.{A}.t1.log")["opens"][0]["steps"]
        self.assertEqual((walk["answers"][0]["ageGate"], walk["answers"][0]["ageGateFrom"]), (True, "settled-marker"))

    def test_one_process_or_a_new_one(self):
        same = [ln.replace("(23070)", "(27774)").replace("09:44:", "10:04:") for ln in ADULT]
        write(self.dir, f"v16-lte-recovery.RING.{A}.t2.log", same)
        opens = self.case(f"v16-lte-recovery.RING.{K}.t1.log", f"v16-lte-recovery.RING.{A}.t2.log")["opens"]
        self.assertEqual([o["newProcess"] for o in opens], [True, False])
        self.assertEqual(opens[1]["atMs"], (10 * 60 + 4) * 60_000 + 14_302 - ((9 * 60 + 56) * 60_000 + 40_517))
        opens = self.case(f"v16-lte-recovery.RING.{K}.t1.log", f"v17-wifi-cat.RING.{A}.t1.log")["opens"]
        self.assertEqual([o["newProcess"] for o in opens], [True, True])

    def test_a_restart_inside_the_cell(self):
        # The app died after the open and a new process (pid 23071) restored a saved book.
        lines = ADULT + [
            "09-29 09:44:30.000 D/NetPath (23071): player-ring botwall restore records=2 network=pending",
            f"09-29 09:44:30.100 D/NetPath (23071): player-ring plan video={A} lane=signed-out order=[VISIONOS]",
            f"09-29 09:44:30.200 D/NetPath (23071): ep=1 video={A} open +0 \"x\"",
        ]
        write(self.dir, f"v17-wifi-crash.RING.{A}.t1.log", lines)
        (open_,) = self.case(f"v17-wifi-crash.RING.{A}.t1.log")["opens"]
        self.assertIsNone(open_["restoredRecords"])  # the first process had no saved book
        self.assertEqual([s["type"] for s in open_["steps"]], ["walk", "restart"])
        self.assertEqual(open_["steps"][1]["restoredRecords"], 2)
        self.assertEqual(open_["droppedWalks"], [f"{A}: the cell ended mid-walk"])

    def test_timeouts_and_cut_reasons(self):
        long_reason = "Este vídeo se ha retirado por infringir la política de YouTube sobre la incitación " \
                      "al odio. Obtén más información sobre cómo combatir la incitación al odio en t…"
        self.assertEqual(len(long_reason), 161)  # safeLogValue: 160 characters and an ellipsis
        lines = [
            f"09-29 10:00:00.000 D/NetPath (4242): player-ring plan video={A} lane=signed-out order=[VISIONOS, WEB_EMBED]",
            f"09-29 10:00:07.000 W/NetPath (4242): player-ring attempt-timeout client=VISIONOS video={A} ms=7000 clamped=n",
            f"09-29 10:00:07.001 W/NetPath (4242): player-result video={A} client=VISIONOS attempt=1 parsed=null",
            f"09-29 10:00:08.000 W/NetPath (4242): player-result video={A} client=WEB_EMBED attempt=2 parsed=null",
            f"09-29 10:00:08.100 D/NetPath (4242): player-result video={A} client=IOS attempt=3 status=ERROR playable=n auth=n srvAuth=n formats=0+0 usableAdaptive=0 dash=n hls=n sabr=n reason=\"{long_reason}\"",
            f"09-29 10:00:08.200 D/NetPath (4242): player-transform video={A} client=IOS ms=0",
        ]
        write(self.dir, f"v17-lte-slow.RING.{A}.t1.log", lines)
        (walk,) = self.case(f"v17-lte-slow.RING.{A}.t1.log")["opens"][0]["steps"]
        timeout, error, removal = walk["answers"]
        self.assertEqual((timeout["parsed"], timeout["noResponse"]), (False, True))
        self.assertEqual((error["parsed"], error["noResponse"]), (False, False))  # not a logged timeout
        self.assertEqual((removal["reasonCut"], walk["answers"][2]["reason"]), (True, long_reason))
        self.assertEqual(walk["expect"]["asked"], ["VISIONOS", "WEB_EMBED", "IOS"])

    def test_manifest_and_exclusions(self):
        manifest = os.path.join(self.dir, "seed.json")
        with open(manifest, "w") as fh:
            json.dump({"cases": [{"name": "kids", "logs": [f"v16-lte-recovery.RING.{K}.t1.log"],
                                  "priorState": "a record of another attachment"},
                                 {"name": "old", "logs": [f"v17-wifi-cat.RING.{A}.t1.log"],
                                  "exclude": "changed on purpose"}]}, fh)
        out = os.path.join(self.dir, "out.json")
        replay_fixtures.main(["--results", self.dir, "--manifest", manifest, "--out", out])
        with open(out) as fh:
            doc = json.load(fh)
        self.assertEqual(doc["schema"], replay_fixtures.SCHEMA)
        self.assertEqual([(c["name"], c["exclude"], c["priorState"]) for c in doc["cases"]],
                         [("kids", None, "a record of another attachment"), ("old", "changed on purpose", None)])

    def test_a_walk_changed_on_purpose(self):
        log = f"v16-lte-recovery.RING.{K}.t1.log"
        change = {"log": log, "walk": 2, "asked": ["WEB_EMBED"], "why": "asks what just refused it last"}
        case = replay_fixtures.build_case(self.dir, "c", [log], changed=[change])
        walks = [s for s in case["opens"][0]["steps"] if s["type"] == "walk"]
        self.assertEqual(walks[1]["expect"]["asked"], ["VISIONOS", "WEB_EMBED"])  # the device's record
        self.assertEqual(walks[1]["expect"]["changed"],
                         {"asked": ["WEB_EMBED"], "why": "asks what just refused it last"})
        self.assertNotIn("changed", walks[0]["expect"])
        # A change only drops or reorders asks the device answered, in a walk that exists, with a why.
        for bad in (dict(change, asked=["ANDROID_VR"]), dict(change, asked=[]), dict(change, walk=9),
                    dict(change, why=""), dict(change, log=f"v17-wifi-cat.RING.{A}.t1.log")):
            with self.assertRaises(ValueError):
                replay_fixtures.build_case(self.dir, "c", [log], changed=[bad])


if __name__ == "__main__":
    unittest.main()
