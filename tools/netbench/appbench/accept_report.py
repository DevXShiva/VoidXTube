#!/usr/bin/env python3
"""Acceptance report (PLANNER.md section 4): the planner+HLS arm ("on") against the same build
without them ("off"), per network, per video. Reads results/acc-<net>-*.jsonl.

  python3 accept_report.py [--data DIR] lte [wifi]   -> markdown on stdout
Results come from <data>/appbench/results (--data or NETBENCH_DATA, default: tools/netbench).
"""
import glob
import json
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = [os.path.join(os.environ.get("NETBENCH_DATA") or os.path.dirname(HERE), "appbench", "results")]


def load(run_id):
    path = os.path.join(RESULTS[0], run_id + ".jsonl")
    if not os.path.exists(path):
        return []
    return [json.loads(line) for line in open(path) if line.strip()]


def played(r):
    """Played through the window, possibly after a recovery the app made on its own."""
    return r["verdict"] == "PLAY-OK" or r["verdict"].startswith(("PARTIAL", "RECOVERED"))


def cell(r):
    """One open, compactly: verdict, /player requests, winner, first frame, readiness, flags."""
    reqs = len(r["results"])
    ff = r["first_frame_ms"]
    flags = []
    waits = [x for x in r.get("readiness", []) if x.startswith("readiness-wait")]
    retries = [x for x in r.get("readiness", []) if x.startswith("readiness-retry")]
    if waits:
        flags.append(f"wait{len(waits)}")
    if retries:
        flags.append(f"retry{len(retries)}")
    if r.get("bot_trip"):
        flags.append("BOT-TRIP")
    if r.get("bot_cooldown"):
        flags.append("BOT-COOLDOWN")
    if r.get("http403"):
        flags.append(f"403x{r['http403']}")
    if r.get("auto_reload_cap"):
        flags.append("RELOAD-CAP")
    if r.get("aborted"):
        flags.append("ABORTED")
    prep = (r.get("prepare") or {}).get("type", "-")
    return (f"{r['verdict']} req={reqs} {r['winner'] or '-'} {prep} "
            f"ff={ff / 1000:.1f}s" if ff is not None else
            f"{r['verdict']} req={reqs} {r['winner'] or '-'} {prep} ff=-") + (
            " " + ",".join(flags) if flags else "")


def section(net, name, title):
    """name "x" -> runs acc-<net>-x-on / -off; "=x" -> the single run acc-<net>-x."""
    if name.startswith("="):
        on, off = load(f"acc-{net}-{name[1:]}"), []
    else:
        on, off = load(f"acc-{net}-{name}-on"), load(f"acc-{net}-{name}-off")
    if not on and not off:
        return f"### {title}\n\n(no results)\n"
    out = [f"### {title}\n"]
    videos = []
    for r in on + off:
        if r["video"] not in videos:
            videos.append(r["video"])
    arms = [("on", on)] + ([("off", off)] if off else [])
    out.append("| video | " + " | ".join(a for a, _ in arms) + " |")
    out.append("|---|" + "---|" * len(arms))
    for v in videos:
        row = [v]
        for _, rows in arms:
            row.append("<br>".join(cell(r) for r in rows if r["video"] == v) or "-")
        out.append("| " + " | ".join(row) + " |")
    for arm, rows in arms:
        if not rows:
            continue
        ok = sum(played(r) for r in rows)
        ffs = [r["first_frame_ms"] for r in rows if r["first_frame_ms"] is not None]
        reqs = [len(r["results"]) for r in rows]
        med = f"{statistics.median(ffs) / 1000:.1f}s" if ffs else "-"
        rec = sum(r["verdict"].startswith("RECOVERED") for r in rows)
        out.append(f"\n{arm}: played {ok}/{len(rows)} ({rec} after a recovery), first frame median {med}, "
                   f"/player requests total {sum(reqs)} (max {max(reqs)})")
    return "\n".join(out) + "\n"


def blockers(net):
    """The section-4 block list, as far as the logs can say it."""
    notes = []
    for path in sorted(glob.glob(os.path.join(RESULTS[0], f"acc-{net}-*.jsonl"))):
        run = os.path.basename(path)[:-6]
        for r in load(run):
            tag = f"{run} {r['video']} t{r['trial']}"
            if r.get("bot_trip") or r.get("bot_cooldown"):
                notes.append(f"- challenge: {tag} (bot trip/cooldown)")
            if r.get("auto_reload_cap"):
                notes.append(f"- recovery beyond budget: {tag} (auto-reload cap)")
            if r.get("aborted"):
                notes.append(f"- aborted: {tag}: {r['aborted']}")
    return notes


def main():
    args = sys.argv[1:]
    if len(args) >= 2 and args[0] == "--data":
        RESULTS[0] = os.path.join(args[1], "appbench", "results")
        args = args[2:]
    nets = args or ["lte"]
    for net in nets:
        print(f"## {net.upper()}\n")
        for name, title in [("=s-plan", "smoke: planner"), ("=s-embed-hls", "smoke: WEB_EMBED + HLS"), ("kids", "kids (150 s + seek)"),
                            ("cat", "ordinary + categories (60 s)"), ("neg", "negative (30 s)"),
                            ("=readiness", "WEB_EMBED forced, readiness (60 s)"),
                            ("=seq", "kids -> ordinary, one process (60 s)"),
                            ("recovery", "injected media 403 (60 s)")]:
            print(section(net, name, title))
        notes = blockers(net)
        print("### flags\n")
        print("\n".join(notes) if notes else "none")
        print()


if __name__ == "__main__":
    main()
