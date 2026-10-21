#!/usr/bin/env python3
"""Walk-replay fixtures from appbench's per-open logs (netbench).

A device run already holds YouTube's real answers: every /player request of the phone's walk leaves a
`player-result` NetPath line (status, reason, playable, formats, manifests, what we sent and what the
server saw), and the walk's decisions leave theirs (the plan, `anon-tizen next`, `age-gate-settled`,
`definitive-unplayable`, `bot-check trip`, the answer's `player-transform`). This turns saved logs of
RING opens (<run>.RING.<video>.t<N>.log) into a fixtures JSON that MediaServiceCore's
VideoInfoReplayTest replays through the real walk, with no network: a planner change is checked in
seconds instead of ~80 s of phone per video.

One entry per open (per log): run, build, video, lane, network kind, the debug switches the app ran
with (appbench --prop, from the run's .jsonl), and its steps in order - every walk the app's
process ran while the cell was logged (the open, a recovery reload, the next video's preload), each
with the answers per client in the order asked and the device's outcome (the clients asked, the
answer returned and whether it played, a bot-check trip, the settle markers) - plus the player's
recovery calls between walks (a media 403). Opens are grouped into cases; a case is replayed on one
simulated device: one service per app process, a new one (keeping the persisted bot-wall book) when
the process changed, the clock advanced by the logged time between lines. What the player-result
line lacks (the age-gate marker, live signals) is taken from the walk's own lines or YouTube's
age-gate sentence and marked on the answer (ageGateFrom), unless the log has the debug build's
player-playability line, which gives it exactly (exact=true).

Privacy: only those fields leave the log. No visitor ids, cookies, tokens, URLs (a URL inside a
reason becomes <url>), network ids, titles, cpn or pids: markers are rebuilt from whitelisted tokens
(client names, counts, reason keywords), never copied.

Usage (offline, no adb):
  replay_fixtures.py --results DIR --manifest replay_seed.json --out walk-replay.json
  replay_fixtures.py --results DIR <run>.RING.<video>.t1.log ...     # one case per log, to stdout
  replay_fixtures.py --results DIR --case NAME <log> <log> ...       # the logs as one case
"""
import argparse
import datetime
import json
import os
import re
import sys
import unicodedata

SCHEMA = 1
LOG_NAME = re.compile(r"(?P<run>.+?)\.(?P<source>[A-Z_]+)\.(?P<video>[\w-]{11})\.t(?P<trial>\d+)\.log$")
LINE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) [VDIWEF]/NetPath\s*\(\s*(\d+)\):\s?(.*)$")
CLIENT = r"[A-Z][A-Z0-9_]*"
RESULT = re.compile(r"player-result video=(\S+) client=(" + CLIENT + r") attempt=(\d+) "
                    r"(?:parsed=null|status=(\S+) playable=([yn]) auth=([yn]) srvAuth=([yn?]) "
                    r"formats=(\d+)\+(\d+) usableAdaptive=(\d+) dash=([yn]) hls=([yn]) sabr=([yn]) "
                    r"reason=\"(.*)\")\s*$")
# The exact playability line (debug builds with MediaServiceCore's PlayabilityLog on): see exact().
# From router v20 the line ends with the answer's channel tag (channel=<tag>|none), outside the JSON.
PLAYABILITY = re.compile(r"player-playability video=(\S+) client=(" + CLIENT + r") attempt=(\d+) (\{.*\})"
                         r"(?: channel=\S+)?\s*$")
