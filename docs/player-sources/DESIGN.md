> Copied from a private working folder on 2026-09-28: the tools it names (harness, appbench, recap, proxy, corpus) now live in `tools/netbench/`; the raw results and logs it cites are not in the repo.

# Player sources: from a ring of gates to a planned, measured catalog

Draft 2 2026-09-28, after a Codex astra adversarial review (novel/astra-design-review.md); §0 lists what changed. Evidence: `netbench/` (inventory-engine.md, inventory-external.md, corpus.md,
novel/observed-official.md, harness results sweep1-wifi / sweep1-lte / sustain1-wifi*).
Scope: signed-out playback on the phone first; signed-in keeps its account routes and only
gains the new fallbacks. Nothing here removes or shortens YouTube's pre-roll wait; the app
honours it.

## 0. Changes after review (draft 2)

- Evidence restated: completed coverage is Wi-Fi 3 videos (159/795 cells) + LTE 7 videos (371/795),
  one trial per cell, both runs stopped at a bot wall; corpus checked from Spain only; "sustain" is a
  paced HTTP simulator (no decoding, byte-fraction seek), and several "OK" rows ended at EOF before
  150 s. Everything below is a *candidate*, to be confirmed in-app on the device.
- Timing: "257/257" counts only deliveries that were served; 29 no-ad deliveries were refused
  outright (e.g. token-less TV). 0.8 x skip offset is a hypothesis with counterexamples (LTE rows 20,
  52 served earlier than the estimate) — the app will use the full, conservatively parsed wait.
- Readiness gate redesigned (§3.3): a too-early estimate would 403 *after* readyAt, which the draft's
  retry rule missed.
- New §3.0 failure classification; catalog gains auth policy, endpoint shape, identity binding and a
  stable versioned source id; recovery scoped to an episode; live, prefetch and warm-up rules made
  explicit; HLS/progressive carried as a *selected delivery* through loader and recovery.
- Build order changed (§4): in-app instrumentation first, behaviour-preserving catalog before any
  reordering, every behaviour change behind its own switch.

## 1. What the measurements say (2026-09-28)

