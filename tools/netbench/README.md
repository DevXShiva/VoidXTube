# netbench: which /player sources serve which videos

VoidTube asks YouTube's `/player` endpoint for a video's streams through one of several client
identities ("sources": VISIONOS, TV_TIZEN, WEB_EMBED, ...). Which ones answer, and which answers
googlevideo then actually serves, depends on the video (ordinary, made for kids, age-restricted,
live, ...) and on the network (home Wi-Fi vs LTE). These tools measure that, signed out, so the
app's source order is chosen from evidence instead of guesses. The design they feed lives in
[`docs/player-sources/`](../../docs/player-sources/) (DESIGN.md, PLANNER.md, the inventories, and a
dated RECAP snapshot).

## The pieces

| Path | What it is |
|---|---|
| `harness/` | Host-side benchmark. Every request shape is a YAML *variant*; it sends `/player`, probes the media on a time axis, and can replay a cell as a 150 s player (`sustain`). Full manual: `harness/README.md`. |
| `corpus.json`, `corpus.md` | The 42-video test corpus by category, with the expected signed-out outcome and how it was checked. `harness/corpus/` holds a 3-video seed and extra fixtures. |
| `appbench/appbench.py` | In-app benchmark: drives the `.check` build on a phone over adb, forces one source per open, lets it play 150 s with a seek, and turns the app's `NetPath` log into one JSON line per open. |
| `device/guard.sh` | Safety check run before every action on the phone. Exit 0 = safe to act. |
| `pixel-lte-wrap.sh` | Runs any command with the phone on LTE (Wi-Fi off, guarded) and restores Wi-Fi afterwards. |
| `pixel-lte-run.sh` | Runs a harness sweep over the phone's LTE, through the SOCKS proxy on the phone. |
| `proxy/` | `netbench-proxy`: a tiny SOCKS5 server (Go, stdlib only) that runs on the phone, loopback only. |
| `recap/build_recap.py` | Offline: merges every harness and in-app result into `recap.json` + `RECAP.md`. |

## Rules (read before running anything)

- **Signed out by default.** No accounts, no OAuth, no cookies from disk or a browser. The harness only
  sends cookies YouTube set on its own anonymous pages in that run. The `.check` app has its own
  data and no account: never sign it in, and never test on someone's daily app or account. The one
  signed-in tool is the `.auth` build (`-PsideBySide=auth`): only on an account whose owner agreed
  to it, and it blocks every watch-history and account write (`account-write blocked`; the history
  ping logs a `history-ping dry-run` line instead).
- **yt-dlp always with `--ignore-config`** (plus `--no-cookies --no-cookies-from-browser`) when you
  run it by hand. The harness uses a yt-dlp checkout as a library only: no config, plugins off, its
  own cache under `harness/.cache/`.
- **One sender at a time.** Only one tool sends YouTube traffic from a home IP at any moment: not
  the harness and appbench together, not two harness runs. appbench runs on several phones of one
  network may overlap, like screens in a home, but never walk at once: each open holds a host-wide
  turn (`--sender-lock`, default `~/.cache/netbench/sender.lock`) until its `/player` walk is decided
  (a first frame, a refusal, an error, or 25 s), then plays while the next phone walks. **Stop at a
  bot wall** ("Sign in to confirm you're not a bot", HTTP 429). The harness exits with code 3 by
  itself; appbench writes a `STOP` file next to the lock at the first explicit bot check, trip or
  established wall on any phone, and every run stops before its next open until a person removes
  that file. Do not work around a wall; come back another day.
- **Never shorten the pre-roll wait.** No variant, axis or app switch may remove or shorten
  YouTube's enforced pre-roll ad wait. The app honours it (readiness gate); the harness measures it.
- **Keep request rates low.** The harness enforces >= 3 s between YouTube requests and >= 1 s between
  googlevideo requests, and stops at a request cap (default 400 YouTube / 1500 googlevideo). Keep runs small and spread them over
  sessions and days.