PLAN = re.compile(r"player-ring plan video=(\S+) lane=(signed-in|signed-out)(.*?) order=\[(.*?)\]")
WALLED = re.compile(r"player-ring botwall route video=(\S+) order=\[(.*?)\] probe=([yn]).*? auth=([yn])")
TRANSFORM = re.compile(r"player-transform video=(\S+) client=(" + CLIENT + r")")
SPECULATIVE = re.compile(r"player-ring speculative video=(\S+) client=(" + CLIENT + r")")
ADOPTED = re.compile(r"player-ring speculative-adopted video=(\S+) client=(" + CLIENT + r")")
COOLDOWN = re.compile(r"bot-check cooldown video=(\S+)")
RECOVERY = re.compile(r"ep=\d+ video=(\S+) recovery-source http403=([yn]) freshUrls=([yn])(.*)")
RESTORE = re.compile(r"player-ring botwall restore records=(\d+)")
# NEWTUBE(wall-memory): what the engine saw of an episode's media requests, for the one-minute wall's
# signature. v22 logs it exactly (playback-media403); older logs give the episode's first media loads
# (media-load chunk), its served chunks (media-chunk done) and where the error stopped it.
EPISODE = re.compile(r"ep=(\d+) video=(\S+) ")
MEDIA_LOAD = re.compile(r"ep=(\d+) video=\S+ media-load chunk \+\d+ track=\S+ .*?start=(\d+)")
MEDIA_DONE = re.compile(r"ep=(\d+) video=\S+ media-chunk done \+\d+ .*?start=(\d+)")
RECOVERY_ERROR = re.compile(r"ep=(\d+) video=\S+ recovery-error .*?pos=(\d+)")
MEDIA403 = re.compile(r"playback-media403 video=(\S+) client=(" + CLIENT + r") forbiddenStartMs=(-?\d+)"
                      r" servedStartMs=(-?\d+)\.\.(-?\d+)")
VIDEO = re.compile(r"video=(\S+)")
URL = re.compile(r"https?://\S+")

# Markers: (regex on the line, template filled with its groups). Only whitelisted tokens survive.
MARKERS = [
    (re.compile(r"player-ring anon-tizen next after=(" + CLIENT + r")"), "anon-tizen next after={0}"),
    (re.compile(r"player-ring account-route next reason=([a-z-]+) after=(" + CLIENT + r")"),
     "account-route next reason={0} after={1}"),
    (re.compile(r"player-ring (" + CLIENT + r") live-no-dash, walking on"), "live-no-dash client={0}"),
    (re.compile(r"player-ring live-no-dash exhausted"), "live-no-dash exhausted"),
    (re.compile(r"player-ring age-gate-settled video=\S+ refused=\[([A-Z0-9_, ]*)\] attempts=(\d+)"),
     "age-gate-settled refused=[{0}] attempts={1}"),
    (re.compile(r"player-ring definitive-unplayable video=\S+ clients=([A-Z0-9_,]+) .*?attempts=(\d+)"),
     "definitive-unplayable clients={0} attempts={1}"),
    (re.compile(r"bot-check walk-on client=(" + CLIENT + r") signal=([a-z-]+)"),
     "bot-check walk-on client={0} signal={1}"),
    (re.compile(r"bot-check trip client=(" + CLIENT + r") signal=([a-z-]+)"), "bot-check trip client={0} signal={1}"),
    (re.compile(r"bot-check defer-until-auth-client client=(" + CLIENT + r")"),
     "bot-check defer-until-auth-client client={0}"),
    (re.compile(r"bot-check repeated-login video=\S+ unconfirmed reason=([a-z-]+)"),
     "repeated-login unconfirmed reason={0}"),
    (re.compile(r"bot-check repeated-login video=\S+ confirmed-by="), "repeated-login confirmed"),
    (re.compile(r"bot-check discounted video=\S+ reason=([a-z-]+)"), "bot-check discounted reason={0}"),
    (re.compile(r"bot-check (probe|bypass=[a-z-]+) video="), "bot-check {0}"),
    (re.compile(r"player-ring botwall (suspect|confirmed) client=(" + CLIENT + r")"), "botwall {0} client={1}"),
    (re.compile(r"player-ring botwall established .*?by=(" + CLIENT + r")"), "botwall established by={0}"),
    (re.compile(r"player-ring botwall shortcut .*?next=\[([A-Z0-9_, ]*)\]"), "botwall shortcut next=[{0}]"),
    (re.compile(r"player-ring botwall cleared reason=([a-z-]+) client=(" + CLIENT + r")"),
     "botwall cleared reason={0} client={1}"),
    (re.compile(r"player-ring anon-challenged .*?hits=(\d+)"), "anon-challenged hits={0}"),
    (re.compile(r"player-ring account-route failed client=(" + CLIENT + r") reason=([a-z0-9-]+) .*?scope=([a-z]+)"),
     "account-route failed client={0} reason={1} scope={2}"),
    (re.compile(r"player-ring budget-exhausted video=\S+ attempts=(\d+)"), "budget-exhausted attempts={0}"),
    (re.compile(r"player-ring transport-down video=\S+ attempts=(\d+)"), "transport-down attempts={0}"),
    (re.compile(r"player-ring attempt-timeout client=(" + CLIENT + r")"), "attempt-timeout client={0}"),
    (re.compile(r"player-ring winner-kept reason=live client=(" + CLIENT + r")"), "winner-kept reason=live client={0}"),
    (re.compile(r"player-request canceled video=\S+ stage=([a-z-]+)"), "canceled stage={0}"),
]