Signed out, no PO token, host-side harness (curl_cffi, not the app's stack); Wi-Fi (home) on 3
videos and LTE (Movistar, through a proxy on the Pixel) on 7, 53 request shapes, one trial per cell. "Sustain" = 150 s of real-player fetching incl. a seek.

| category | first try that plays, today's ring | what serves it | sustained? |
|---|---|---|---|
| ordinary, music, 4K, Shorts, long, embed-disabled | VISIONOS (#1) | VISIONOS | yes (control) |
| made for kids (8/8 in corpus from Spain: channels from RO/US/UK/CA/ES/KR) | **nothing** (VISIONOS/ANDROID_VR "not available", web/iOS SABR-only), WEB_EMBED #10 in the working tree | **TV_TIZEN anonymous**: instant, no ads; **WEB_EMBED + embed identity**: ads -> ~0.8 x skip-offset wait, HLS too; ANDROID family: 360p progressive, instant | TV_TIZEN yes (2/2), WEB_EMBED adaptive + HLS yes (2/2), ANDROID_REEL 360p yes (1/1) |
| age-restricted, embeddable | nothing | WEB_EMBED (yt-dlp too) | not yet measured |
| live 24/7 | VISIONOS (HLS held) -> ANDROID_VR (DASH) | same | – |

Ruled out or unreliable without a token: TV_SIMPLY and WEB_MUSIC (serve, then 403 at media
~55-60 s), MWEB with supportXhr=false (URLs in one answer, SABR-only in the next), TV 7.x (403),
GEO (ERROR everywhere), TV_EMBED (no longer supported), WEB/WEB_SAFARI/IOS (SABR-only; IOS HLS for
ordinary videos only). They may change with valid PO tokens: phase B.

Timing (candidate rule): of the deliveries that were served, all 257 from answers without pre-roll
ads were served on the first probe (~0.6-2 s after /player); from ad-bearing answers, adaptive and
progressive were mostly refused for the first few seconds (refused <=3.7 s, served >=4.7 s with a 6 s
estimate; two counterexamples served earlier). The official player's SABR `backoff_time_ms` was
0.8 x yt-dlp's estimate in 2 multi-ad samples. The Pixel failure of 2026-09-28 fits this (first media
request 0.3 s after /player, then re-resolving = a new answer and a new wait, until the reload cap),
but the app's own answer at the time was not captured, so this is the leading explanation, not a
proven one.

## 2. Problems with the current design

1. **Order is emergent.** `VIDEO_INFO_TYPE_LIST` (upstream's, untouchable) + ~12 static gates
   (`setSkip*`, `setPrefer*`, `setWebEmbedLast`, `deprioritize*`, `insert*Before*`) + persisted
   winner by ordinal + recovery cursor = orders nobody wrote down (inventory §2.5 needed a page to
   derive them). A kids video walks 9 dead clients (~3-5 s) before the only one that works.
2. **A client is one shape.** supportXhr, visitor source, token, UA and version are hard-coded per
   predicate (`!isTVClient && !isEmbedded`, `isWebPotRequired`), so "MWEB without supportXhr" or
   "TV_TIZEN anonymous" can't be expressed without new special cases.
3. **"Dead" verdicts are frozen in comments** (18 of them, several refuted by today's data).
4. **The media layer knows nothing about readiness** (ad wait) and **nothing but live uses HLS**.
5. **Failure handling amplifies**: early 403 -> re-resolve -> new wait; unplayable autoplay walks
   ~90 /player per minute.

## 3. Design

### 3.0 Failure classification (first, before any reordering)

Every /player outcome and every playback failure gets one class, and each class has one action:

| class | examples | action |
|---|---|---|
| content-terminal | private, removed, members-only, paid, region-blocked (ERROR / specific reasons) | stop after a second independent source agrees; never a bot signal |
| restricted-age | LOGIN_REQUIRED + age reasons | age-capable sources only (WEB_EMBED; signed in: account route); never a bot signal |
| client-refused | UNPLAYABLE "not available" from a head that refuses whole classes (kids on VISIONOS/ANDROID_VR) | move to sources measured to serve that class |
| delivery-unsupported | OK but SABR-only / no usable delivery for this app | next source; or another delivery in the same answer if switched on |
| bot-challenge | explicit "not a bot" text | existing BotWallBook logic |
| transport | timeout, IOException | existing transport-down logic; not evidence against the source |
| readiness | media refused at startup of an ad-bearing (or unknown-ad) answer | wait/retry the same answer (§3.3) before any source blame |
| media-expired / mid-play 403 | 403 after playback started | bounded refresh from the same source first, then the next source |

### 3.1 Source catalog (`PlayerSourceCatalog`, VOIDXDEV, new file in MSC `videoinfo/V2/sources/`)

One entry per *source* = an `AppClient` plus its request profile and measured properties:

```
Source(
  id = "TV_TIZEN_ANON", client = TV_TIZEN,
  identity = APP_VISITOR | WEB_SESSION | EMBED_PAGE,
  poToken = NONE | WEB_SESSION,            // what the /player body and URLs carry
  supportXhr = TRUE | FALSE | ABSENT,
  roles = {GENERAL, KIDS, AGE_EMBEDDABLE, LIVE_HLS, LIVE_DASH, LOW_QUALITY_LAST_RESORT},
  deliveries = {ADAPTIVE, HLS, PROGRESSIVE},
  auth = NEVER | WHEN_SIGNED_IN | REQUIRED,  // enforced, not inferred from isAuthCapable
  endpoint = PLAYER | REEL_ITEM_WATCH,     // ANDROID_REEL != ANDROID
  needsPlayerJs = true/false,              // sig/n solve cost
  timeoutMs, evidence = "netbench sweep1 2026-09-28: kids 7/7, sustain 2/2")
```

The id is stable and versioned (`TV_TIZEN_ANON@1`); anything persisted (winner hint, quarantine,
bot-wall benches) keys on it, with a migration from today's ordinals and an unknown-id fallback.
Roles/deliveries are *observations* used for ordering, never an override of what an answer contains.

`QueryBuilder` / `VideoInfoApiHelper` read the profile instead of predicates for supportXhr,
identity and token. `AppClient` stays upstream-shaped (enum untouched; persisted winner moves to
the enum NAME, fixing the ordinal hazard of a future upstream merge).

### 3.2 Planner (`PhoneSourcePlanner`, replaces the phone's gate stack in the normal branch)

Signed out, normal open — candidate order, enabled only after phase-1 device data confirms it:

1. **VISIONOS** (1 request for almost everything).
2. **TV_TIZEN anonymous** (kids, SABR-only, most "not available").
3. **WEB_EMBED with its embed identity** (age-restricted embeddable, kids when TV_TIZEN fails;
   honour its pre-roll wait; HLS accepted).
4. **ANDROID 360p progressive** (last resort; the UI says "low quality" — to decide).
5. Token sources (WEB/MWEB/WEB_SAFARI with the BotGuard session) only if phase B proves them.

Branches, spelled out:
- **Live** (known from the head's answer or the item's badge): unchanged — VISIONOS HLS held,
  ANDROID_VR asked for DASH; live winners never persisted. Unknown-live (head failed before
  identifying it): the plan continues, and ANDROID_VR is inserted as soon as any answer says live.
  Upcoming: no walk beyond the head (poll as today). Ended replay: treated as VOD.
- **Signed in**: account routes first (unchanged), then the same list; TV_TIZEN in the signed-out
  list is the `auth=NEVER` profile, distinct from the `WHEN_SIGNED_IN` account route, so the
  consensus/enrichment/challenge accounting reads the *actual* auth, not `isAuthCapable`.
- **Bot wall**: BotWallBook's plan stays authoritative while a wall is active. A normal-plan TV_TIZEN
  attempt that gets challenged counts as that wall's anonymous TV_TIZEN ask (no duplicate). Adding
  WEB_EMBED to the walled plan is a separate, measured decision.
- **Recovery** is scoped to an episode (one video, one playback): a startup readiness refusal retries
  the same answer (§3.3); a mid-play 403/expiry first gets one bounded refresh from the same source
  (fresh URLs), then moves to the next source; the episode's history resets on a successful
  150 s of playback or a new video. Never "never back to the head".
- **Prefetch / SessionWarmup / next-video** walks run on speculative state: they may warm caches but
  never set the active video's winner, recovery cursor or wall budget.

Budgets: the 45 s walk budget stays; per-source timeouts come from the catalog (TV_TIZEN 15 s and
WEB_EMBED 20 s today would let the first three sources eat ~42 s on a dead network — transport-down
still ends such walks after 2 no-response attempts).

Fixes that fall out: age-gate LOGIN_REQUIRED must not count as a bot challenge (inventory §6.1);
WEB_EMBED stops being `isWebPotRequired` (it has its own identity); dead sources leave the phone
plan (GEO, TV 7.x signed out, TV_EMBED, TV_SIMPLY/WEB_MUSIC until tokens).

### 3.3 Media readiness: honour the pre-roll wait

- Each answer gets a *generation* (monotonic receive time + id). Its pre-roll data
  (`adSlots`/`adPlacements` pre-content renderers: skip offsets, unskippable durations) gives a
  conservative `readyAt` = receive time + yt-dlp's full wait rule (not 0.8). Missing/unparsable ad
  data = UNKNOWN, not zero.
- All startup media loads of that generation (video, audio, init, HLS playlists, any DataSource
  instance) share the deadline; the wait is cancellable, uses monotonic time, and does not trip the
  startup watchdog, auto-reload or route quarantine.
- A startup refusal (403) of an ad-bearing or UNKNOWN answer — before *or after* readyAt — gets a
  bounded, delayed retry of the same generation (e.g. +3 s, +6 s, +12 s, capped ~25 s from receive),
  then ordinary source recovery. Not applied to failures after playback started.
- A cached/prefetched answer keeps its original receive time; reopening it does not restart the wait.

### 3.4 HLS for VOD and progressive-only answers

A *selected delivery* (ADAPTIVE | HLS | PROGRESSIVE | SABR) is decided once per answer and carried
through playability, the loader and recovery (ErrorFixer knows whether a manifest, segment, track or
progressive range failed). HLS-for-VOD and progressive-only are separate switches, off until
decoded-playback tests pass (variant switching, audio group, seek both ways, subtitles, duration,
EOF, resume, quality pins). Order when adaptive has no usable format: HLS, then progressive.

### 3.5 Autoplay budget

Autoplay stops after 2 consecutive *autoplay-started* items end unplayable (or 3 in 60 s) and shows
the error instead of walking the related list. Manual navigation, cancellations, upcoming-live polling
and offline failures don't count; a successful first frame resets the count.

### 3.6 Keeping it true: benchmark as a maintained tool

- `tools/netbench` (the harness) moves into the repo; the catalog's `evidence` fields cite runs.
- A debug/benchmark in-app mode: force any source (all AppClients, profile overrides), play the
  corpus 150 s each, log `bench-result` lines; run on the Pixel over Wi-Fi and LTE through the
  `.check` side-by-side package. This is the only way to measure token-carrying sources.

## 4. Phases (reordered after review; each behaviour change has its own switch)

1. **Measure in the app** (was C): debug + benchmark builds can force any source profile, play the
   corpus for 150 s or EOF with decoding, and log a `bench-result` line per open (source, actual
   auth, generation, selected delivery, readiness state, first decoded frame and audio, stalls,
   recovery causes, request count). Pixel `.check` package, Wi-Fi and direct LTE, spread over
   several sessions/days to keep request rates low.
2. **Failure classification and state ownership** (§3.0): age vs bot vs terminal vs transport vs
   delivery; prefetch/SessionWarmup no longer move the active video's winner, cursor or wall budget.
3. **Catalog, behaviour-preserving**: today's order and shapes expressed as catalog entries; request
   parity verified byte-for-byte against the current code; versioned ids + migration.
4. **Readiness** (§3.3) with watchdog/cancellation integration.
5. **HLS-for-VOD and progressive** (§3.4), each behind a switch, after decoded tests.
6. **New planner order** (§3.2) after phase-1 device data; wall, signed-in and live branches tested.
7. **PO token** port (upstream July-2026 minting), measured separately with token-bearing profiles.
8. `tools/netbench` into the repo; docs, CLAUDE.md rules, stale comments.

Acceptance before enabling a behaviour by default (scaled to what one home IP and one phone can do
without tripping walls): every changed route on >=5 relevant videos x >=3 opens x both networks in
the app with decoded playback to 150 s/EOF and seeks; readiness on >=15 ad-bearing answers per network
plus no-ad controls; the full corpus once per network through the new planner, asserting outcomes
and request counts. Single-sample results never enable a default.

## 5. Risks / open questions

- TV_TIZEN anonymous at scale: TV-family requests were the first to be bot-challenged in the sweep
  (TV_DOWNGRADED/old Cobalt, twice). TV_TIZEN showed no challenge in ~45 requests. Watch
  `player-ring botwall` lines; keep WEB_EMBED as its alternate.
- Single-sample sustain results; repeat on LTE and with more kids videos before shipping.
- Signed-in kids playback: unmeasured (no test account); the plan's fallbacks apply after the
  account routes.
- 0.8 x factor from two official samples; if wrong, the retry-same-URL path still recovers.
- Upstream merges: the planner lives in new VOIDXDEV files; `VIDEO_INFO_TYPE_LIST` untouched.

## 6. Progress log

### 2026-09-28 night

| phase | state | where |
|---|---|---|
| 1 measure in the app | done: `.check` benchmark build, forced source, `bench-tick`/`bench-seek`, `appbench.py` (guarded, Wi-Fi and LTE) | main 8affb802, MSC 798d5ac4 |
| 2 failure classification | partial: age gate != bot check (MSC 4e714149, 17bbfde1); autoplay budget (main f8dbed92, a76882b1). Terminal/transport/delivery classes not started | |
| 3 catalog | not started | |
| 4 readiness | done, on by default in main (not released): MSC feef8e30, main 6f6413b8; two Codex sol reviews | |
| 5 HLS/progressive | not started; the legacy-codecs progressive route is ungated until then | |
| 6 planner | first step: WEB_EMBED last instead of skipped (v5 check build, uncommitted until its device run) | |
| 7 PO token | not started (`potoken-port.md`); MWEB/TV_SIMPLY/WEB_MUSIC all stop at ~0:59 in the app | |
| 8 netbench into repo | not started | |

Readiness as built deviates from §3.3, on data (834 answers):
- **Ask first, wait only when refused.** Only WEB-family answers announce pre-rolls (VISIONOS,
  ANDROID_VR/REEL, IOS, TV, TV_TIZEN never do). MWEB and WEB_EMBED are refused right after /player
  (73/77) and never once the announced time passed (MWEB served 0.3-3.3 s before it); WEB,
  WEB_SAFARI and WEB_MUSIC announce the same ads and serve at once. Waiting first would have added
  ~6 s to those for nothing.
- Full yt-dlp sum, no 0.8 factor; unknown or unmapped pre-roll = 15 s (none of 206 slots needed it).
- Retry after readyAt: +3, +6 s within 10 s (not 25 s): a genuine refusal reaches route recovery
  sooner, and no refusal after the announced time was ever seen.
- One gate per answer object, on the HTTP side of the cache (cache hits neither wait nor count).
- Watchdog: re-arms while the answer is inside its hold (its action was a quality drop, not a new
  answer). Next-video sample preload is posted at the answer's ready time.

Device (Pixel 9, LTE, `.check`): WEB_EMBED on _WB5hh7WOb4, three opens (v3 x2 aborted by
phone events at 0:53/0:23, v4 full): wait 4.5-4.6 s, served 0.10-0.15 s after readyAt, first frame
6.2-6.3 s, v4 played to 2:09 of 2:12 with the seek and no error. Before the gate: NO-START, 4 errors.

Acceptance still owed before a release enables these by default (§4): readiness on >=15 ad-bearing
answers per network plus no-ad controls; WEB_EMBED-last on >=5 kids videos x >=3 opens x Wi-Fi and
LTE; the corpus once per network through the ring, with request counts.

Later the same night (v4 check build, LTE): WEB_EMBED 4/5 kids PLAY-OK (the 2 h Peppa Pig episode
too; KUcmvVHh_RA's answer had no pre-roll: first frame 1.5 s, no wait). wGltuo1B1sM: WEB_EMBED's
adaptive formats were SABR-only (24, no URLs), the app fell to the single progressive stream (ungated)
and got 403 four times; the same answer had HLS. So phase 5 (HLS for VOD) is what covers that shape,
or TV_TIZEN. Wi-Fi harness matrix finished (sweep2d, 180 attempts, no wall with the TV canaries
excluded): VISIONOS and TV_TIZEN serve all five, WEB_EMBED all but the embed-disabled video.
Phase 3 progress: golden /player requests pinned (MSC 6fb18f09); PlayerSourceCatalog (63357158) and
its four consumers redirected one per commit (visitor, token, supportXhr, budget), goldens unchanged.

### 2026-09-28 late night (after "don't stop until you have finished all of that")

| phase | state | where |
|---|---|---|
| 2 failure classification | walk roles done: preloads/warmup are SPECULATIVE and leave the watched video's routing state alone (cursor, current client, hint, unplayable flag, transport-down, the bot-check probe); an open that reuses a preload's answer adopts it. Codex sol review: 3 findings fixed | MSC 68619d82, main fb1cdb0d |
| 3 catalog | + measured fallback deliveries per source (WEB_EMBED HLS, ANDROID_REEL progressive) | MSC 6d88e1ff |
| 5 HLS/progressive | HLS for VOD behind `debug.arc.hls_vod` (VodDelivery; only sources measured to serve it); the answer's progressive route now behind the readiness gate; the gate counts only /videoplayback as served and logs whether a refused request was media | MSC 6d88e1ff, main f5d5563d |
| 6 planner | PhoneSourcePlanner behind `debug.arc.planner` (PLANNER.md); age gate skips TV_TIZEN; recovery asks the failed client last; the TV_TIZEN switch and WEB_EMBED-last committed | MSC f00c587c, main 50e4979d, 19e5b8d2 |

Device (LTE, `.check`):
- Bot-circuit repro: v4 (released ring) wGltuo1B1sM -> TV "not a bot" -> circuit armed -> dQw4w9WgXcQ in the
  same process answered from the cooldown with no request (NO-START). v8: no trip, the video's own refusal
  shown, dQw4w9WgXcQ plays.
- v8 ring, kids, TV_TIZEN switch on: 5/5 PLAY, all won by TV_TIZEN as #2, first frame 1.2-2.1 s. Switch off
  (WEB_EMBED last): PLAY, first frame 8.6-9.1 s (10-client walk + 4.6 s readiness wait).
- Recap of every source x category x network: `recap/RECAP.md` (script `recap/build_recap.py`).