- **Device guard.** Before every intent or Wi-Fi toggle, `device/guard.sh` requires: the check app
  in focus (`start` mode also accepts the Pixel launcher or an idle VoidTube), no notification shade or
  expanded status bar, no active call, and the phone awake. While a cell plays, appbench checks the
  focus (from 6 s after the intent, as a slow phone shows the launcher while it starts); if anything else takes it (the owner picks the phone up, an emergency alert, a
  call), the cell is aborted and the whole run stops. The tools never press BACK or HOME and never
  run `logcat -c`. Media volume goes to 0 for the run and is restored (per output: each output the
  run set to 0 gets its own prior index back while it is the active one).
- **During a call (the owner's rule, 2026-09-29).** `appbench.py --allow-call` (or
  `NETBENCH_ALLOW_CALL=1`), Wi-Fi runs only, sets `GUARD_ALLOW_CALL=1` for its guard: an active call
  no longer fails `start`/`app` for opens and inputs, every other check stays. Before an open during
  a call the media stream on the active output (the earbuds when connected) must read 0, or the open
  waits. Cells that saw a call are tagged `incall` and stay out of timing stats. Network toggles
  never relax: the LTE scripts run their guards with `GUARD_ALLOW_CALL` unset, and `net` always
  requires no call.
- **Wi-Fi always comes back.** The LTE scripts re-enable Wi-Fi on exit (guarded; after 5 min of
  failed guards, as soon as a READABLE call state shows no call: an unplugged phone is not "no
  call"), check it with `cmd wifi status`, stop the command if Wi-Fi comes back mid-run, and
  leave a phone-side timer that re-enables Wi-Fi after 4 h in case the PC dies. The timer is killed
  (`kill -9` on its shell, checked with `ps`) only once Wi-Fi is confirmed on, and every run first
  kills any stale timer left by an earlier one. A failed guard
  before switching Wi-Fi off stops the script (its own exit status is checked, not the log pipe's).
- **Results stay out of the repo.** Point `NETBENCH_DATA` at a folder outside the checkout. The
  default is this directory, where results, caches and binaries are git-ignored.

## Setup

- `NETBENCH_SERIAL`: the phone's adb serial. Required by appbench, the guard and the LTE scripts
  (they refuse to run without it). An `emulator-*` serial skips the guard (your own emulator only).
- `NETBENCH_DATA`: where results go (default: `tools/netbench`). Layout: `harness/results/`,
  `appbench/results/`, `appbench/*.sh` (your run scripts), `recap/`. The recap also reads
  `corpus.json` and `harness/corpus/` from there, so copy them into a separate data folder.
- Harness: Python 3.12 with `curl_cffi` 0.15 and `PyYAML`, a yt-dlp checkout (`NETBENCH_YTDLP`,
  default `~/projects/yt-dlp`), deno (`NETBENCH_DENO`, else `deno` on PATH, else `~/.deno/bin/deno`).

## How to run

**Harness (host side, Wi-Fi).** From `tools/netbench/harness`:
```bash
./netbench list                                                   # variants and tags
./netbench run --network wifi --dry-run --variant-ids nt-WEB_EMBED --videos _WB5hh7WOb4   # no network
./netbench run --network wifi --categories made_for_kids --variant-ids 'nt-*' --run-id kids-wifi-1
./netbench sustain --from "$NETBENCH_DATA/harness/results/kids-wifi-1.jsonl" --only PLAY --network wifi
./netbench report "$NETBENCH_DATA"/harness/results/*.jsonl -o report.md
python3 -m unittest discover -s tests -v                          # offline, 127.0.0.1 only
```
Exit codes: 0 done, 1 harness bug, 2 usage error, 3 stopped (bot wall, 429, Ctrl-C), 4 request cap.

**Harness over LTE.** Build the proxy once, then let the script push it, switch Wi-Fi off, check
that the exit IP is not the home IP, and run `netbench run --network lte --proxy socks5h://127.0.0.1:18080`:
```bash
(cd proxy && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o netbench-proxy-arm64 .)
./pixel-lte-run.sh sweep-lte-1 --categories made_for_kids --variant-ids 'nt-*'
```
The proxy's own flags: `-listen` (loopback only), `-dns` (default 8.8.8.8:53), `-max-mb` (0 = no
cap), `-idle`, `-quiet`.

**In the app (appbench).** Build and install the side-by-side benchmark build:
```bash
ANDROID_HOME=<sdk> ./gradlew :smarttubetv:assembleStmobileBenchmark -PsideBySide
adb -s "$NETBENCH_SERIAL" install -r smarttubetv/build/outputs/apk/stmobile/benchmark/<apk>
```
It is release code with `BENCHMARK=true`, installed as `io.github.aleixrodriala.arc.check` next to
the normal app (the `.check` id needs release signing, `keystore.properties`; without it the build
gets `.debug`, which appbench does not drive). appbench sets these `debug.arc.*` properties, which
only debug and benchmark builds read at process start:

| Property | Set by | Effect |
|---|---|---|
| `debug.arc.player_client` | `--sources` | force one AppClient (`RING` = unset: the app's own walk) |
| `debug.arc.support_xhr` | `--support-xhr` | `true` / `false` / `absent` for every client (`none` = unset) |
| `debug.arc.anon_tizen` | `--anon-tizen` | TV_TIZEN without the account right after a refusal |
| `debug.arc.bench` | always `1` | `bench-tick` playback lines every 10 s |
| `debug.arc.bench_seek` | `--seek` (`90:0.7`) | one seek to 70% of the video after 90 s |
| `debug.arc.planner` | `--prop debug.arc.planner=1` | /player order from `PhoneSourcePlanner` |
| `debug.arc.hls_vod` | `--prop debug.arc.hls_vod=1` | HLS for VOD answers whose adaptive formats are SABR-only |
| `debug.arc.readiness` | `--prop debug.arc.readiness=0` | turn the pre-roll readiness gate off (comparison only) |
| `debug.arc.poison_once_itag` | `--prop debug.arc.poison_once_itag=any` | refuse one media request with a synthetic 403 (then the app's recovery runs) |
| `debug.arc.still_lift` | `--prop debug.arc.still_lift=ready` | the loading still lifts at READY once this open's first frame is on the texture (v17-v20); `texture`: at the next texture frame after READY (before v17). Default from v21: `frame`, at this open's first rendered frame |
| `debug.arc.embed_persist` | `--prop debug.arc.embed_persist=0` | WEB_EMBED's embed identity in memory only again (default: persisted, 6 h TTL) |
| `debug.arc.kids_channel` | `--prop debug.arc.kids_channel=0` | the kids channel memory off (default on from v20: a channel whose video VISIONOS refused and TV_TIZEN served sends its next named video to TV_TIZEN first) |
| `debug.arc.token_warmup` | `--prop debug.arc.token_warmup=frame` | the BotGuard warm-up runs after the first screen's frame again (v20); default from v21 (`open`): after the open in flight shows its first frame or fails, with the WEB enrichment that would build the WebView held with it |
| `debug.arc.embed_reroll` | `--prop debug.arc.embed_reroll=0` | a SABR-only WEB_EMBED answer keeps its embed identity (v20); default from v21: the identity is replaced off the walk, at most once per 6 h |
| `debug.arc.hls_n_fold` | `--prop debug.arc.hls_n_fold=0` | the HLS-for-VOD manifest's challenge gets its own V8 run again (v20); default from v21: it rides the bulk solve |
| `debug.arc.recovery_kids` | `--prop debug.arc.recovery_kids=0` | a kids video's recovery asks the lane's order again (v20); default from v21: TV_TIZEN and WEB_EMBED first, the sources that never serve kids videos after the suspect |
| `debug.arc.live_card` | (in-app only: `inapp-v21.sh live`) | an item that says live no longer sends ANDROID_VR first (v20); VIEW intents carry no live flag |

Every property is reset to `none` at the end. Then, from `tools/netbench`:
```bash
python3 appbench/appbench.py --network wifi --sources RING,TV_TIZEN,WEB_EMBED \
    --videos _WB5hh7WOb4,dQw4w9WgXcQ --run-id kids-wifi-app1
./pixel-lte-wrap.sh "$NETBENCH_DATA/kids-lte-app1.log" \
    python3 appbench/appbench.py --network lte --sources RING --prop debug.arc.planner=1 \
    --videos _WB5hh7WOb4,dQw4w9WgXcQ --run-id kids-lte-app1
```
Other flags: `--keep-process` (no restart between opens), `--repeat N`, `--play-s 150`,
`--serial`, `--data`, `--settle` (a decision cell: the open ends once the video played `--play-s`
seconds, 15 fits the 10 s ticks, or once the walk settled a refusal; ~20 s a cell instead of
150+), `--pm-clear` (clears the app's data before every open: a fresh install's first open; refused
for the `.auth` build), `--min-battery 8` (a phone under it stops its run), `--sender-lock PATH|none`. Each open prints a verdict (`PLAY-OK`, `PARTIAL@Ns`, `STALL@Ns`, `FAIL@Ns`,
`RECOVERED@Ns` for an error the app then played past, `NO-START`) and writes `<data>/appbench/results/<run-id>.jsonl` plus the raw log of that open. Exit
code 2 means a guard or focus stop ended the run (or a usage error). Put each sequence in a small script under
`<data>/appbench/` with a `check build vN` comment in its header: the recap reads it to label runs.

Each row also carries the open's phases, all in ms from the tap (`None` when the log has no such
line): `answer_ms`/`answer_client` (the `/player` answer that played), `sig_ms` and `v8_solve_ms`
(its n/sig solve), `mli_ms` (first media request), `init_done_ms`, `dec_video_ms`/`dec_audio_ms`
(codec init; 0 = reused), `first_frame_ms`, `ready_ms`, `picture_visible_ms` (what the user sees)
and `picture_lift` (`ready`/`texture`), the first googlevideo warm (`warm_host`, `warm_ms` = its
duration, `warm_done_ms`, and from builds that log it `warm_dns_ms`, `warm_connect_ms`,
`warm_ssl_ms`, `warm_wait_ms`, `warm_reused`, `warm_proto`), and `embed_identity`
(`restored`/`fetched`, `embed_fetch_ms`). `python3 appbench/appbench.py --reparse <log>...` prints
the same row for saved per-open logs, offline; `python3 appbench/test_appbench.py` tests
the parser.

**Walk replay (no phone, no network).** `appbench/replay_fixtures.py` turns saved per-open logs
into the fixtures of MediaServiceCore's `VideoInfoReplayTest`
(`youtubeapi/src/test/resources/walk_replay/device_walks.json`, cases listed in
`appbench/replay_seed.json`), which replays YouTube's recorded answers through the real walk and
checks the same clients in the same order and the same outcome. Run it first after a planner
change; phones then only measure time and playback. Debug and benchmark builds log a
`player-playability` line per answer so new fixtures are exact (from v20 it ends with the answer's
channel as a hash, `channel=<tag>|none`). A walk the planner now asks differently on purpose is
listed in its case's `changed` (`{log, walk, asked, why}`): the device's record stays, the replay
checks the new order and prints the why; a change can only drop or reorder asks the device answered.

**Acceptance (planner and HLS for VOD).** `appbench/accept.sh <wifi|lte> smoke|rest` runs the matrix
of `docs/player-sources/PLANNER.md` section 4, each switch on against the same build with it off (LTE
inside `pixel-lte-wrap.sh`). `python3 appbench/accept_report.py [--data DIR] lte wifi` prints it per
video, on beside off: verdict, `/player` requests, winner, delivery, first frame, readiness waits, and
flags for challenges, auto-reload caps and aborts.

**Recap.** Offline, deterministic, stdlib only:
```bash
python3 recap/build_recap.py [--data "$NETBENCH_DATA"] [--out DIR]   # default out: <data>/recap
```

**Guard by hand.** `device/guard.sh start` (or `app`, `net`) prints one line and exits 0 when it is
safe to act.