# LOGIN_REQUIRED sentences that are YouTube's age gate. The player-result line carries no
# desktopLegacyAgeGateReason (the field the walk reads, VideoInfo.isAgeGate): an answer is taken as
# an age gate when the walk settled on it (age-gate-settled names it) or when its first sentence is one
# of these, captured on the Pixel and the emulator (2026-09-28/29). The debug line exact() reads makes
# the guess unnecessary.
AGE_GATE_SENTENCES = (
    "sign in to confirm your age",
    "inicia sesion y confirma tu edad",
    "inicia sesion para confirmar tu edad",
)


def normalize(text):
    value = unicodedata.normalize("NFD", text or "")
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    return re.sub(r"\s+", " ", value).strip().lower()


def build_of(run):
    m = re.match(r"(v\d+[a-z]?)-", run)
    return m.group(1) if m else "unknown"


def epoch_ms(m, year=2000):
    mo, d, hh, mi, ss, ms = (int(m.group(i)) for i in range(1, 7))
    moment = datetime.datetime(year, mo, d, hh, mi, ss, ms * 1000, tzinfo=datetime.timezone.utc)
    return int(moment.timestamp() * 1000)


def clean_reason(text):
    if text is None or text == "null":
        return None
    return URL.sub("<url>", text)


def read_lines(path):
    with open(path, errors="replace") as fh:
        return fh.read().replace("\x00", "").splitlines()


def parse_lines(lines):
    """[(epoch_ms, pid, body)] of the NetPath lines."""
    out = []
    for raw in lines:
        m = LINE.match(raw.rstrip("\r"))
        if m:
            out.append((epoch_ms(m), m.group(7), m.group(8)))
    return out


def app_pids(parsed, video):
    """The app's process(es), in order: the pids that opened the log's video or walked for it.
    appbench filters logcat by tag only, so another app's NetPath lines (the owner's NewTube,
    another build) can be in the file and must not be read as this app's."""
    pids = []
    mine = re.compile(r"ep=\d+ video=" + re.escape(video) + r" (tap|open)\b"
                      r"|player-ring (plan|botwall route) video=" + re.escape(video) + r" ")
    for _, pid, body in parsed:
        if mine.match(body) and pid not in pids:
            pids.append(pid)
    return pids


def answer_of(m, at, timed_out=False):
    if m.group(4) is None:
        # parsed=null: asked, nothing parsed. A timeout (the walk's attempt-timeout line came first)
        # is no response at all, which the walk's transport-down stop counts; anything else (an
        # HTTP error, or a transport error the log does not name) replays as an error.
        return {"client": m.group(2), "atMs": at, "parsed": False, "auth": None, "noResponse": timed_out}
    reason = m.group(14)
    return {
        "client": m.group(2), "atMs": at, "parsed": True,
        # reason: as player-result logs it - YouTube's reason and subreason joined by " • ", cut at
        # 160 characters (reasonCut). The walk reads them joined, so this is what it classified, as
        # far as the cut goes.
        "status": m.group(4), "reason": clean_reason(reason),
        "reasonCut": len(reason) == 161 and reason.endswith("…"),
        "playable": m.group(5) == "y", "auth": m.group(6) == "y",
        "srvAuth": {"y": True, "n": False}.get(m.group(7)),
        "adaptive": int(m.group(8)), "regular": int(m.group(9)), "usableAdaptive": int(m.group(10)),
        "dash": m.group(11) == "y", "hls": m.group(12) == "y", "sabr": m.group(13) == "y",
        "live": False, "liveContent": False, "liveStart": False, "trailer": False,
        "ageGate": False, "ageGateFrom": None, "exact": False,
    }


def exact(answer, payload):
    """Merge a debug build's player-playability line (MediaServiceCore's PlayabilityLog) into its
    answer: reason and subreason as YouTube split them (bounded, not cut at 160),
    desktopLegacyAgeGateReason, the live signals and a rental's trailer."""
    try:
        data = json.loads(payload)
    except ValueError:
        return
    if not answer.get("parsed") or data.get("status") != answer.get("status"):
        return
    answer["exactReason"] = clean_reason(data.get("reason"))
    answer["exactSubreason"] = clean_reason(data.get("subreason"))
    answer["reasonCut"] = bool(data.get("cut"))  # past PlayabilityLog's 1000-character bound
    answer["ageGate"] = bool(data.get("desktopLegacyAgeGateReason"))
    answer["ageGateFrom"] = "logged"
    for key in ("live", "liveContent", "liveStart", "trailer"):
        answer[key] = bool(data.get(key))
    answer["exact"] = True


class Walk:
    def __init__(self, video, at, lane, suspect, plan, walled):
        self.video, self.at, self.lane, self.suspect, self.plan, self.walled = \
            video, at, lane, suspect, plan, walled
        self.answers, self.markers = [], []
        self.role = "active"
        self.result = None  # "playable" | "unplayable" | "none" | "cooldown"
        self.client = None
        self.trip = False
        self.done = False
        self.live = False

    def derive(self):
        """What the log does not carry but the walk showed: live answers, age gates."""
        for a in self.answers:
            if not a["parsed"] or a["exact"]:
                continue
            if self.live and a["status"] == "OK":
                a["live"] = a["liveContent"] = True
            first = normalize(a["reason"]).split(" • ")[0].rstrip(".")
            if a["status"] == "LOGIN_REQUIRED" and first in AGE_GATE_SENTENCES:
                a["ageGate"], a["ageGateFrom"] = True, "reason-text"
        if any(m.startswith("age-gate-settled") for m in self.markers):
            # The answer an age-gate-settled walk returns is its first age gate.
            for a in self.answers:
                if a["client"] == self.client and a["parsed"] and not a["exact"] and not a["ageGate"]:
                    a["ageGate"], a["ageGateFrom"] = True, "settled-marker"

    def to_json(self, base):
        self.derive()
        answers = []
        for a in self.answers:
            a = dict(a)
            a["atMs"] -= base
            answers.append(a)
        return {
            "type": "walk", "atMs": self.at - base, "video": self.video, "role": self.role,
            "lane": self.lane, "suspect": self.suspect, "walled": self.walled, "plan": self.plan,
            "answers": answers,
            # What VideoInfoBotWallTest.ShadowWalk records: the client, "+auth" with the account.
            "expect": {"asked": [a["client"] + ("+auth" if a.get("auth") else "") for a in self.answers],
                       "result": self.result, "client": self.client, "botCheckTrip": self.trip,
                       "markers": self.markers},
        }


def parse_open(path, video, base=None, prev_pid=None, network=None):
    """One per-open log -> (its open entry, the case's base time, the app's pid at its end).
    base: the case's first line (ms); prev_pid: the app's process at the end of the case's previous
    open (None: this open starts the case)."""
    name = os.path.basename(path)
    mname = LOG_NAME.match(name)
    run = mname.group("run") if mname else name
    parsed = parse_lines(read_lines(path))
    pids = app_pids(parsed, video)
    if not pids:
        raise ValueError(f"{name}: no walk lines for {video}")
    parsed = [p for p in parsed if p[1] in pids]
    base = parsed[0][0] if base is None else base
    steps, walks, current = [], {}, None
    dropped, markers = [], []
    restored = None
    pid = pids[0]
    new_process = prev_pid is None or pid != prev_pid

    def drop(walk, why):
        dropped.append(f"{walk.video}: {why}")
        if walk in steps:
            steps.remove(walk)
        if walks.get(walk.video) is walk:
            del walks[walk.video]

    restart = None
    # NEWTUBE(wall-memory): per player episode, its media request starts (see EPISODE).
    episodes = {}
    exact403 = None

    def episode(number):
        return episodes.setdefault(number, {"loads": [], "served": [], "pos": -1})

    for at, line_pid, body in parsed:
        if line_pid != pid:  # the app restarted inside the cell
            if current is not None and not current.done:
                drop(current, "the process ended mid-walk")
            restart = {"type": "restart", "atMs": at - base, "restoredRecords": None}
            steps.append(restart)
            pid, current, walks = line_pid, None, {}
        m = RESTORE.search(body)
        if m:
            # The process's bot-wall book, restored at its start. No such line: it had no saved book
            # (nothing walled or benched yet, or the app's data was cleared: appbench --pm-clear).
            if restart is not None:
                if restart["restoredRecords"] is None:
                    restart["restoredRecords"] = int(m.group(1))
            elif restored is None:
                restored = int(m.group(1))
        mp, mw = PLAN.search(body), WALLED.search(body)
        if mp or mw:
            if current is not None and not current.done:
                drop(current, "no outcome before the next walk")
            if mp:
                suspect = re.search(r"suspect=(" + CLIENT + r")", mp.group(3))
                current = Walk(mp.group(1), at, mp.group(2), suspect.group(1) if suspect else None,
                               [c.strip() for c in mp.group(4).split(",") if c.strip()], False)
            else:
                current = Walk(mw.group(1), at, "signed-in" if mw.group(4) == "y" else "signed-out",
                               None, [c.strip() for c in mw.group(2).split(",") if c.strip()], True)
            walks[current.video] = current
            steps.append(current)
            continue
        m = COOLDOWN.search(body)
        if m:  # answered from the bot-check circuit, before any walk
            walk = Walk(m.group(1), at, None, None, [], False)
            walk.result, walk.done = "cooldown", True
            steps.append(walk)
            continue
        m = RESULT.search(body)
        if m:
            walk = walks.get(m.group(1))
            if walk is None or walk.done:
                dropped.append(f"{m.group(1)}: an answer outside a logged walk (the log began mid-walk)")
            else:
                timed_out = bool(walk.markers) and walk.markers[-1] == "attempt-timeout client=" + m.group(2)
                walk.answers.append(answer_of(m, at, timed_out))
            continue
        m = PLAYABILITY.search(body)
        if m:
            walk = walks.get(m.group(1))
            if walk is not None and walk.answers and walk.answers[-1]["client"] == m.group(2):
                exact(walk.answers[-1], m.group(4))
            continue
        m = TRANSFORM.search(body)
        if m and m.group(1) in walks and not walks[m.group(1)].done:
            walk = walks[m.group(1)]
            walk.client = m.group(2)
            answer = next((a for a in reversed(walk.answers) if a["client"] == walk.client), None)
            walk.result = "playable" if answer is not None and answer.get("playable") else "unplayable"
            walk.done = True
            continue
        m = SPECULATIVE.search(body)
        if m and m.group(1) in walks:
            walks[m.group(1)].role = "speculative"
            continue
        m = ADOPTED.search(body)
        if m:
            steps.append({"type": "adopt", "atMs": at - base, "video": m.group(1)})
            continue
        m = MEDIA_LOAD.search(body)
        if m:
            episode(m.group(1))["loads"].append(int(m.group(2)))
        m = MEDIA_DONE.search(body)
        if m:
            episode(m.group(1))["served"].append(int(m.group(2)))
        m = RECOVERY_ERROR.search(body)
        if m:
            episode(m.group(1))["pos"] = int(m.group(2))
        m = MEDIA403.search(body)
        if m:
            exact403 = {"forbiddenStartMs": int(m.group(3)), "lowestServedStartMs": int(m.group(4)),
                        "highestServedStartMs": int(m.group(5)), "exact": True}
            continue
        m = RECOVERY.search(body)
        if m:
            # ErrorFixerController: anchorRouteToVideo, then markCurrentPlaybackRouteForbidden and
            # notePlaybackMedia403 on a media 403, then applyNoPlaybackFix (switchNextFormat). A
            # transport-only blame just re-mints the URLs and calls none of them.
            if "blame=transport" not in m.group(4):
                failure = {"type": "media-failure", "atMs": at - base, "video": m.group(1),
                           "http403": m.group(2) == "y"}
                if failure["http403"]:
                    failure["media403"] = exact403 or media403_of(episodes.get(
                        (EPISODE.search(body) or [None, None])[1]))
                exact403 = None
                steps.append(failure)
            continue
        for regex, template in MARKERS:
            mm = regex.search(body)
            if not mm:
                continue
            marker = template.format(*mm.groups())
            vid = VIDEO.search(body)
            target = walks.get(vid.group(1)) if vid else current
            # winner-kept follows the transform of its walk; anything else logged between walks
            # (the player's route failure) belongs to the open.
            if target is None or (target.done and not marker.startswith("winner-kept")):
                markers.append(marker)
                break
            target.markers.append(marker)
            if marker.startswith("bot-check trip"):
                target.trip = True
            if marker.startswith(("live-no-dash client", "winner-kept reason=live")):
                target.live = True
            if marker.startswith(("budget-exhausted", "transport-down")) and not target.done:
                target.result, target.done = "none", True
            if marker.startswith("canceled"):
                drop(target, marker + ": a replay has no cancellation to match it")
                if target is current:
                    current = None
            break
    for step in list(steps):
        if isinstance(step, Walk) and not step.done:
            drop(step, "the cell ended mid-walk")
    lanes = sorted({s.lane for s in steps if isinstance(s, Walk) and s.lane})
    if network is None:
        nets = re.findall(r"net=(cell|wifi):", "\n".join(b for _, _, b in parsed))
        network = ("lte" if nets[0] == "cell" else "wifi") if nets else None
    return {
        "log": name, "run": run, "build": build_of(run), "video": video,
        "trial": int(mname.group("trial")) if mname else None,
        "network": network, "lane": lanes[0] if len(lanes) == 1 else (lanes or None),
        "newProcess": new_process, "restoredRecords": restored, "atMs": parsed[0][0] - base,
        "markers": markers, "droppedWalks": dropped,
        "steps": [s.to_json(base) if isinstance(s, Walk) else s for s in steps],
    }, base, pid


def media403_of(ep):
    """An older log's media 403, rebuilt for notePlaybackMedia403: the refused request started at the
    later of where the error stopped the player and the episode's last logged media load (the first
    loads and those after a resume are logged, the rest are not); served: the episode's done chunks.
    -1 where the log says nothing."""
    if not ep:
        return {"forbiddenStartMs": -1, "lowestServedStartMs": -1, "highestServedStartMs": -1,
                "exact": False}
    loads = ep["loads"]
    return {"forbiddenStartMs": max([ep["pos"]] + loads[-1:]),
            "lowestServedStartMs": min(ep["served"]) if ep["served"] else -1,
            "highestServedStartMs": max(ep["served"]) if ep["served"] else -1,
            "exact": False}


PROP = re.compile(r"debug\.arc\.[a-z0-9_]+")
PROP_VALUE = re.compile(r"[A-Za-z0-9_.:-]{0,32}")


def run_row(results, run, video, trial):
    """The open's appbench row (<run>.jsonl), or {}: the network and the debug switches the app ran
    with (appbench --prop, --anon-tizen, --support-xhr), which the log itself does not say."""
    row = {}
    try:
        with open(os.path.join(results, run + ".jsonl")) as fh:
            for line in fh:
                candidate = json.loads(line)
                if candidate.get("video") == video and candidate.get("trial") == trial:
                    row = candidate  # a run re-invoked with the same id rewrites the log: the last row
    except (OSError, ValueError):
        return {}
    return row


def props_of(row):
    props = {k: str(v) for k, v in (row.get("props") or {}).items()
             if PROP.fullmatch(k) and PROP_VALUE.fullmatch(str(v))}
    if row.get("anon_tizen"):
        props["debug.arc.anon_tizen"] = "1"
    if row.get("support_xhr") not in (None, "none"):
        props["debug.arc.support_xhr"] = str(row["support_xhr"])
    return props


def build_case(results, name, logs, note=None, exclude=None, prior_state=None, changed=None):
    """The logs, in order, as one case (one simulated device). exclude: why the current code is
    expected to disagree (the case is skipped with it); prior_state: why state the device restored
    from before the case's first open (a saved bot-wall book) cannot change its walks; changed: walks
    the current code asks differently on purpose (see apply_change)."""
    opens, base, pid = [], None, None
    for log in logs:
        path = log if os.path.isabs(log) else os.path.join(results, log)
        m = LOG_NAME.match(os.path.basename(path))
        if not m or m.group("source") != "RING":
            raise ValueError(f"{log}: not a RING per-open log (<run>.RING.<video>.t<N>.log)")
        row = run_row(results, m.group("run"), m.group("video"), int(m.group("trial")))
        entry, base, pid = parse_open(path, m.group("video"), base, pid, row.get("network"))
        entry["props"] = props_of(row)
        opens.append(entry)
    for change in changed or []:
        apply_change(name, opens, change)
    builds = sorted({o["build"] for o in opens})
    return {"name": name, "build": builds[0] if len(builds) == 1 else builds, "note": note,
            "exclude": exclude, "priorState": prior_state, "opens": opens}


def apply_change(case, opens, change):
    """One walk the current code asks differently on purpose, from the manifest: {log, walk (1-based,
    the open's walks in order), asked (the clients the current walk asks), why}. The device's own
    record stays as it was; the change goes beside it (expect.changed), and the replay expects it
    instead, saying why. Every client of the new order must have a device answer in that walk: a
    change can only drop or reorder asks, never invent an answer."""
    where = f"{case}: change {change.get('log')} walk {change.get('walk')}"
    if not change.get("why"):
        raise ValueError(f"{where}: says no why")
    entry = next((o for o in opens if o["log"] == change.get("log")), None)
    if entry is None:
        raise ValueError(f"{where}: no such log in the case")
    walks = [s for s in entry["steps"] if s["type"] == "walk"]
    if not 1 <= change.get("walk", 0) <= len(walks):
        raise ValueError(f"{where}: the open has {len(walks)} walks")
    walk = walks[change["walk"] - 1]
    answered = [a["client"] + ("+auth" if a.get("auth") else "") for a in walk["answers"]]
    for client in change.get("asked") or []:
        if client not in answered:
            raise ValueError(f"{where}: no device answer for {client} (answered: {answered})")
    if not change.get("asked"):
        raise ValueError(f"{where}: asks no one")
    walk["expect"]["changed"] = {"asked": list(change["asked"]), "why": change["why"]}


def main(argv=None):
    ap = argparse.ArgumentParser(description="Walk-replay fixtures from appbench per-open logs.")
    ap.add_argument("--results", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "results"),
                    help="appbench results directory (the per-open logs and the runs' .jsonl)")
    ap.add_argument("--manifest", help="JSON {cases: [{name, logs: [...], note, exclude, priorState, changed}]}")
    ap.add_argument("--case", help="replay the logs given as ONE case with this name")
    ap.add_argument("--out", help="write here instead of stdout")
    ap.add_argument("logs", nargs="*")
    args = ap.parse_args(argv)
    cases = []
    if args.manifest:
        with open(args.manifest) as fh:
            manifest = json.load(fh)
        for c in manifest["cases"]:
            cases.append(build_case(args.results, c["name"], c["logs"], c.get("note"), c.get("exclude"),
                                    c.get("priorState"), c.get("changed")))
    if args.case:
        cases.append(build_case(args.results, args.case, args.logs))
    elif args.logs:
        for log in args.logs:
            try:
                cases.append(build_case(args.results, os.path.basename(log), [log]))
            except ValueError as e:  # an aborted cell, a forced-source log: say so, go on
                print(f"skip {e}", file=sys.stderr)
    if not cases:
        ap.error("nothing to do: give --manifest or logs")
    doc = {"schema": SCHEMA, "generator": "tools/netbench/appbench/replay_fixtures.py", "cases": cases}
    text = json.dumps(doc, ensure_ascii=False, indent=1) + "\n"
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(text)
        walks = sum(1 for c in cases for o in c["opens"] for s in o["steps"] if s["type"] == "walk")
        print(f"{args.out}: {len(cases)} cases, {sum(len(c['opens']) for c in cases)} opens, "
              f"{walks} walks", file=sys.stderr)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
