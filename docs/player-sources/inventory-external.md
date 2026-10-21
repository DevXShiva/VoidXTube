> Copied from a private working folder on 2026-09-28: the tools it names (harness, appbench, recap, proxy, corpus) now live in `tools/netbench/`; the raw results and logs it cites are not in the repo.

# External inventory: how third-party YouTube clients get playable streams (2026-09-28)

Scope: what yt-dlp, NewPipe(Extractor), LibreTube, Piped, PipePipe, YouTube.js/googlevideo, FreeTube,
Grayjay, Invidious(-companion), ReVanced/Morphe/anddea/rvx, the YouTube-Music Kotlin apps and SmartTube
upstream (yuliskov/MediaServiceCore) do **today** to get media, compared with VoidTube's
`MediaServiceCore/.../common/helpers/AppClient.kt` (fork HEAD `795091f7` + uncommitted working tree of
2026-09-28). Every claim carries a source; **[code]** = read in source at the given SHA, **[issue]/[PR]** =
stated by a maintainer/contributor, **[measured]** = VoidTube's own measurement (HANDOFF / this week's
Pixel runs). Nothing here was benchmarked by this survey; section (b) is the list of things to benchmark.

SHAs used: yt-dlp `c7fb478` (2026-09-16; master `51bab8a` 2026-09-27 has no later YouTube commit),
Morphe `fccde737f` (2026-09-28), ReVanced GitLab dev `bac650ff8` (2026-08-29), anddea `b5a75d761`,
NewPipeExtractor dev `eb53b79` (2026-09-27) / release v0.26.5, LibreTube `abd9073` (2026-09-28),
PipePipeExtractor `c68e10e` (v5.4.0), innertubex `195bac9` (v0.7.2, 2026-09-28), MSC upstream
`476357b4` (2026-09-27). Clones: `…/scratchpad/{yt-dlp-master,repos/*,rv/*}`.

---

## 0. The ten things that matter

1. **The ecosystem converged on two anonymous no-PO-token paths: VISIONOS (no JS, no POT, no kids) and a
   JS-player web/TV client for everything VISIONOS refuses.** yt-dlp: `visionos` → kids fallback
   appends `web_embedded` and `tv_downgraded` (requested tv_downgraded first — the queue pops from the end —
   and formats from both are merged). Morphe/anddea: VISIONOS → TV_SIMPLY(+POT) → ANDROID_CREATOR.
   NewPipe dev: VISIONOS only (kids = 360p or nothing). VoidTube already has VISIONOS first.
2. **Your WEB_EMBEDDED recipe is exactly yt-dlp's `web_embedded` (since 2026-03-10, PR #16177) and
   MetrolistGroup/innertubex's embed path**, with three differences: yt-dlp/innertubex **never send
   `devicePlaybackCapabilities`** at all; yt-dlp replays the **embed page's whole `INNERTUBE_CONTEXT`**
   (visitor, clientVersion, …) and shares one cookie jar across watch page, embed page and /player;
   innertubex uses a Firefox UA and also sends top-level `thirdParty.embedUrl=https://www.youtube.com/embed/<id>`.
   innertubex's benchmark notes: "Normal and made-for-kids playback passed sustained playback and both
   seek directions after fetching per-video encryptedHostFlags; age-restricted media was rejected."
3. **web_embedded is itself entering a SABR-only experiment** (yt-dlp #17666, 2026-09-10: "Both `mweb`
   and `web_embedded` have become SABR-only in some sessions (except for format 18)"). The Safari UA
   commit c7fb478 exists precisely so that such sessions still get **HLS itags 91–96 (≤1080p, muxed)** —
   which is what VoidTube saw for KUcmvVHh_RA on LTE. HLS-for-VOD is a real, maintainer-endorsed fallback.
4. **The 403-then-OK-after-48–68 s is most likely YouTube's forced preroll wait ("fake buffering") —
   a hypothesis until the WEB_EMBED response's ad renderers are checked (candidate 2a).** yt-dlp models
   it (`available_at = now + Σ full preroll ad durations`, or the skip offset when skippable) and sleeps;
   FreeTube does the same from `adSlots` (with a "Remaining preroll-ad time" toast); SABR clients see it
   as `backoff_time_ms`;
   iter.ca (2025-06-20) measured the backoff at **80 % of the ad duration** on the first `/videoplayback`.
   Known avoidances: `contentPlaybackContext.isInlinePlaybackNoAd: true` **plus `inline=1` on the /player
   query** (rvx "Bypass fake buffering"); `adPlaybackContext.pyv=true` (yt-dlp: works for mweb/web_music,
   **did not work for web_embedded** in 2025-12 testing). VoidTube sends isInlinePlaybackNoAd but not
   `inline=1`, and does not parse adPlacements/adSlots.
5. **TVHTML5 5.x ("downgraded") is the other kids path and the only no-PO-token one that also plays
   non-embeddable kids videos** (bashonly, 2026-07-20; with a POT, MWEB/WEB SABR/TV Simply also do). It is broken for sessions in the `tcl` player-JS experiment
   ("The page needs to be reloaded", yt-dlp #17389, open, high priority). Working bypasses in the wild:
   Samsung/Tizen 2.4.0 device context (yt-dlp PR #17723, "high priority, will probably be merged very
   soon" — = VoidTube TV_TIZEN), an ancient Cobalt UA (SmartTube upstream 2026-09-08:
   `Cobalt/4.13031-qa … Starboard/1`), TVHTML5 **5.20150304** + Tizen (Morphe TV_DASH). VoidTube uses
   TV_TIZEN only signed-in under a bot wall — never anonymously for kids.
6. **TVHTML5_SIMPLY + a TV-challenge PO token is Morphe's "plays everything incl. kids" signed-out path**
   (PR #2533, 2026-08-22): challenge from `/tv_config?action_get_config=true`, BotGuard in a WebView,
   `GenerateIT`, a video-bound token in `serviceIntegrityDimensions.poToken` + a visitor-bound `&pot=` on
   every format URL. BgUtils #44 confirms the TV challenge "doesn't require EVENT_ID (yet?)".
7. **WebPO minting changed in late July 2026**: the BotGuard challenge is bound to the page session
   (`yt.config_.EVENT_ID`); tokens minted from `/att/get` challenges are rejected with GVS 403 **~30 s
   into playback** for sessions in the experiment (inference: NewPipe-style `jnn/v1/Create` challenges
   are equally unbound to the page's EVENT_ID) (BgUtils v4.0.3 2026-08-04,
   bgutil 1.3.2 2026-08-21: 58 % → 92 % success). SmartTube upstream ported this (`PoTokenWebView4`,
   2026-08-08/12). **VoidTube's fork still selects the old `PoTokenWebView` (`jnn/v1/Create`).** Also: GVS
   tokens are now bound to the **video id** when `html5_generate_content_po_token=true` is in the ytcfg.
8. **android_vr is dead as a default everywhere** (yt-dlp: all formats 403 with 1.65.10 since
   2026-08-17; Morphe: VR needs OAuth sign-in since 08-19 and moved to Pico/XR identities because Quest
   "is being used by too many alternative clients such as yt-dlp, SmartTube, and NewPipe"). IOS has no
   usable VOD without an attested POT (no VOD m3u8 since ~2026-05-18). ANDROID reel needs a POT since
   2026-07-25 (Morphe), except the itag-18 360p muxed floor.
9. **Java/Kotlin SABR now exists and ships**: LibreTube v32 (media3, ~2.3k lines, VISIONOS-sourced),
   Grayjay (native media3 UMP, default since 08-22, WEB/MWEB + WebView POT), PipePipe (ExoPlayer2, MWEB +
   WebView POT), Flow, innertubex (audio only), SmartTube upstream ("very poor … live completely
   unplayable", yuliskov 2026-09-09). yt-dlp's SABR PR #13515 is still unmerged. The "~60 s wall" is the
   `sps` stream-protection status (2 = attestation pending after ~1–2 MB, 3 = required); the fix is a
   **content-bound (video-id) token as raw bytes in `streamer_context.po_token`**. For kids that means
   WEB/MWEB + a good token (VISIONOS SABR never serves kids). Estimate on top of VoidTube's `sabr-media3`:
   ~1.5–2.5 focused weeks (§3.6).
10. **Small VoidTube hygiene gaps vs the ecosystem**: WEB_CREATOR `1.20241203.01.00` and WEB_REMIX
    `1.20250219.01.00` are ~1–2 years stale (yt-dlp/upstream: `1.20260708.06.00` / `1.20260707.12.00`);
    `TVHTML5_SIMPLY` is sent as client-name id **74** (yt-dlp and Morphe use **75**; YouTube.js has 74);
    IOS goes out as `clientName "iOS"`, osName "iOS" (yt-dlp: `IOS` / `iPhone`; upstream MSC moved to
    osName `iPhone`); no per-client visitor ids (Morphe #2309 made them per client to lower bot score).

---

## (a) Master table — request shapes, who uses them, what they need, what they are good for

Legend: POT = PO token (GVS = on media URLs, PL = in the /player body); JS = needs the player JS for
sig/n; "kids" = made-for-kids; "age" = age-restricted. "VoidTube has it" is checked against
`AppClient.kt` (fork working tree 2026-09-28).

| # | Client / request shape | Who uses it (2026-09) | Needs | Good for / fails on | VoidTube has it? | Last confirmed working |
|---|---|---|---|---|---|---|
| 1 | **VISIONOS 1.02** (Apple `RealityDevice17,1` or `14,1`, osName visionOS, osVersion `26.5.23O471`/`26.6.x`, Safari-26 UA; 1.03/1.04 variants) | yt-dlp default #1 (anon + JS-less); NPE dev (only stream client, 1.04 + UA `com.google.visionos.youtube/1.04(...)`); LibreTube (SABR source); PipePipe default; Morphe/anddea default (1.02; 1.03 for AV1); ReVanced (0.1); SmartTube upstream ring #1; innertubex probe only | nothing: no POT, no JS, no cookies | normal VOD, AV1/8K, itag 616 "enhanced bitrate" (gamer191 #17226), VOD m3u8 separate tracks incl. itag 602 (#17143). **Fails: kids, age, paid, movie, private.** Morphe: "may end 1 s early", no stable volume. innertubex: "clean Android sessions can stall"; VISIONOS **0.1** → HTTP 400 FAILED_PRECONDITION (Sept 2026) | **yes** (VISIONOS 1.02, RealityDevice17,1; PREFERRED_FIRST_CLIENT). Measured: SABR from VISIONOS served to the end without POT (HANDOFF §27–28) | yt-dlp default 2026-08-18 (dae52d8); Morphe v1.44.0 2026-09-21; NPE dev 2026-09-27 |
| 2 | **WEB_EMBEDDED_PLAYER 2.2026xxxx + embed identity**: embed page `/embed/<id>?html5=1` fetched with Referer `https://www.reddit.com/` (non-YouTube) + same UA; /player with `thirdParty.embedUrl=https://www.reddit.com/`, `contentPlaybackContext{html5Preference, signatureTimestamp, encryptedHostFlags}`, that page's visitor | yt-dlp `web_embedded` (signed-in default #1 since 08-18; anon kids/age fallback; Safari UA since 09-16, master only); innertubex/Metrolist (auto, priority 65, Firefox UA, per-video flags); lavalink youtube-source (`fetchEncryptedHostFlags`); SmartTube upstream (flags via YtCfgService, Safari UA 09-18) | JS (sig + n, also `/n/` in the HLS manifest path), **no POT**; per-video embed-page fetch (yt-dlp, innertubex) | **kids (embeddable)**, age-gated **embeddable** videos (yt-dlp), HLS 91–96 ≤1080p with Safari UA. **Fails: non-embeddable** ("Playback on other websites has been disabled", #17497), age-gated non-embeddable. Risks: SABR-only experiment in some sessions (#17666) → HLS still there; forced preroll wait (§7) | **yes** (WEB_EMBED, working tree 2026-09-28; Safari UA; flags+visitor cached 6 h, not per video; sends `supportXhr=false`) | yt-dlp c7fb478 2026-09-16; innertubex v0.7.2 2026-09-28; VoidTube Pixel 2026-09-28 (with the 403 window) |
| 3 | **TVHTML5 5.x "downgraded"** (clientVersion `5.20260707`/`5.20260901`), 5-digit sts, main/`tv-player-ias` JS; UA variants: `Cobalt/Version` (yt-dlp), `(DirectFB; Linux x86_64) Cobalt/4.13031-qa (unlike Gecko) Starboard/1` (SmartTube upstream), Samsung SmartTV Tizen 2.4.0 + deviceMake/Model/osName/osVersion (yt-dlp PR #17723 = VoidTube TV_TIZEN) | yt-dlp `tv_downgraded` (signed-in default #2; kids fallback, requested before web_embedded; REQUIRE_AUTH removed 07-20); SmartTube upstream ring #2 (old Cobalt UA, auth); PipePipe (extractor only, app refuses it); innertubex ("UNPLAYABLE in August 2026") | JS, no POT (yt-dlp: "doesn't need PO tokens"), cookies/OAuth OK | **kids incl. non-embeddable** (bashonly 07-20), signed-in playback, age with an account. **Broken for sessions in the `tcl` experiment** ("page needs to be reloaded", #17389); 360° formats differ with Tizen (gamer191) | **yes**: TV_DOWNGRADED (`Cobalt/Version`, ring), **TV_TIZEN** (Tizen, signed-in bot-wall only, not in ring) | TV_TIZEN reproduced off-device 2026-09-25 (HANDOFF §31); PR #17723 comments 2026-09-28 "fixes work for me" |
| 4 | **TVHTML5 5.20150304** (a 2015 version) + Tizen 2.4.0 (Morphe TV_DASH) or `Cobalt/9.28152-debug … Starboard/4` (rvx TV_LEGACY, "can play SABR format-only videos", `attest_botguard_on_tvhtml5:false`) | Morphe (TV_DASH, live fallback of TV_SABR); rvx (EOL) | JS, no POT | DASH/https where 7.x is SABR; live DASH | no (VoidTube's TV_DOWNGRADED/TV_TIZEN use 5.20260707) | Morphe code 2026-09-28 |
| 5 | **TVHTML5 7.x** (`7.20260707.07.00`/`7.20260901.15.00`, Cobalt 25 UA or PS4 GAME_CONSOLE) | yt-dlp `tv` (non-default); SmartTube TV head; Morphe TV_SABR (PS4, SABR, "can play Kids", #3078) | JS; tcl variant (8-digit sts); Morphe's TV_SABR declares `requirePoToken=false` (intent, not proof) | signed-in; kids via SABR (Morphe). DRM-on-everything experiment (#12563); SABR-only often | yes (TV, TV_LEGACY) | Morphe 2026-09-22 (#3078) |
| 6 | **TVHTML5_SIMPLY 1.0/1.1** (Morphe: Sony PS4, platform GAME_CONSOLE, id 75) **+ TV-challenge POT** | Morphe/anddea fallback #1 (signed-out works since 2026-08-22, PR #2533); yt-dlp `tv_simply` (GVS POT required https/dash; not default); innertubex (priority 42, "two tokens, HLS"); invidious-companion fallback #1 | JS + POT (video-bound PL token + visitor-bound `&pot=` GVS) | Morphe: "Can't play: none" incl. **kids**; no cookies in yt-dlp | partly: TV_SIMPLY 1.0, **id 74**, no POT. [measured 2026-09-07] 5-digit sts → OK and media 206 | Morphe #3078 closed 2026-09-22 (TV Simply working again) |
| 7 | **ANDROID_VR** 1.65.10 Quest 3 / 1.61.48 / 1.43.32; Morphe: 1.73.21 Pico (SABR) / **1.64.34** DASH / XR 1.69.27, **OAuth required** | yt-dlp (non-default since 08-18); ReVanced fallback (1.43.32); Morphe (signed-in only); invidious-companion fallback #2; Grayjay (off by default since 08-22); VoidTube ring | yt-dlp: GVS POT unless player POT; all formats 403 with 1.65.10 since 08-17 | normal VOD; multi-audio (SABR); live DASH. **Fails: kids** | yes (ANDROID_VR 1.65.10) | dead anonymous (yt-dlp 2026-08-18); Morphe OAuth variants 2026-08-19 |
| 8 | **IOS 21.26.4** (iPhone16,2, iOS 18.3.2) | yt-dlp (non-default); NPE release optional (off) | GVS POT for https **and HLS** (not with player POT; needs iOS attestation) | HLS live only with POT; no VOD m3u8 since ~2026-05-18 (#16764) | yes (IOS, sent as `clientName "iOS"`) | not usable without attestation |
| 9 | **ANDROID 21.x reel_item_watch** (`/youtubei/v1/reel/reel_item_watch`, `disablePlayerResponse:false`) | NPE v0.26.5 release (primary); PipePipe (parallel kids fallback, 360p muxed); ReVanced default ANDROID_REEL_NO_AUTH 20.26.46 | Morphe: "always requires a PoToken" since 2026-07-25; tokenless → itag 18 only | **kids at 360p (itag 18 muxed)**; "Video may stop at 1:00" | yes (ANDROID_REEL, ANDROID, ANDROID_SDK_LESS) | NPE release 2026-08-15; itag 18 "always expected to be sunset" (bashonly) |
| 10 | **MWEB 2.20260708.05.00** (iPad Safari UA) + WebView BotGuard POT (+ SABR) | PipePipe "mweb (SABR)" (forced when signed in); yt-dlp wiki TL;DR "Use a PO Token Provider … `mweb` client"; bashonly: mweb+POT for non-embeddable/age | JS + GVS POT (itag 18 without); `adPlaybackContext.pyv` accepted | with POT: kids, non-embeddable, age (signed in) — community "works for all videos" (#17497), maintainer advice for non-embeddable/age; SABR-only in some sessions (#17666, then only itag 18 without SABR) | yes (MWEB; VoidTube mints a web POT, old jnn/Create flow) | PipePipe v5.4.0 2026-09-24 |
| 11 | **WEB (watch-page ytcfg) + content-bound POT + SABR** | FreeTube (Shaka SABR plugin, token minted in Electron from the page `ytAtN`); Grayjay (native media3 UMP; MWEB when logged in); YouTube.js default client; googlevideo examples; invidious-companion (WEB+POT, no SABR → falls back to TV_SIMPLY → ANDROID_VR → MWEB); yt-dlp SABR PR; SmartTube upstream | JS + POT (in /player `serviceIntegrityDimensions` and as raw bytes in `streamer_context.po_token`) + SABR/UMP | everything the browser plays, incl. kids | WEB exists; SABR path debug-only (dies ~60 s = `sps` without POT) | FreeTube v0.25.3 2026-08-28; Grayjay v364 |
| 12 | **WEB_SAFARI** (Safari 15.5 UA) → pre-merged HLS 91–96 | yt-dlp (non-default since 07-20) | JS (n in manifest path); HLS: no GVS POT "at this time" (wiki) | kids at ≤1080p **when signed in** (ChannelFinWatcher #55: Pocoyó 1080p via web_safari with cookies). "Since 2026.07, HLS formats are only returned with some logged-in or 'trusted' sessions" | yes (WEB_SAFARI, anonymous) | 2026-09-23 (signed-in) |
| 13 | **WEB_REMIX 1.20260707.12.00** (music.youtube.com) | yt-dlp `web_music` (signed-in music URLs); innertubex primary (Firefox UA) | JS + GVS POT (itag 18 without) | innertubex: "Works for normal, explicit, and kids content when signed in"; BOplaid: works for non-embeddable with POT (#17497) | yes (WEB_MUSIC) but **version `1.20250219.01.00`** | innertubex 2026-09-28 |
| 14 | **WEB_CREATOR** | yt-dlp (premium default, age-verification bypass); innertubex (login) | cookie login (SAPISIDHASH) + GVS POT | age-verification bypass, kids | yes but **version `1.20241203.01.00`**; OAuth bearer → HTTP 400 [measured 2026-09-07] | not usable with a TV OAuth bearer |
| 15 | **ANDROID_CREATOR 26.10.000** (Pixel 10 Pro XL, Android 16) | Morphe fallback #3 (login required); ReVanced (23.47.101) | Android account auth (GMS token inside the YouTube app) | **kids**, 720p max, no live/HDR/AV1 | constant only (YTSTUDIO_ANDROID 22.43.101), no AppClient | Morphe 2026-09-28 |
| 16 | **WEB_KIDS 2.20260205.00.00** (id 76) | innertubex (priority 55, first for known kids content; "Kept in inventory pending full playback validation") | JS; unknown POT | kids (unvalidated) | constant only (CLIENTS.WEB_KIDS), no AppClient | unvalidated |
| 17 | **itag 18 progressive** (360p muxed) from WEB/MWEB/ANDROID reel/WEB_EMBEDDED | NPE/PipePipe kids floor; yt-dlp keeps it | JS for web; none for reel | last-resort floor; gone for some videos (BOplaid #17603) | incidental | 2026-09 |

---

## (b) Ranked candidates to benchmark

Ranked by (chance it plays what VoidTube can't today) × (cheapness), after an adversarial astra review
(see "Review" at the end). Each is a concrete request variant; "arms" = what to compare. Test set: kids
_WB5hh7WOb4, KUcmvVHh_RA and one **non-embeddable** kids video, an age-gated embeddable and an age-gated
non-embeddable video, one normal control; LTE and Wi-Fi; play past 120 s.

0. **Baseline control first: the complete yt-dlp-shaped WEB_EMBED request** (cheap, diagnostic). Embed
   page fetched **per video** (`/embed/<id>?html5=1`, Safari UA, `Referer: https://www.reddit.com/`);
   /player body = that page's own `INNERTUBE_CONTEXT` (its visitorData and clientVersion) with
   `thirdParty.embedUrl=https://www.reddit.com/`, `contentPlaybackContext{html5Preference,
   signatureTimestamp, encryptedHostFlags}`, `racyCheckOk`, `contentCheckOk` — and **no
   `devicePlaybackCapabilities`, no `isInlinePlaybackNoAd`, no `lactMilliseconds`**; X-Goog-Visitor-Id =
   the same visitor. Compare with VoidTube's current body (6 h cached identity, `supportXhr=false`,
   isInlinePlaybackNoAd). Variants from innertubex: Firefox-140 UA, top-level
   `thirdParty.embedUrl=https://www.youtube.com/embed/<id>`, `videoCheckOk`; bashonly's reserve flags
   source `INNERTUBE_CONTEXT.thirdParty.embeddedPlayerContext.embeddedPlayerEncryptedContext`. Every arm
   below should be run against this baseline so differences are attributable.
1. **(joint first) HLS-for-VOD fallback when WEB_EMBED answers SABR-only but carries `hlsManifestUrl`**
   (finding 2 — the phone already receives the manifest, so this is the most concrete lead). Solve the
   `/n/<challenge>` segment in the manifest **path** (yt-dlp substitutes it before fetching and drops the
   manifest if n is unsolved); expect itags 91–96 (144p–1080p muxed H.264/AAC, "worse codecs"); no GVS
   POT is required by yt-dlp's policy (web_embedded has none; web-family HLS is "recommended" only).
   Measure the stages separately — master playlist, media playlist, **first segment** — because "manifest
   present" is not "playable", and yt-dlp only applies `available_at` at download time, i.e. the forced
   wait may also hit the first segment. Arms: with/without `/pot/<web-pot>` appended to the manifest path.
2. **(joint first) Measure, then honour, the preroll wait on WEB_EMBED** (finding 1: 403 at 0.3 s, OK at
   48–68 s). Hypothesis, not yet established: YouTube's forced preroll wait.
   - 2a, measure (cheap, decisive): does the WEB_EMBED response carry `adPlacements[]` with
     `adPlacementRenderer.config.adPlacementConfig.kind == AD_PLACEMENT_KIND_START`, or `adSlots[]` with
     `adSlotMetadata.triggerEvent == SLOT_TRIGGER_EVENT_BEFORE_CONTENT`? Compute yt-dlp's number (Σ full
     `instreamVideoAdRenderer.playerVars.length_seconds`, or `skipOffsetMilliseconds` per ad when
     present — yt-dlp uses full durations, iter.ca measured ~80 %) and compare with the observed window
     per video. If there are no ad renderers, the 403 is something else.
   - 2b, honour: delay the first media request to `available_at` (measure TTFF); overlap the wait
     (prefetch /player for the next queue item; start on another client and switch) or show a countdown.
   - 2c, avoidance arms (speculative): `inline=1` on the /player query + `isInlinePlaybackNoAd:true` (rvx
     "Bypass fake buffering", 2025-09-16); `adPlaybackContext:{pyv:true}` (yt-dlp reports 403s with it on
     web_embedded in 2025-12 — retest); body without `isInlinePlaybackNoAd`/`lactMilliseconds`.
3. **TVHTML5 5.x for kids, anonymous** — yt-dlp's other kids fallback (it actually requests
   `tv_downgraded` **before** `web_embedded`: both are appended and the queue is popped from the end).
   Note the reference checkout uses the plain `Cobalt/Version` UA; Tizen is only in open PR #17723. Arms:
   TV_TIZEN anonymous (today VoidTube uses it only signed-in under a bot wall); TV_DOWNGRADED with
   upstream's ancient Cobalt UA `Mozilla/5.0 (DirectFB; Linux x86_64) Cobalt/4.13031-qa (unlike Gecko)
   Starboard/1` **and the real 5-digit sts** — VoidTube's `usesTvSignatureTimestamp` still suffixes
   TV_DOWNGRADED with `001` (`AppClient.kt`), so a UA change alone does not reproduce upstream's 09-08
   recipe; TVHTML5 **5.20150304** + Tizen (Morphe TV_DASH). Measure status, formats with URL, 206 at byte 0
   and deep, playback past 60 s. The non-embeddable kids video is the one WEB_EMBED cannot do.
4. **TVHTML5_SIMPLY with a TV-challenge PO token (Morphe PR #2533)**: `GET
   https://www.youtube.com/tv_config?action_get_config=true` (TV UA) → `challengeParams.R` (bgChallenge) +
   `challengeRequestKey` → BotGuard snapshot in the existing WebView → `POST /api/jnn/v1/GenerateIT` →
   (a) video-id-bound token → `serviceIntegrityDimensions.poToken`, (b) visitor-bound token → `&pot=` on
   each format URL. Client-name id **75**, clientVersion 1.1, PS4 device fields. Morphe claims kids
   work; BgUtils #44 says the TV challenge needs no EVENT_ID. Also try that token on TV_TIZEN.
5. **Fix PO-token minting, then re-run the web-POT arms.** Two separate changes: (i) **minting** — port
   upstream's `PoTokenWebView4` (homepage `ytAtN` challenge + ytcfg/EVENT_ID) and log which path actually
   minted (it keeps an `/att/get` fallback, so selecting the class proves nothing); (ii) **binding** —
   VoidTube's streaming token is minted against the web visitorData (`PoTokenProviderImpl`), while
   sessions with `html5_generate_content_po_token=true` want it bound to the **video id**. Then: MWEB +
   GVS POT (yt-dlp wiki's general recommendation; bashonly for non-embeddable/age), with
   `adPlaybackContext.pyv` (removes the wait on mweb per yt-dlp); WEB_REMIX `1.20260707.12.00` + GVS POT
   (innertubex's primary; bump the stale version first).
6. **`supportXhr=false` (or no `devicePlaybackCapabilities`) on MWEB, WEB_SAFARI and TV 7.x** for kids
   videos answered SABR-only. yt-dlp never sends the block; rvx's JS clients sent `supportXhr:false`;
   Morphe switched false→true on 2026-02-18 because it wanted SABR. Lower value for IOS and ANDROID_REEL:
   their media also need an attested (DroidGuard/iOS) token, so URLs alone would still hit the ~60 s wall.
7. **WEB_KIDS 2.20260205.00.00 (id 76)** — innertubex keeps it "pending validation"; one probe run is
   cheap (status, URLs vs SABR-only).
8. **itag-18 floor**: when all else fails on kids, `streamingData.formats` itag 18 from WEB_EMBED / MWEB /
   ANDROID reel (NPE, PipePipe). 360p; check it isn't also behind the wait.
9. **Per-client visitor ids** (Morphe #2309: one visitor shared across clients raises the bot score;
   `/visitor_id` per client, refreshed from each response) — for the bot-wall work.
10. **SABR with a proper POT** (the structural fix; §3), after 5: content-bound token as raw bytes in
    `streamer_context.po_token` (and in /player for WEB/MWEB), `sps` 2/3 → re-mint,
    `backoff_time_ms` (= the ad wait), `RELOAD_PLAYER_RESPONSE`, `SABR_REDIRECT`; recheck
    `EnabledTrackTypes` (googlevideo VIDEO_ONLY = 2; STATUS.md assumed none). Arms: **WEB_EMBEDDED SABR**
    (the phone already receives embedded SABR metadata), WEB and MWEB SABR on the kids set. Copy-safe
    references: googlevideo, SmartTube `library/sabr` (MIT); LibreTube/Grayjay for reading only.
11. Low: ANDROID_VR Pico/XR 1.64.34/1.69.27 DASH with an OAuth bearer (Morphe requires sign-in; does it
    accept VoidTube's TV device-flow bearer?) — normal videos only, no kids. ANDROID_CREATOR needs an
    Android account token, almost certainly not the TV bearer.

---

## (c) Ecosystem change log, 2026-06 → 2026-09-28

- 06-06/06-09 NPE PR #1508: VISIONOS added (released v0.26.3); kids → 360p only.
- 06-09 LibreTube: SABR merged (released v32.0 on 08-20).
- 06-26 yt-dlp #17062: forced preroll wait also read from `adPlacements` (AD_PLACEMENT_KIND_START).
- 07-04 yt-dlp 2026.07.04: live adaptive formats (#16771).
- 07-09 yt-dlp: `visionos` client added (#17184); all client versions bumped (#17185; web_embedded
  1.x → 2.20260708.00.00).
- 07-13…07-20 PipePipe: SABR streaming + session-bound WebView PO tokens.
- ~07-15 YouTube: `android_vr` selective POT enforcement; web_safari VOD HLS gated behind login/"trusted"
  sessions (#17143).
- 07-18…08-04 BgUtils v4.0.0 → v4.0.3: WebPO challenge bound to `yt.config_.EVENT_ID` (late July);
  `/att/get` tokens rejected for sessions in the experiment (#44).
- 07-20 yt-dlp #17261: defaults `visionos,android_vr,web`; tv_downgraded no longer REQUIRE_AUTH; kids
  fallback → tv_downgraded; web_safari out of defaults.
- 07-20 Morphe #2094: SABR support; 07-25 #2208: Android Reel removed (POT always required), default →
  visionOS.
- 07-21 → 09: ~59 s / 1 MiB 403s on POT-less clients reported everywhere (LibreTube #8609 07-21, NewPipe
  #13824/#13841/#13855 Sept, innertubex ANDROID_VR "403 after 1 MiB").
- 08-05 Morphe #2309: VR identity Quest → Pico/XR; per-client visitor ids; TV → PS4 SABR.
- 08-06…08-20 NPE PR #1529: ANDROID, IOS, WEB_EMBEDDED removed; VISIONOS only; "videos made for kids
  cannot be played until we can support SABR".
- 08-07 yt-dlp #17389: tv_downgraded "The page needs to be reloaded" (tcl player variant).
- 08-08/08-12 SmartTube upstream: `PoTokenWebView4` (homepage challenge) becomes the generator;
  08-11 "we almost fixed sabr".
- 08-17 YouTube: android_vr 1.65.10 403 on all formats → 08-18 yt-dlp #17461 removes it; #17462 adds
  web_embedded to signed-in defaults (first) and as an extra kids fallback. 08-19 yt-dlp 2026.08.19 released.
- 08-19 Morphe #2479: VR needs OAuth; DASH variants 1.64.34 / XR 1.69.27.
- 08-21 bgutil 1.3.2: homepage challenge + ytcfg minting (fixes GVS 403 ~30 s in); 09-08 bgutil 2.0.0
  (security, localhost binding).
- 08-22 Morphe #2533: TV Simply works signed-out via `/tv_config` BotGuard PO token.
- 08-24 PipePipe v5.3.0: SABR restored (media3 dropped, rewritten); 09-24 v5.4.0.
- 08-28 Morphe #2618: external PotHelper (DroidGuard/keybox) PO tokens; 08-31 anddea in-app provider.
- 09-03…09-08 SmartTube upstream: TV_DOWNGRADED as final fallback, then ancient Cobalt UA for the
  non-tcl player ("fix restricted videos by downgrading UA").
- 09-09 yuliskov: "99 percent of issues are related to recent switching to sabr … live or past live
  videos are completely unplayable with the current sabr implementation."
- 09-10 yt-dlp #17666: mweb and web_embedded SABR-only in some sessions (except itag 18).
- 09-16 yt-dlp c7fb478 (#17684, master only): Safari UA for web_embedded → HLS 91–96.
- 09-18 SmartTube upstream 9df453a0: Safari UA for web_embedded.
- 09-24 yt-dlp PR #17723 (open): Tizen device context for tv_downgraded; maintainers want it as a
  separate client (`tv_samsung`), gamer191 "high priority … merged very soon".
- 09-28 innertubex v0.7.2: VISIONOS 0.1 demoted (HTTP 400 FAILED_PRECONDITION); LibreTube removes
  Piped support.
- SABR stack dates: googlevideo v4.1.1 07-13, unreleased rewrite #54 09-23 (live, corrected proto
  names); Grayjay native media3 UMP 07-14, default 08-22, VISIONOS 1.04 08-24, ANDROID_VR off 08-22;
  YouTube.js VISIONOS 08-12, v18.1.0 09-22; FreeTube #9689 SABR redirect fix 08-25, #9753 09-10;
  invidious-companion YouTube.js 18 on 09-05 (still `/att/get` tokens).

---

## 1. yt-dlp

Source: local `~/projects/yt-dlp` @ `c7fb478` (2026-09-16) — identical for `yt_dlp/extractor/youtube/` to master
`51bab8a` (2026-09-27). Latest stable **2026.08.19**; c7fb478 is nightly/master only. Key files:
[`_base.py` INNERTUBE_CLIENTS L97–390](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_base.py#L97),
[`_video.py` defaults L143–147](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L143),
[`_get_requested_clients` L2969](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L2969),
[`_extract_player_responses` L3028](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L3028),
[`_get_available_at_timestamp` L3823](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L3823),
[`pot/utils.py`](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/pot/utils.py).

### 1.1 INNERTUBE_CLIENTS — every entry [code]

Common to all: `hl=en`; host `www.youtube.com` unless noted; /player body = page-or-static
`INNERTUBE_CONTEXT` + `playbackContext.contentPlaybackContext{html5Preference: HTML5_PREF_WANTS,
signatureTimestamp?, encryptedHostFlags?}` (+ `adPlaybackContext{pyv:true}` only with
`use_ad_playback_context=true` on mweb/web_music) + `racyCheckOk:true, contentCheckOk:true`;
a player POT goes in `serviceIntegrityDimensions.poToken`. **yt-dlp never sends
`devicePlaybackCapabilities`, `isInlinePlaybackNoAd` or `lactMilliseconds`.** Headers: X-YouTube-Client-Name
(the numeric id below), X-YouTube-Client-Version, Origin, X-Goog-Visitor-Id, User-Agent, cookie auth.

PO-token columns: GVS policy per protocol https / dash / hls. `req` = required, `rec` = recommended,
`!prem` = not_required_for_premium, `!w/pl` = not_required_with_player_token. Blank = nothing required.

| key | clientName / version | id | UA / device fields | JS | GVS POT | PLAYER POT | cookies/auth | code comment |
|---|---|---|---|---|---|---|---|---|
| `web` | WEB 2.20260708.00.00 | 1 | yt-dlp's browser UA | yes | https,dash req+rec+!prem; hls rec | – | cookies | https formats w/o URL skipped: "YouTube is forcing SABR streaming for this client" |
| `web_safari` | WEB 2.20260708.00.00 | 1 | Safari 15.5 macOS `…Version/15.5 Safari/605.1.15,gzip(gfe)` | yes | as web | – | cookies | "Safari UA returns pre-merged video+audio 144p/240p/360p/720p/1080p HLS formats"; **"Since 2026.07, HLS formats are only returned with some logged-in or 'trusted' sessions"** |
| `web_embedded` | WEB_EMBEDDED_PLAYER 2.20260708.00.00 | 56 | Safari 15.5 UA (c7fb478, 2026-09-16); `thirdParty.embedUrl=https://www.reddit.com/` | yes | none | – | cookies | encryptedHostFlags on every request (1.4) |
| `web_music` | WEB_REMIX 1.20260707.12.00 (host music.youtube.com) | 67 | default | yes | https,dash req+rec+!prem; hls rec | – | cookies; ad-playback-context | added for music URLs when signed in |
| `web_creator` | WEB_CREATOR 1.20260708.06.00 | 62 | default | yes | https,dash req+rec+!prem; hls rec | – | cookies, REQUIRE_AUTH | "This client now requires sign-in for every video" |
| `android` | ANDROID 21.26.364 | 3 | `com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip`, sdk 30, osName Android, osVersion 11 | no | all req+rec, !w/pl | rec | – | – |
| `android_vr` | ANDROID_VR 1.65.10 | 28 | Oculus / Quest 3, sdk 32, Android 12L, UA `com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip` | no | all req+rec, !w/pl (added 07-20) | rec | – | "Made for kids videos aren't available"; "clientVersion>1.65 may return SABR streams only"; "Since 2026.07, intermittent/selective POT enforcement … for non-HLS formats"; **"Since 2026.08.17, ALL formats (including live HLS and itag 18) are 403'd with version 1.65.10"** |
| `ios` | IOS 21.26.4 | 5 | Apple / iPhone16,2, osName **iPhone**, osVersion 18.3.2.22D82, UA `com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)` | no | https req+rec+!w/pl; **hls req** ("HLS Livestreams require POT 30 seconds in") | rec | – | "iOS clients have HLS live streams"; 60 fps via deviceModel |
| `visionos` | VISIONOS 1.02 | 101 | Apple / RealityDevice17,1, visionOS 26.5.23O471, UA `Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15` | **no** | **none** | none | – | "Made for kids videos aren't available with this client" |
| `mweb` | MWEB 2.20260708.05.00 | 2 | iPad `CPU OS 16_7_10 … Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)` ("mweb previously did not require PO Token with this UA") | yes | https,dash req+rec+!prem; hls rec | – | cookies; ad-playback-context | 'ultralow' formats |
| `tv` | TVHTML5 7.20260707.07.00 | 7 | `Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)` | yes | none | none | cookies | DRM-on-all experiment warning (#12563) |
| `tv_downgraded` | TVHTML5 **5.20260707** | 7 | `Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version` | yes | none | none | cookies (REQUIRE_AUTH removed 2026-07-20) | broken for `tcl`-experiment sessions (#17389) |
| `tv_simply` | TVHTML5_SIMPLY 1.0 | **75** | – | yes | https,dash req+rec; hls rec | – | – | – |

Removed earlier: `tv_embedded` (TVHTML5_SIMPLY_EMBEDDED_PLAYER) "broken" 2026-01-31 (#15787);
`ios_downgraded` 2026-01-31 (#15786); `mediaconnect`, `android_music`, `ios_music`, `android_testsuite`,
`android_producer` before 2025. OAuth login is gone (cookies only).

### 1.2 Default selection

| situation | clients, in order | since |
|---|---|---|
| signed out + JS runtime | `visionos, web` | 2026-08-18 (dae52d8 / #17461) |
| signed out, no JS runtime | `visionos` (+ "extraction without a JS runtime has been deprecated") | 2026-08-18 |
| signed in (cookies), free | `web_embedded, tv_downgraded, web` | 2026-08-18 (5d5b634 / #17462) |
| signed in, Premium | `web_creator, tv_downgraded, web` | 2026-08-18 |
| signed in + music URL | + `web_music` | – |
| signed in | every client without SUPPORTS_COOKIES is dropped | – |

Before: 2026-07-20 (#17261) `visionos, android_vr, web` / authed `tv_downgraded, web`; before that
`android_vr, web_safari` / authed `tv_downgraded, web_safari`. In the signed-out default `web` mostly
supplies the watch page (`ytInitialPlayerResponse`, microformat — and the literal string `made for kids`
the kids fallback greps for), subtitles and live; its https formats are SABR-only.

### 1.3 Fallbacks and special cases [code, `_extract_player_responses`]

- **Made-for-kids** ([L3146](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L3146)):
  client ∈ {android_vr, visionos} AND status `UNPLAYABLE` or (since 07-20) `ERROR` AND the watch webpage
  contains `made for kids` AND a JS runtime exists → append `web_embedded`, then `tv_downgraded`
  (append order; the client queue is popped from the end, so **tv_downgraded is requested first**;
  formats from both are merged).
  History: 2026-01-31 web_embedded (#15785); 2026-07-20 tv_downgraded, which "makes non-embeddable
  'made for kids' streams now downloadable" (bashonly, #17261); 2026-08-18 both (web_embedded appended first).
  Non-embeddable kids fail on web_embedded with "Playback on other websites has been disabled" (#17252).
- **Age-gate** ([L3156](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L3156)):
  `desktopLegacyAgeGateReason`, or reason contains "confirm your age"/"age-restricted"/"inappropriate",
  or status AGE_CHECK_REQUIRED/AGE_VERIFICATION_REQUIRED → append `web_embedded.<base>` (embeddable
  only). Signed in and still age-gated, or web_embedded UNPLAYABLE (embedding disabled) → append
  `web_creator` (POT; bypasses EU age verification). No tv_embedded-style bypass remains. Maintainer
  advice for non-embeddable age-gated without cookies: "use a PO token provider and the `mweb` client"
  (bashonly, #17497, 2026-08-20). Open: #17542 (age-restricted → itag 18 only, "side effect of #17389").
- **"This video is not available"**: no generic handling; it is how visionos/android_vr answer kids
  (#16693, UNPLAYABLE; ERROR since July, #17252).
- **SABR-only**: a format with neither `url` nor a usable `signatureCipher` is dropped: "Some {client}
  client https formats have been skipped as they are missing a URL … YouTube is forcing SABR streaming
  for this client" (web/web_safari, debug) or "YouTube may have enabled the SABR-only streaming experiment
  for the current session" (others, warning). Tracking #12482 (locked).
- **missing_pot**: formats whose policy says GVS POT required, without one, are skipped
  (`formats=missing_pot` keeps them; HLS gets "MISSING POT" and source_preference −20).
- **encryptedHostFlags**: only for the `embedded` variant, from
  `WEB_PLAYER_CONTEXT_CONFIGS.WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER.encryptedHostFlags` of
  `https://www.youtube.com/embed/{id}?html5=1`, fetched **per video** with the client UA and
  `Referer: https://www.reddit.com/` ([`_download_ytcfg` L993](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_base.py#L993)).
  The /player context is **that page's own `INNERTUBE_CONTEXT`** (`_extract_context(player_ytcfg)`: its
  visitorData, clientVersion, etc.) with `thirdParty.embedUrl` forced to reddit; the X-Goog-Visitor-Id
  header is the first visitor seen (watch page), and one cookie jar (VISITOR_INFO1_LIVE, YSC) spans the
  watch page, embed page and /player — so it is one visitor end to end. Origin: PR #16177 (2026-03-10)
  fixed #16077 "Error code: 152 - 18"; without a referrer YouTube answers `Error 153 /
  PLAYABILITY_ERROR_CODE_EMBEDDER_IDENTITY_MISSING_REFERRER`, with a youtube.com referrer `152 - 4 /
  EMBEDDER_IDENTITY_DENIED`. bashonly kept an alternative flags source in reserve:
  `INNERTUBE_CONTEXT.thirdParty.embeddedPlayerContext.embeddedPlayerEncryptedContext`. Code comment:
  detectable via experiment `embeds_enable_encrypted_host_flags_enforcement`, "no harm in including
  encryptedHostFlags with all web_embedded player requests". bashonly (#16077): "The web_embedded client
  is routinely broken (and unbroken) by YT. This is why it's only used as a fallback".
- **Forced preroll wait** — see §7.
- **n / sig**: all JS solving via EJS — `yt-dlp-ejs` 0.8.0 (`yt.solver.core.js`, meriyah + astring; no
  release in the window) in deno (default, ≥2.3), node (≥22), bun (1.2.11–1.3.14) or quickjs, plus
  external JSC plugins. By default yt-dlp uses the `main` player variant (`player_ias.vflset/en_US/base.js`)
  instead of what the page prescribes (`player_js_variant=actual` keeps the page's); there is no `tcl` entry — gamer191 (2026-08-08): TVHTML5 now
  uses `tv-player-ias-tcl.js`, which "uses a different signatureTimestamp… yt-dlp-ejs can't yet solve its
  JS challenges"; bashonly: the real fix "demands some major changes to the Youtube extractor and EJS".
- **HLS vs DASH**: HLS manifest path has an `/n/<challenge>` segment that is solved and substituted
  (the manifest is dropped if unsolved) and gets `/pot/<token>` appended when a token exists
  ([L3685](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L3685)).
  web_safari/web_embedded pre-merged HLS is deprioritised −1 for VOD ("may have lower quality audio").
  Live/post-live use "adaptive" https fragments since #16771 (07-04) instead of the DASH manifest.
- **PO-token plumbing**: provider plugins (`pot/`, e.g. bgutil). Content binding ([`pot/utils.py`](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/pot/utils.py)):
  GVS → visitor_data (signed out) / data_sync_id (signed in), **or video_id when ytcfg's
  `serializedExperimentFlags` has `html5_generate_content_po_token=true`**; PLAYER and SUBS → video_id.
  WebPO clients: WEB, MWEB, TVHTML5, WEB_EMBEDDED_PLAYER, WEB_CREATOR, WEB_REMIX, TVHTML5_SIMPLY,
  TVHTML5_SIMPLY_EMBEDDED_PLAYER.

### 1.4 Wiki PO-token table (live, last content update 2026-03 by coletdjnz, wiki#71) — stale vs code

| client | POT required for | notes |
|---|---|---|
| web | Subs, GVS | only SABR formats |
| web_safari | GVS* | HLS doesn't need GVS POT "at this time" |
| mweb | GVS | |
| tv | not required | all DRM without cookies; SABR-only in some cases |
| tv_simply | GVS | no account cookies |
| web_embedded | not required | only embeddable videos |
| web_music | GVS | |
| web_creator | GVS | account cookies |
| android / ios | GVS or Player | no cookies |
| android_vr | not required (stale: code says required since 07-20, dead since 08-17) | kids not available |

No `visionos` / `tv_downgraded` rows yet. TL;DR on the page: "Use a PO Token Provider plugin to provide
the `mweb` client with a PO Token for GVS requests"; manual token extraction discouraged because tokens
are now "bound to the video ID". Community client table wiki#81 (unmerged) lists web_embedded as JS
required, no POT, HLS/https/SABR, embeddable only.

### 1.5 SABR in yt-dlp

PR [#13515](https://github.com/yt-dlp/yt-dlp/pull/13515) "[fd/sabr] Add YouTube SABR protocol downloader"
(coletdjnz, opened 2025-06-21) is **open, unmerged, conflicting**; 227 commits, last real work
2026-07-06…08-08 (visionos support, resume, `formats=sabr_live`). bashonly 2026-08-21: "a massive PR and I
need to carve out some serious time". Test build: `yt-dlp --update-to bashonly/yt-dlp@sabr`. Notes in
the PR: `web` SABR still needs a GVS POT; server-side ad wait applies; cold-start POT "out of scope".

### 1.6 Other yt-dlp issues worth knowing (maintainer statements)

- #17226 (07-15): coletdjnz "android_vr is rolling out a pot-like requirement, visionos does not have
  this"; gamer191: visionos has itag 616 (= Premium 1080p enhanced bitrate); only visionos has itag 602.
- #17143 (closed 07-20): bashonly "non-live m3u8 formats are just being gated behind login now …
  android_vr and mweb still see m3u8 formats for livestreams when logged-out"; 07-21 YouTube is
  "phasing out or locking down these m3u8 formats". Contributor: visionos "gives m3u8 without cookies or
  pot or js" (separate video/audio tracks, not muxed).
- #17509 (08-21) bashonly: android_vr "was the client that provided combined video+audio m3u8 formats
  for livestreams"; `android` is a live-only workaround.
- #15583: bashonly — the tv LOGIN_REQUIRED botgate is "caused by the generic Cobalt user-agent"; a guest
  cookie file aged ~3 days lets tv/tv_downgraded work logged out; 06-20 "logged-out tv_downgraded now
  works".
- #16764 (05-20) bashonly: "none of the IOS client formats are expected to be downloadable" without POT;
  IOS VOD m3u8 vanished ~05-18.
- #17232: coletdjnz — datacenter IPs "tend to be blocked… can show as an immediate 403".
- #17666 (open, 09-10): mweb and web_embedded SABR-only in some sessions except itag 18; log shows
  "Detected experiment to bind GVS PO Token to video ID for web_embedded client"; gamer191 09-16: after
  c7fb478 web_embedded "should also get formats 91-96 … up to 1080p although they have worse codecs.
  mweb still only gets format 18".
- #17603 (closed 09-24, not adopted): mweb+POT gets age-restricted formats (contributor); with the SABR
  build `web` SABR formats still need a GVS POT; itag 18 already gone for some videos.
- Third-party data point (2026-09-23, ChannelFinWatcher #55): with cookies, kids videos (Pocoyó
  iK3FfLBmU78) got visionos/tv_downgraded/web_embedded UNPLAYABLE on 2026.08.19 (pre-Safari-UA) and fell
  to itag 18; `player_client=default,web_safari` restored 1080p (itag 96).


## 2. NewPipeExtractor family (NewPipe, LibreTube, Piped, PipePipe, Tubular)

### 2.1 NewPipeExtractor (TeamNewPipe) [code]

| line | /player clients | details |
|---|---|---|
| **dev** [`eb53b79`](https://github.com/TeamNewPipe/NewPipeExtractor/tree/eb53b79e6242d52f0ee2c2614e04e5a9dc2b6a64) (2026-09-27; NewPipe nightly pins `13a655f`, 08-27) | **VISIONOS only** for streams; WEB (`$fields=microformat,…`) for metadata | VISIONOS `1.04`, Apple `RealityDevice17,1`, osName visionOS, osVersion `26.6.0.23O770`, platform MOBILE, clientScreen WATCH, UA `com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; <CC>)` (no space before `(`). `POST youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t=<12 random>&id=<vid>`, headers only UA + `X-Goog-Api-Format-Version: 2`; visitorData from `/visitor_id` with the same client; body `videoId, cpn, contentCheckOk, racyCheckOk` — **no playbackContext, no sts**. [YoutubeStreamHelper L73](https://github.com/TeamNewPipe/NewPipeExtractor/blob/eb53b79e6242d52f0ee2c2614e04e5a9dc2b6a64/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeStreamHelper.java#L73) |
| **v0.26.5** (2026-08-15; NewPipe 0.29.1) | ANDROID `reel/reel_item_watch` → IOS (opt-in, off) → VISIONOS 1.02 (`RealityDevice14,1`, `25.6.0.23O471`) → WEB metadata | ANDROID `21.03.36`, UA `com.google.android.youtube/21.03.36 (Linux; U; Android 15; <CC>) gzip`, body `playerRequest{videoId,cpn,contentCheckOk,racyCheckOk}`, `disablePlayerResponse:false`, `$fields=playerResponse`; a playability error here is fatal |

- PO token: `setPoTokenProvider()` is a no-op on dev "until SABR support is added"; NewPipe's WebView
  provider returns null for ANDROID/IOS/embed. `&cpn=` (and `&pot=` if present) on stream URLs.
- JS: regex-extracted sig/n run in **Rhino 1.8.1**. Open PR #1545 (09-08) ports yt-dlp EJS n-sig parsing
  and makes the JS runtime pluggable (WebView).
- Kids: PR #1508 (06-09): VISIONOS added; "Videos made for kids cannot be watched with the VISIONOS
  client, so only the 360p muxed stream … can be got." PR #1529 (merged 08-20, `9ed62db`): removes
  ANDROID, IOS and WEB_EMBEDDED_PLAYER ("nobody is able to generate poTokens for first two";
  WEB_EMBEDDED "requires some parameters the extractor doesn't currently support") → "DASH manifests …
  and videos made for kids cannot be played until we can support SABR". The removed embed code had
  `thirdParty.embedUrl=watch URL` + `contentPlaybackContext{signatureTimestamp, referer}` and **no
  encryptedHostFlags**.
- Age-restricted: `AgeRestrictedContentException`, no bypass. "a bot" → SignInConfirmNotBot.
- HLS: `getHlsUrl()` reads VISIONOS (dev) / VISIONOS→IOS→ANDROID (v0.26.5); token as `?pot=` query.
- Timing: no ad parsing, no delay.
- [issue] NewPipe #13824/#13841/#13855 (Sept): 403 at ~59 s on 0.29.1; #13769: kids 360p until SABR;
  #12248 SABR coordination (no code).

### 2.2 LibreTube [code]

- Release v32.0/32.1 (2026-08-20) "implemented SABR"; master `abd9073` (09-28) **removes Piped support**
  ([e7812c7](https://github.com/libre-tube/LibreTube/commit/e7812c76bb8068ae0805253835b4d535f14235db)).
- Extractor: fork `libre-tube/NewPipeExtractor@3e863d7` (08-23, before #1529). VISIONOS first
  ([37c0cc7](https://github.com/libre-tube/NewPipeExtractor/commit/37c0cc7abde265abd9ce4581be0fda9d09353138):
  "Android … recently started to require poTokens"), IOS optional, WEB metadata; exposes
  `serverAbrStreamingUrl`(+cpn) and `videoPlaybackUstreamerConfig`.
- Playback order (`OnlinePlayerService`): **SABR** (non-live) → locally built DASH from non-SABR formats
  → HLS (VISIONOS hlsManifestUrl) only if nothing else.
- SABR stack: Kotlin on **media3**, ~2.3k lines — `SabrClient`, `UmpParser`, `SabrMediaSource/Period`,
  `SabrChunkSource`, protos under `app/src/main/proto/video_streaming`, SABR downloads;
  `streamerContext.clientInfo` hard-coded to VISIONOS 1.02; PO token = cached WEB token from its WebView
  BotGuard (often empty); sends MediaCapabilities (09-07), buffered ranges, playback cookie; honours
  backoff ([SabrClient.kt L359](https://github.com/libre-tube/LibreTube/blob/abd907390b016504e6954e5546aa370a8f50526c/app/src/main/java/com/github/libretube/player/parser/SabrClient.kt#L359)).
- Kids: no workaround (VISIONOS). Open: #8630 "fall back to DASH/HLS if SABR fails", #8760 buffering.

### 2.3 Piped (TeamPiped/Piped-Backend) — stalled

Last commit 2026-05-30; pinned `FireMasterK/NewPipeExtractor@c83884e` (pre-VISIONOS: ANDROID reel,
optional IOS, WEB metadata); `BgPoTokenProvider` = external bgutil server, WEB tokens only; no SABR.
Piped #4257 (08-03): maintainer has a private SABR solution, looking at `TeamPiped/rustypipe` (Rust).
Open PR #895 (09-27) bumps NPE.

### 2.4 PipePipe (InfinityLoop1308) — the most actively fighting [code]

Extractor `c68e10e` (v5.4.0, 2026-09-24), client `c2a166f`.

| mode | request | fallback |
|---|---|---|
| signed-out default `visionos` (since 08-06, [3b2aa5e](https://github.com/InfinityLoop1308/PipePipeExtractor/commit/3b2aa5e171171e14385260f0319ea45ff592f4a2)) | VISIONOS 1.02 / `RealityDevice14,1` / `25.6.0.23O471`, googleapis, `/visitor_id` visitor | **in parallel** ANDROID `reel/reel_item_watch` (21.03.36, osVersion 16, sdk 36); on VISIONOS error ("not available", kids) or no streams → reel's muxed `formats` (360p) ([fc1a3c3](https://github.com/InfinityLoop1308/PipePipeExtractor/commit/fc1a3c3f05c20a3b1970d3f97cd7f39f2151dc75)) |
| `mweb (SABR)`, forced when signed in ([App.java L160](https://github.com/InfinityLoop1308/PipePipeClient/blob/c2a166f7df05e5ace4c80fb3e3fd27702cfc3e56/app/src/main/java/org/schabi/newpipe/App.java#L160)) | MWEB, clientVersion + visitorData from the WebView attestation bootstrap, iPad Safari UA, `contentPlaybackContext{html5Preference, signatureTimestamp}`, **`serviceIntegrityDimensions.poToken`**, `utcOffsetMinutes 0`, `timeZone UTC` | SABR with `streamerContext.clientInfo` = MWEB (id 2); plays kids at full quality |
| hidden `tv_simply` / `tv_downgraded` | TVHTML5_SIMPLY 1.0 (id 75) / TVHTML5 5.20260114 Cobalt/Version | tv_downgraded removed from UI 08-24 ("unavailable for some accounts", yt-dlp #17389) |

- PO token: `LocalDomPoTokenProvider` + `assets/sabr_po_token.js` (WebView BotGuard, content- or
  session-bound, 07-19 `9d962af`); rejected attestation identities rotated (08-02).
- JS: yt-dlp **EJS** solver in an Android WebView (`WebViewJavaScriptDecoder`), fallback remote
  `api.pipepipe.dev/decoder/decode`.
- SABR: Java, `services/youtube/sabr/**` (~4–5.8k lines incl. client), rewritten 08-11 (media3 dropped
  → ExoPlayer2); v5.2.5 (08-07) disabled SABR, v5.3.0 (08-24) restored it.
- Timing: the only NewPipe-family project that parses ads (yt-dlp and FreeTube do too) — `updateAvailableAt()` sums `length_seconds`
  (`skipOffsetMilliseconds` overrides) over START adPlacements + BEFORE_CONTENT adSlots
  ([L2037](https://github.com/InfinityLoop1308/PipePipeExtractor/blob/c68e10e2e97495877832d8df6cbac55478083019/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java#L2037)),
  but only attaches it to SABR streams and the client never reads it. What it does enforce is the SABR
  server backoff (`NextRequestPolicy.backoffTimeMs`, capped at 30 s, with a "YouTube forces us to wait N
  seconds" notification — [SabrBackoffCoordinator](https://github.com/InfinityLoop1308/PipePipeClient/blob/c2a166f7df05e5ace4c80fb3e3fd27702cfc3e56/app/src/main/java/org/schabi/newpipe/player/SabrBackoffCoordinator.java)).
- HLS VOD: only post-live (n solved in the `/n/` path), no pot.

Tubular: discontinued (README 2026-07-07); `feuerswut/Tubular-Revived` follows NewPipe 0.29.1.


## 3. YouTube.js, googlevideo (SABR), FreeTube, Grayjay, Invidious — and SABR on media3

### 3.1 LuanRT/YouTube.js (youtubei.js) — v18.1.0 (2026-09-22), HEAD `bad89d2` [code]

Clients (`src/utils/Constants.ts`): IOS `20.11.6` (iPhone10,4, iOS 16.7.7.20H330), WEB `2.20260623.01.00`,
MWEB `2.20260205.04.01`, WEB_KIDS `2.20260205.00.00`, WEB_REMIX `1.20250219.01.00`, ANDROID `21.03.36`
(SDK 36), ANDROID_VR `1.65.10` (Quest 3, SDK 32), VISIONOS `1.02` (RealityDevice17,1, 26.5.23O471, Safari 26
UA; added 08-12 `e518645`), TVHTML5 `7.20260311.12.00`, TVHTML5_SIMPLY `1.0` (**id 74** in
`CLIENT_NAME_IDS`), TVHTML5_SIMPLY_EMBEDDED_PLAYER `2.0` (EMBED, embedUrl youtube.com),
WEB_EMBEDDED_PLAYER `1.20260206.01.00` (EMBED, embedUrl google.com — stale `1.x` prefix), WEB_CREATOR
`1.20241203.01.00`, ANDROID_CREATOR `22.43.101`, ANDROID_MUSIC `5.34.51`. (VoidTube's `innertube/utils/
Constants.kt` is derived from this file — hence VoidTube's id 74 and stale WEB_CREATOR/WEB_REMIX.)
`getInfo`/`getBasicInfo` default to WEB (`Session.ts:384`). PO tokens are caller-supplied →
`serviceIntegrityDimensions.poToken`; no minting, **no encryptedHostFlags**. Deciphering: downloads
`player_es6.vflset/en_US/base.js`, extracts n/sig with a meriyah AST, evaluates via a caller-provided
`Platform.shim.eval`. SABR: only exposes `server_abr_streaming_url` and `sabr://` DASH output for
googlevideo. Open PR #1248: `BotGuardManager` (page + API challenges, ytcfg EVENT_ID).

### 3.2 LuanRT/googlevideo — the reference SABR client [code]

Release v4.1.1 (2026-07-13); HEAD `44e360a` has an unreleased rewrite (#54, 09-23: livestreams, memory
leaks, corrected proto names; #56 09-24 "ignore unused streams").
- **UMP framing** (`UmpReader.ts`): varint whose length comes from the first byte (<0x80→1, <0xC0→2,
  <0xE0→3, <0xF0→4, else 5 bytes), then part type, size, payload; MEDIA/MEDIA_END start with a 1-byte
  header_id.
- **Request**: POST to the n-deciphered `serverAbrStreamingUrl` + `&rn=N`, `content-type:
  application/x-protobuf`, `accept: application/vnd.yt-ump`, `accept-encoding: identity`.
- **VideoPlaybackAbrRequest**: `client_abr_state` (1), `initialization_format_ids` (2),
  `buffered_ranges` (3), `video_playback_ustreamer_config` (5, from
  `playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig`), selected audio/video format ids (16/17),
  captions (18), `streamer_context` (19: `client_info{client_name id, client_version, os…}`, `po_token`=2,
  `playback_cookie`=3, `sabr_contexts`=5, `unsent_sabr_contexts`=6), `ssap_playback_infos` (24).
  `EnabledTrackTypes {VIDEO_AND_AUDIO=0, AUDIO_ONLY=1, VIDEO_ONLY=2}` (`formatUtils.ts`) — VoidTube's
  STATUS.md says "there is no video-only value": recheck.
- **Server messages**: wait `NextRequestPolicy.backoff_time_ms` and echo its playback cookie;
  `SABR_REDIRECT` swaps the URL; `RELOAD_PLAYER_RESPONSE` → re-call /player with
  `playbackContext.reloadPlaybackContext`; `SABR_CONTEXT_UPDATE` + sending policy; `SABR_ERROR` throws;
  `CUEPOINT_LIST` ads echoed with AdState RATECONTROL_CLIENT (11).
- **PO token in SABR**: the **content-bound (video-id) token, base64url-decoded to raw bytes**, in
  `StreamerContext.po_token`; never a `pot` query param (googlevideo `SabrStream.buildRequestBody`,
  Grayjay `SabrSession.resolvePoToken`, SmartTube `SabrProcessor.createStreamerContext`; YouTube.js
  `Player.ts:204` skips `pot` when `sabr=1`). For WEB/MWEB the same token also goes into /player
  `serviceIntegrityDimensions.poToken`.
- **The ~60 s wall = stream protection status (`sps`)**: 1 = OK; 2 = attestation pending (a cold-start
  or missing token still gets ~1–2 MB, then the client must re-mint); 3 = attestation required (nothing
  more). googlevideo re-mints via `onMintPoToken` up to 5 times. Matches VoidTube's positional 56–60 s wall
  (HANDOFF §28). LuanRT (googlevideo #38, 2025-10-07): web now needs content-bound tokens; the TV client
  "doesn't use PO tokens yet". Cold-start token: BgUtils `WebPoMinter.createColdStartToken` (MSC has
  `PoTokenGate.getColdStartPoToken`). ~60 s cutoff reports #38/#45/#52/#53; #53 fixed (2026-08-22) by
  page-challenge minting.
- **Onesie**: only an example (`examples/onesie-request`, TV `onesieHotConfig` key); nobody uses it in
  production.
- Users: FreeTube (protos + UmpReader with its own Shaka plugin), invidious-companion (URL helpers).

### 3.3 FreeTube — HEAD `9a1dd60`, v0.25.3-beta (2026-08-28) [code]

`youtubei.js ^18`, `googlevideo ^4.1.1`, `bgutils-js ^4.0.3`, Shaka 5.1. Client **WEB**, from the watch
page's ytcfg INNERTUBE_CONTEXT + `ytInitialPlayerResponse` (`src/renderer/helpers/api/local.js:441-500`);
WEB_EMBEDDED only as the age-gate fallback. Content-bound PO token minted in Electron (hidden
`WebContentsView`) from the page's `ytAtN` + `window.yt={config_}`, `/att/get`+`eacrToken` fallback
(`src/main/poTokenGenerator.js`). SABR via its own Shaka scheme plugin (segment index from sidx/Cues;
marks the other track fully buffered so each response carries one segment; `sps`=3 invalidates the
token). SABR for VOD since #8047 (2025-09-19), always on since #8542 (2026-01-18); #9689 redirect fix
(08-25), #9753 robustness (09-10). **Preroll wait**: reads `adSlots` BEFORE_CONTENT (skip offset else
length_seconds) into `adEndTimeUnixMs` (`local.js:778`) and delays loading with a "Remaining preroll-ad
time" toast — legacy/DASH only; for SABR it honours `backoffTimeMs`. Live: DASH/HLS manifests with
`/pot/` in the path; #9545: YouTube sometimes forces SABR for live (no manifest).

### 3.4 Grayjay — plugin `847a46f` (v364), app futo-org/grayjay-android `d033244` [code]

Primary /player: WEB ytcfg from youtube.com (MWEB + iPad UA when logged in). Also VISIONOS `1.04` (UA
`com.google.visionos.youtube/1.04 (RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US) gzip`, on
since 08-24), ANDROID_VR `1.65.10` (off since 08-22), ANDROID `21.03.36`. **Native UMP/SABR in Kotlin on
media3 1.9.0** since 2026-07-14, on by default since 08-22; streamer context WEB `2.20250923.08.00`, osName
Windows. Token session-bound (visitorData) or video-bound per player config, BotGuard in a WebView with a
V8+JSDOM fallback, plus a cold-start token; `sps`=3 → `SabrBlockedException` and a reload
(`SabrSession.kt:1002`). HLS only for live (`?pot=`). License "Source First 1.1" (non-commercial) —
read, don't copy.

### 3.5 invidious-companion / Invidious [code]

Companion `bb3b37f`: /player WEB + content-bound token, falling back to **TV_SIMPLY → ANDROID_VR → MWEB**
when `adaptiveFormats[0].url` is missing; TV when OAuth is on (`src/lib/helpers/youtubePlayerReq.ts`).
Token from a jsdom worker (bgutils 3.2.0) using the **old `/att/get` challenge**. YouTube.js 18.0.0
(09-05). No SABR (proxies `/videoplayback`; UMP proxy issue #10 open since 2024). Invidious: SABR issue
#5263 open.

### 3.6 SABR on Android/media3 — what exists and what it would take

| project | language / player | license | size | state |
|---|---|---|---|---|
| yuliskov/SmartTube `exoplayer-amzn-2.10.6/library/sabr` | Java, ExoPlayer 2.10.6, port of yt-dlp's SABR processor | MIT | ~8.6k LOC | active (f406bc7 09-17); yuliskov 09-09: "very poor … live or past live videos are completely unplayable" |
| VoidTube `sabr-media3` | media3 1.10.1 | MIT | — | opt-in, off in release (dies ~60 s without POT except VISIONOS) |
| LibreTube `player/` | Kotlin, media3 | GPL-3.0 | ~2.3k LOC | shipped v32 (08-20), VOD only, VISIONOS context |
| grayjay-android `sabr` | Kotlin, media3 1.9.0 | Source-First 1.1 | ~5k LOC | default on since 08-22 |
| A-EDev/Flow `player/sabr` | Kotlin, media3 1.11.0 | GPL-3.0 | ~3.6k LOC | active 09-28; SABR only as an upgrade when direct streams top out <720p |
| PipePipeExtractor `sabr` + client | Java, ExoPlayer2 | GPL-3.0 | ~5k LOC | MWEB + WebView POT; rewritten 08-11 |
| innertubex `sabr` | Kotlin MP, audio only | GPL-3.0 | ~2.5k LOC | experimental; Metrolist ships `allowSabr=false` |
| chadacious/sabr-exoplayer | Kotlin, media3 1.4.1, DASH + `sabr://` DataSource | MIT | ~3.4k LOC | dormant since 2025-11 |
| felipeucelli/JavaTube | Java downloader | MIT | — | default VISION_OS since 09-06 |

Copy-safe for MIT VoidTube: SmartTube, googlevideo, sabr-exoplayer, JavaTube, yt-dlp (Unlicense). GPL and
Grayjay: reference only.

Architectures: (a) custom MediaSource/MediaPeriod + ChunkSampleStream fed by a session pump (Grayjay,
LibreTube, SmartTube, VoidTube `sabr-media3`); (b) DASH manifest with `sabr://` URIs + DataSource
(sabr-exoplayer; Shaka analogue in FreeTube); (c) one progressive source per track + merge (Flow;
simplest, no ABR).

Checklist beyond what VoidTube's `sabr-media3` already does (UMP, request builder, VISIONOS end-to-end):
(1) a /player client that serves the wanted video by SABR — for kids that means WEB/MWEB (+ content POT)
or WEB_EMBEDDED; (2) `client_info` in `streamer_context` identical to the /player client (Flow: extra OS
fields on web trigger reloads); (3) POT bytes (content-bound, decoded) + cookie echo + SABR contexts;
(4) `sps` 2/3 → re-mint; (5) RELOAD_PLAYER_RESPONSE handshake keeping buffers and CPN; (6) SABR_REDIRECT,
URL expiry, fallback hosts; (7) backoff (= the ad wait, see §7); (8) a minter that uses the homepage
`ytAtN` challenge + EVENT_ID with the same UA/visitor as /player. **Estimate: ~1.5–2.5 focused weeks** on
top of VoidTube's existing module (mostly the POT pipeline, `sps`, reload, expiry and a kids test sweep);
4–8 weeks from scratch.


## 4. ReVanced / Morphe / anddea / rvx ("spoof video streams")

Where the code lives: **ReVanced** GitHub repo (and 702 forks) DMCA-blocked since 2026-03-24 (notice filed
by a Morphe team member); development on gitlab.com/ReVanced/revanced-patches, dev `bac650ff8`
(2026-08-29), latest release v6.2.1 (2026-06-02). **Morphe**: github.com/MorpheApp/morphe-patches, dev
`fccde737f` (2026-09-28), v1.44.0 (2026-09-21); inotia00 wrote nearly all spoof commits. **inotia00/rvx**:
end-of-life 2026-03-10. **anddea**: active, v4.3.0 (2026-09-28), playback code = Morphe's, a few days
behind.

**How they differ from VoidTube structurally**: they hook the official app's own /player, fire the spoofed
request alongside it and swap in only `streamingData` (+ Morphe `playerConfig`). Ads come from the
stock response, so the stock app runs its own preroll timing — they never need a wait of their own.
Field masks: Morphe `player?fields=responseContext.visitorData,playabilityStatus,streamingData,playerConfig&alt=proto`;
ReVanced `fields=streamingData&alt=proto` or reel `fields=playerResponse.playabilityStatus,playerResponse.streamingData`.
None sends `adPlaybackContext`. No spoof client uses `hlsManifestUrl` for VOD ("HLS … can theoretically be
played with ExoPlayer, but the related code has not yet been implemented"), none puts a POT on HLS.

### 4.1 Morphe [code] (`extensions/shared-youtube/library/src/main/java/app/morphe/extension/shared/spoof/ClientType.java` @ fccde737f)

| enum | clientName (id) | clientVersion | device / OS | SABR | login | notes in code |
|---|---|---|---|---|---|---|
| VISIONOS_1_02 **(default)** | VISIONOS (101) | 1.02 | Apple RealityDevice14,1, visionOS 26.6.1 | no | none | can't play Kids/Paid/Movie/Private/Age; no AV1; "may stop working at any time" |
| VISIONOS_1_03 | VISIONOS (101) | 1.03 | RealityDevice17,1, visionOS 26.6.1 | no | none | AV1 (picked when AV1 on, Force AVC off) |
| TV_SIMPLY | TVHTML5_SIMPLY (75) | 1.1 | Sony PS4, GAME_CONSOLE | no | optional | "Can't play: none"; needs JS + PoToken when signed out |
| TV_SABR | TVHTML5 (7) | 7.20260707.07.00 | Sony PS4 "PlayStation 4", GAME_CONSOLE | yes | optional | needs JS; plays kids (#3078) |
| TV_DASH | TVHTML5 (7) | **5.20150304** | Samsung SmartTV, Tizen 2.4.0, platform TV | no | optional | needs JS; only as TV_SABR's live fallback |
| ANDROID_VR_SABR | ANDROID_VR (28) | 1.73.21 | `…vr.pico`, Pico A8110, Android 10, SDK 29 | yes | **OAuth required** | no Kids, no AV1; multi-audio |
| ANDROID_VR_DASH | ANDROID_VR (28) | **1.64.34** | same | no | OAuth required | "supports dash streams"; no audio-track menu |
| ANDROID_XR_SABR / _DASH | ANDROID_VR (28) | 1.73.21 / **1.69.27** | `…youtube.xr`, Samsung SM-I610, Android 14, SDK 34 | yes / no | OAuth required | AV1 variants |
| ANDROID_CREATOR | ANDROID_CREATOR (14) | 26.10.000 | Pixel 10 Pro XL, Android 16, SDK 36 | no | login required | plays kids; no live/AV1/HDR; 720p max |
| ANDROID_MUSIC_NO_SDK | ANDROID_MUSIC (21) | 7.12.52 | `…apps.youtube.music/7.12.52 (Linux; U; Android <rel>) gzip`, no sdk | no | YT Music | – |
| ANDROID_MUSIC_REEL | ANDROID_MUSIC (21) | 9.05.52 | real device | yes | YT Music | `reel/reel_item_watch` |

- Fallback (YouTube): chosen → TV_SIMPLY → VISIONOS_1_02 → ANDROID_CREATOR (defaults: visionOS →
  TV Simply → Android Studio). JS clients skipped without a JS engine (`androidx.javascriptengine`).
  Rejects a response if status≠OK, no streamingData or no adaptiveFormats.
- Request: `POST youtubei.googleapis.com/youtubei/v1/player?fields=…&alt=proto`, headers
  X-YouTube-Client-Name (numeric), version, `X-GOOG-API-FORMAT-VERSION: 2`, X-Goog-Visitor-Id. **Visitor
  id per client** from `/visitor_id` (TV_SABR: `/guide`), cached 30 days, refreshed from every response
  (#2283/#2309: one visitor shared across clients raises the bot score). JS clients also send
  `configInfo.appInstallData:""`, `user.lockedSafetyMode:false`, `contentPlaybackContext{referer
  https://www.youtube.com/tv#/watch?v=ID, html5Preference, signatureTimestamp}`,
  `devicePlaybackCapabilities{supportsVp9Encoding:true, supportXhr:true}` (**changed false→true on
  2026-02-18, `59a4061b2`**). Player JS from `/iframe_api`, default variant `house_brand_player`.
- **TV Simply PoToken (PR #2533, 2026-08-21/22)**: cold-start challenge from
  `https://www.youtube.com/tv_config?action_get_config=true` ("seems to be valid only on TV Simply") →
  WebView BotGuard → `POST /api/jnn/v1/GenerateIT` (request key `O43z0dpjhgX20SCx4KAo`, Tizen 8 UA, key
  `AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw`) → token bound to **video id** in
  `serviceIntegrityDimensions.poToken` + token bound to **visitor id** as `&pot=` on every format URL;
  challenge cached ~5 h 55 m.
- Dated: 07-09 default → TV (Tizen 5.20150304); 07-20 #2094 SABR support; 07-25 #2208 Android Reel
  removed ("now always requires a PoToken"), default → visionOS; 08-05 #2309 VR Quest → Pico/XR ("YouTube
  VR for Meta Quest is being used by too many alternative clients such as yt-dlp, SmartTube, and
  NewPipe"), TV → PS4 SABR, per-client visitors; 08-08 TV_DASH; 08-19 #2479 VR needs OAuth, DASH 1.64.34
  / XR 1.69.27; 08-22 #2533 TV Simply signed-out POT; 08-28 #2618 "PoToken provider" via external PotHelper
  (DroidGuard + keybox; microG tokens invalid for ~2.5 years); 08-31 visionOS UA →
  `com.google.visionosyoutube/1.0x (RealityDevice…; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip`.
- Issues: #3078 (09-20→22) TV Simply briefly failing, TV_SABR "can play Kids videos"; #3190 (open)
  PotHelper token stops at 1:00; #2283 VR "Sign in to confirm you're not a bot" after 1:00, blamed on
  visitorData; #441/#2272 "video may start late" on TV clients / kids start delayed. inotia00 (#468,
  08-02): newer PoTokens are bound to the video id; visitor-bound tokens still work only for MWEB and
  WEB_REMIX.

### 4.2 ReVanced [code] (GitLab dev `bac650ff8`)

| enum | clientName (id) | version | device | auth | endpoint | notes |
|---|---|---|---|---|---|---|
| ANDROID_REEL | ANDROID (3) | 20.26.46 ("20.44.38 seem to not work") | real device | yes | `reel/reel_item_watch` | used "since 2024 by NewPipe, SmartTube and Grayjay" |
| ANDROID_REEL_NO_AUTH **(default since 06-02)** | same | same | same | no | reel | – |
| ANDROID_VR_1_61_48 | ANDROID_VR (28) | 1.61.48 | Quest 3, Android 12, SDK 32 | no | player | no Kids/Paid/Movie/Private/Age |
| ANDROID_VR_1_43_32 | ANDROID_VR (28) | 1.43.32 | same | no | player | non-adaptive bitrate, no AV1 |
| ANDROID_CREATOR | ANDROID_CREATOR (14) | 23.47.101 | Pixel 9 Pro Fold, Android 15 | required | player | "can play … labeled 'for children'", no live/HDR |
| VISIONOS | VISIONOS (101) | 0.1 | RealityDevice14,1, visionOS 1.3.21O771 | no | player | "may stop working at any time" |

Fallback: chosen → CREATOR → VR_1_43_32 → VISIONOS (Reel excluded: "can take up to 1 minute for videos
start playback"). No playbackContext, no POT, no JS. Since 06-02 (`6a7ba9fce`) X-YouTube-Client-Name
carries the name string, not the id (possibly a bug). All of Reel/VR/visionOS carry "Video may stop at
1:00, or may not be available in some regions". Open MR !6979: YT Music 9.32.51 SABR "wants a
Proof-of-Origin token. microG's attestation token is rejected"; "ANDROID_REEL_NO_AUTH returns 403".

### 4.3 rvx (EOL, final state) and anddea

rvx: ANDROID_NO_SDK 20.05.46, ANDROID_MUSIC_NO_SDK 7.12.52, ANDROID_VR 1.47.48 (AV1: 1.54.20 Quest 3),
ANDROID_CREATOR 24.01.000, VISIONOS 0.1, TV 7.20251217.19.00 (Tizen 8 UA), **TV_LEGACY TVHTML5 5.20150304
+ `Mozilla/5.0 (Linux mipsel) Cobalt/9.28152-debug (unlike Gecko) Starboard/4`** ("can play SABR
format-only videos", `attest_botguard_on_tvhtml5: false`), TV_SIMPLY 1.1 (PS4); unused: TV_EMBEDDED
(TVHTML5_SIMPLY_EMBEDDED_PLAYER 2.0, id 85, "only embeddable videos"), MWEB, WEB_LEGACY 1.20160315 ("for
some reason SABR is not applied"). Its JS clients sent `supportXhr:false`. **"Bypass fake buffering"**
(3941a5d9, 2025-09-16, off by default): "When a video ad is blocked, GVS generate fake buffering
proportional to the ad duration (A/B testing)" → adds **`inline=1` to the /player query** and
`contentPlaybackContext.isInlinePlaybackNoAd:true` (`InnerTubeRequestBody.kt:144`,
`InnerTubeRoutes.kt:80-99` @ 54ce1d48). Code comment on ANDROID: "Requires a DroidGuard PoToken (if
the user is logged in) to play videos longer than 1:00."

anddea `b5a75d761`: same enum/fallback as Morphe; 07-15 YT Music default ANDROID_MUSIC_NO_SDK; 08-25 TV
Simply POT; 08-31 in-app PoToken provider replacing GMS's PoToken service with hard-coded AES key /
challenge bytes (no external app).


## 5. SmartTube upstream (yuliskov/MediaServiceCore `upstream/master`, since 2026-06-01)

`git fetch upstream` in the submodule (2026-09-28): `upstream/master` = `476357b4` (2026-09-27), **62 commits
ahead** of the fork's merge-base `ef98dcd8` (2026-06-27). Upstream's own ring today
([VideoInfoService.java](https://github.com/yuliskov/MediaServiceCore/blob/476357b4/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/videoinfo/V2/VideoInfoService.java)):

```
VISIONOS        // no url formats
TV_DOWNGRADED   // works with old UAs like old Cobalt and old Xbox (non-tcl players)
WEB             // Fix video clip blocked in current location
WEB_EMBED       // Restricted (18+) videos (not working)
WEB_SAFARI, IOS, GEO, MWEB, ANDROID_VR
// commented out: TV ("Supports auth"), ANDROID_REEL ("hangs on all engines"), TV_LEGACY, TV_EMBED,
// TV_SIMPLY, ANDROID_SDK_LESS
// TODO: TV clients are broken because of recently introduced '-tcl' player variant
```

Commits touching AppClient / VideoInfoService / QueryBuilder / PoToken since 2026-06-01 (`git log upstream/master`):

| date | commit | what |
|---|---|---|
| 06-05 | 71676e50 | initial-response service refactor |
| 06-10 / 06-19 | b7840d58 / ccbe282a | pot fallback fix; potokennp2 crash fixes |
| 07-08 | eaac1224 | change clients order |
| 07-10 | ef7dbd34 | pot: init checks |
| 07-18 / 07-20 | 4f350041 / 90ae880a | AppClients: client list and values (VISIONOS etc.) |
| 07-22 | f31f1024 | clients sorting |
| 08-08 | a722df75 | **`PoTokenWebView4`**: "V2 version of bgutil generator" = bgutil PR #243 homepage `ytAtN` challenge + ytcfg/EVENT_ID, `/att/get` fallback |
| 08-10 / 08-12 | 3d87d39e / 3d9521ff | potokennp2 generator #4; **`PoTokenSelector` → `PoTokenWebView4` ("use sabr compatible generator")** |
| 08-10…08-19 | c6e1d7f7, e42a7a9b, b3b1cd71, 29c40c2e, aab3db91 | SABR format metadata / audio-language detection (@Jupiops) |
| 08-11 | 858ce2c9 | "remove isAdaptiveFormatsBroken check since we almost fixed sabr" |
| 08-12 | 4d128db8 / bcb2e24e | QueryBuilder JSON validation; history fix |
| 08-13 | e130f1f9 | disable auto reorder |
| 08-21 | efc0e339 | temp fix for broken SABR live |
| 08-24 | daf417c3 | QueryBuilder: timestamp length check; alt cpn generator |
| 08-26 | 97f92d1f | ClientInfo carries the user agent |
| 08-31 | 082e2e48 | force switch next client |
| 09-03 | 28c3c819 | TV_DOWNGRADED as final fallback |
| 09-08 | 86c87883 | **"fix restricted videos by downgrading UA"**: `USER_AGENT_TV_DOWNGRADED = "Mozilla/5.0 (DirectFB; Linux x86_64) Cobalt/4.13031-qa (unlike Gecko) Starboard/1"` ("uses old 5 digits timeStamp format and NON-tcl player"; Xbox UA as commented alternative); TV sts suffix `+001` dropped ("downgraded UAs use the same timestamp format for WEB and TV"); decipher from `tv-player-ias.js` (f572e43c) |
| 09-11 | 394eebc5 / 82e9ccde | PoTokenGate warmup added then removed |
| 09-13 | 96cfe447 | client versions: WEB `2.20260907.06.00`, MWEB `2.20260907.05.00`, TV `7.20260901.15.00`, TV_DOWNGRADED `5.20260901`, WEB_EMBEDDED `2.20260908.01.00`; TV player-variant test list now includes the `tv-player-es6-tcl` variants ("not compatible with WEB, TV's unique decipher routines"). (WEB_REMIX `1.20260707.12.00` and IOS osName `iPhone` came with 4f350041, 07-18.) |
| 09-15 | 7c3caa93 | stop leaking OAuth refresh tokens to logcat (#44) |
| 09-18 | 9df453a0 | **Safari UA for web_embedded** |
| 09-21 | da8102d4 | modern TV UA back for thumbnails (TV_DOWNGRADED keeps the old Cobalt UA) |
| 09-26 / 09-27 | ebcb98a8 / 476357b4 | FormatInfoWrapper refactor; history fix, original track selection fix |

Notes:
- Upstream already sends `encryptedHostFlags` for embedded clients (`YtCfgService.getCachedEncryptedHostFlags`,
  since ~2026-05-03 `4175ffaf`) but **not paired with the embed page's visitor** and with
  **`supportXhr = !isTVClient`** (i.e. `true` for WEB_EMBED) — the two things VoidTube found necessary.
  Its ring comment still says WEB_EMBED "not working".
- Upstream body for every client: `contentPlaybackContext{html5Preference, lactMilliseconds:60000,
  isInlinePlaybackNoAd:true, signatureTimestamp, encryptedHostFlags?}` +
  `devicePlaybackCapabilities{supportsVp9Encoding:true, supportXhr: !isTV}`. No ad parsing / wait.
- iOS HLS for "extended/high-bitrate formats" on VOD is an opt-in format setting
  (`MediaServiceData.FORMATS_EXTENDED_HLS`, `hasExtendedHlsFormats`).
- SABR lives in the SmartTube app repo, `exoplayer-amzn-2.10.6/library/sabr` (Java, ~8.6k LOC, port of
  yt-dlp's SABR processor; see §3.6). Community: Jupiops' SABR test build `32.11-sabr.1` (2026-08-09,
  SmartTube #6030). yuliskov 2026-09-09 (#6030): "the 99 percent of issues are related to resent
  switching to sabr. The current state of sabr implementation in our app is very poor. E.g. live or
  past live videos are completely unplayable"; 09-12: "YouTube made an account lock for smart TV's"
  (support thread 466491931). Latest WIP builds 32.57/32.59 (09-25/27).


## 6. YouTube Music Kotlin apps and other libraries

### 6.1 MetrolistGroup/innertubex (library behind Metrolist) — v0.7.2 `195bac9` (2026-09-28) [code]

Kotlin Multiplatform; the catalog ([YouTubeClient.kt](https://github.com/MetrolistGroup/innertubex/blob/195bac930073233fd2450edc5589033528cccd43/src/commonMain/kotlin/com/metrolist/innertubex/models/YouTubeClient.kt),
[PlaybackClientCatalog.kt](https://github.com/MetrolistGroup/innertubex/blob/195bac930073233fd2450edc5589033528cccd43/src/commonMain/kotlin/com/metrolist/innertubex/extraction/strategy/PlaybackClientCatalog.kt))
records per-client benchmark evidence (Android benchmark 2026-08-13, SABR benchmark 08-20). Metrolist
(app `28fa8f0`) pins v0.7.0 and calls `extract(allowHls=false, allowSabr=false)` — direct URLs only.

| client (priority) | version / fields | transport | benchmark note |
|---|---|---|---|
| WEB_REMIX (70) | 1.20260707.12.00, Firefox 140 UA, sts | direct/HLS, GVS POT | normal, kids, age-gated ("Works for normal, explicit, and kids content when signed in") |
| **WEB_EMBEDDED_PLAYER (65)** | 2.20260708.00.00, replaced by the embed page's `INNERTUBE_CLIENT_VERSION`; Firefox UA `Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0` | direct/HLS, **no POT** | normal + kids passed sustained playback and both seek directions; age-restricted rejected |
| WEB_CREATOR (55) | 1.20260708.06.00 | login only | normal, kids, age |
| WEB_KIDS (55) | 2.20260205.00.00, id 76 | direct | first choice when the video is known kids content; "Kept in inventory pending full playback validation" |
| TVHTML5_SIMPLY (42) | 1.0, id 75 | "two-token HLS" (`?pot=` + cpn) | normal + kids |
| WEB_REMIX_SABR 52 / WEB_SABR 44 / TVHTML5_SIMPLY_SABR 36 / MWEB_SABR 32 / WEB_SAFARI_SABR 28 / VISIONOS_SABR | SABR | "all 6 test cases" for WEB_REMIX/WEB SABR |
| VISIONOS (fast path) | 1.02, RealityDevice17,1, 26.5.23O471, Mac Safari UA | direct | "Clean Android sessions may stall, and current explicit and kids probes are rejected" |
| broken/probe | ANDROID 21.26.364 & IOS 21.26.4 ("SABR-only" without attestation); ANDROID_VR 1.65.10/1.61.48/1.43.32 ("every audio itag reached a CDN 403 after 1 MiB"); TVHTML5 7.x & TVHTML5_DOWNGRADED 5.20260707 ("UNPLAYABLE in August 2026"); VISIONOS **0.1** (HTTP 400 FAILED_PRECONDITION, `89f3352`, 09-28) | | |

Embed recipe ([YtConfigParserImpl.kt L39](https://github.com/MetrolistGroup/innertubex/blob/195bac930073233fd2450edc5589033528cccd43/src/commonMain/kotlin/com/metrolist/innertubex/extraction/YtConfigParserImpl.kt#L39),
[InnerTube.kt L621](https://github.com/MetrolistGroup/innertubex/blob/195bac930073233fd2450edc5589033528cccd43/src/commonMain/kotlin/com/metrolist/innertubex/InnerTube.kt#L621)):
GET `/embed/<id>?html5=1` (Firefox UA, `Referer: https://www.reddit.com/`, no cookies); regex
`encryptedHostFlags`, `visitorData`, sts, `INNERTUBE_CLIENT_VERSION` (abort if flags or sts missing);
/player with `context.thirdParty.embedUrl=https://www.reddit.com/`, **top-level
`thirdParty.embedUrl=https://www.youtube.com/embed/<id>`**, `contentPlaybackContext{html5Preference,
signatureTimestamp, encryptedHostFlags}`, `contentCheckOk`, `racyCheckOk`, **`videoCheckOk`**; headers
Referer reddit, `X-Goog-Visitor-Id`, Origin youtube.com; media with Referer/Origin youtube.com and 1 MiB
ranges. Its harness asserts **206 on the first ranged GET, no wait, no 403 retry** (`Playback.kt L395`) —
so on 2026-08-13 its (unpublished) samples had no preroll wait. Catalog note: fresh tokenized MWEB URLs
"can return several transient HTTP 403 responses before becoming usable". Fallback chain: VISIONOS fast
path → watch-page config candidates by priority (WEB_KIDS first for known kids) → embed config → WEB_KIDS
guess. JS: yt-dlp EJS on QuickJS (`quickjs-kt` 1.0.14). Metrolist app on media 403/410: invalidate URL,
mark client failed, refresh cipher, re-extract after `RETRY_DELAY_MS` (excludes the client rather than
waiting).

### 6.2 Others

- **SimpMusic** (`maxrave-dev/core@0b9ce7b`, 09-26): WEB_REMIX /player (1.20260304.03.00, sts = days since
  epoch) for metadata; streams from a PipePipeExtractor fork (VISIONOS + reel fallback) with a local QuickJS
  decoder → remote api.pipepipe.dev decoder → BravePipeExtractor; HEAD check of URLs.
- **ArchiveTune** (rukamori/core): preferred client → VISIONOS **0.1** (now 400 per innertubex) →
  ANDROID_VR 1.65.10 → WEB_REMIX → WEB → MWEB → WEB_CREATOR; `youtube_clients.json` has WEB_EMBEDDED
  2.20260708.00.00 flagged `isEmbedded`.
- **OuterTune**: no longer developed (points to Metrolist/ArchiveTune).
- **lavalink-devs/youtube-source** (Java, Discord bots): `NonMusicClient.fetchEncryptedHostFlags` scrapes
  `/embed/<id>` for the flags.
- **A-EDev/Flow** (Kotlin, media3 1.11): direct streams first, SABR only as a quality upgrade (<720p),
  3 s budget.
- **JavaTube** (Java downloader): default client VISION_OS since 09-06.


## 7. Timing and delivery types

Two Pixel-9 findings of 2026-09-28 frame this section: (1) WEB_EMBED (embed identity) answers OK for
made-for-kids _WB5hh7WOb4 with 26 URL formats, the first media request ~0.3 s later is **HTTP 403 (12/12,
LTE and Wi-Fi)**, the same URLs work **48–68 s later**; (2) for kids KUcmvVHh_RA on LTE, WEB_EMBED answers
OK with adaptive formats **SABR-only but with an `hlsManifestUrl`**.

### 7.1 The forced preroll wait ("fake buffering")

**What YouTube does.** iter.ca, "YouTube's fake buffering" (2025-06-20): InnerTube tells GVS to back off
on the **first** `/videoplayback` of the content — "InnerTube is providing GVS streams that will give a
backoff of 80% of the ad duration for ads for the first `/videoplayback` request" (15 s ad → 12 s; 6 s +
15 s unskippable → 16.8 s); applied to everyone in the A/B test, invisible to normal users because
the ad plays meanwhile. On SABR it arrives as `NextRequestPolicy.backoff_time_ms`; on URL formats it
shows up as 403s until the time passes (bashonly, 2026-08-18: not waiting "likely gives a 403"). rvx's
settings text: "When a video ad is blocked, GVS generate fake buffering proportional to the ad duration
(A/B testing)". VoidTube's 48–68 s window is consistent with 80 % of ~60–85 s of prerolls (or the
full ad time) — to be checked against the response's ad renderers.

**yt-dlp's `playback_wait` history** [code/PR]:
| date | change | behaviour |
|---|---|---|
| 2025-08-20 | a97f4cb, PR [#14081](https://github.com/yt-dlp/yt-dlp/pull/14081) "Handle required preroll waiting period" (fixing #13930 "The following content is not available on this app" / 403) | extractor arg `preroll_sleep`, default 6 s, applied to all non-live formats |
| 2025-08-20 | f63a7e4 | renamed to **`playback_wait`** ("Duration (in seconds) to wait inbetween the extraction and download stages in order to ensure the formats are available. The default is `6` seconds") |
| 2025-08-22 | 5c8bcfd, #14124 | timestamp taken before n/sig/POT work so solver time counts toward the wait |
| 2025-11-16 | 23f1ab3, #15066 | wait honoured for ffmpeg downloads |
| 2025-11-23 | 715af0c, PR [#14646](https://github.com/yt-dlp/yt-dlp/pull/14646) (fixes #14645: "yt-dlp waits for a default of 6 seconds to prevent getting 403-d by youtube 'fake buffering' … parse the ad information") | **`playback_wait` arg removed**; wait derived from the player response |
| 2026-06-26 | 3c279b3, [#17062](https://github.com/yt-dlp/yt-dlp/pull/17062) "Fix detection of forced preroll wait time" | also reads `adPlacements` |

**Current algorithm** ([`_get_available_at_timestamp` L3823](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L3823)),
per client response, all clients:
- renderers = `adPlacements[*]` where `adPlacementRenderer.config.adPlacementConfig.kind ==
  AD_PLACEMENT_KIND_START` → `adPlacementRenderer.renderer`, plus `adSlots[*]` where
  `adSlotRenderer.adSlotMetadata.triggerEvent == SLOT_TRIGGER_EVENT_BEFORE_CONTENT` →
  `fulfillmentContent.fulfilledLayout.playerBytesAdLayoutRenderer.renderingContent` (and each
  `playerBytesSequentialLayoutRenderer.sequentialLayouts[*]…renderingContent`) → `instreamVideoAdRenderer`;
- per ad: `skipOffsetMilliseconds/1000` if present ("YT allows skipping this ad; use the wait-until-skip
  time"), else `playerVars` → `length_seconds`;
- `available_at = ceil(now) + Σ`; applied to every non-live format **including HLS/DASH manifest
  formats**; the downloader sleeps until `max(available_at)` (`downloader/common.py:465`).

**Avoiding it**:
- `use_ad_playback_context=true` → `playbackContext.adPlaybackContext = {pyv: true}` — yt-dlp allows it
  only on `mweb` and `web_music` (PR #15220, 2025-12-03: "on some videos, using this in combination with
  some clients results in 403-s"; tester: web_embedded "did not work" on qoeLkZ_CExg); "Do NOT use this
  when passing premium account cookies" (loses premium formats).
- rvx "Bypass fake buffering" (3941a5d9, 2025-09-16): **`inline=1` query param on /player** +
  `contentPlaybackContext.isInlinePlaybackNoAd: true` (iter.ca: with it "InnerTube won't serve you any
  ads"). VoidTube (and SmartTube upstream) already send `isInlinePlaybackNoAd:true` in every body but
  **no `inline=1`** (`VideoInfoApi.java`: bare `/youtubei/v1/player`).

**Who waits before the first media request** (all [code]):
| project | behaviour |
|---|---|
| yt-dlp | computes `available_at` from ads (above), sleeps; SABR PR: `_check_vod_ad_wait` sleeps on backoff |
| FreeTube | `adSlots` BEFORE_CONTENT → `adEndTimeUnixMs`, delays player load with a "Remaining preroll-ad time" toast (legacy/DASH); SABR: honours `backoffTimeMs` |
| PipePipe | computes `availableAt` like yt-dlp but never uses it; enforces SABR backoff (cap 30 s) with a "YouTube forces us to wait N seconds" notification |
| LibreTube, innertubex, Grayjay, googlevideo | SABR backoff only; no wait for URL formats |
| NewPipeExtractor, YouTube.js, invidious-companion | nothing |
| ReVanced / Morphe / anddea | nothing — they reuse the stock app's ads/timing and only swap `streamingData` |
| SmartTube upstream | nothing; sends `isInlinePlaybackNoAd:true` |
| Metrolist | on 403/410: exclude client, re-extract after a delay |

### 7.2 HLS for VOD

| client | VOD HLS today | GVS POT on HLS | source |
|---|---|---|---|
| web_embedded + Safari UA | **itags 91–96 (144p–1080p, muxed H.264/AAC, "worse codecs")**, also when adaptive is SABR-only | none required (no policy) | gamer191 #17666 (09-16), c7fb478 |
| web_safari | 91–96, but "since 2026.07 only returned with some logged-in or 'trusted' sessions" | not required ("at this time"), recommended | `_base.py`, wiki, #17143 |
| visionos | m3u8 with **separate** video/audio tracks (incl. itag 602), no cookies/POT/JS | none | #17143, #17226 (contributors) |
| ios | none for VOD since ~2026-05-18; live only | **required** ("HLS Livestreams require POT 30 seconds in") | #16764, `_base.py` |
| mweb / android_vr / android | live only (android_vr dead since 08-17; `android` = live m3u8 workaround) | mweb: recommended | #17143, #17509 |
| TVHTML5_SIMPLY | innertubex "two-token HLS" passes kids | `?pot=` + cpn | innertubex catalog |

Mechanics (yt-dlp [L3685](https://github.com/yt-dlp/yt-dlp/blob/c7fb478/yt_dlp/extractor/youtube/_video.py#L3685)):
the manifest URL path contains `/n/<challenge>`; solve it with the player JS and substitute it (yt-dlp
drops the manifest if unsolved); append `/pot/<token>` to the path when a token exists (FreeTube does the
same for live; NPE and innertubex use a `?pot=` query instead). Pre-merged HLS is ranked below DASH/https
for VOD ("pre-merged m3u8 formats may have lower quality audio") but is a legitimate fallback. Timing
has stages: yt-dlp fetches and parses the master playlist during extraction, but applies `available_at`
only when downloading the chosen format — so for VoidTube, master playlist, media playlist and the first
segment can each fail independently; "a manifest was returned" does not yet mean "HLS plays".

Who uses HLS for VOD as a fallback: **yt-dlp** (web_safari/web_embedded, deprioritised), **LibreTube**
(VISIONOS HLS as third choice after SABR and local DASH), **innertubex** (when no direct formats, and
always for TVHTML5_SIMPLY), **SmartTube upstream** (opt-in iOS "extended HLS" formats), **NPE**
(`getHlsUrl`, VISIONOS). Not: ReVanced/Morphe ("HLS … related code has not yet been implemented"),
PipePipe (post-live only), FreeTube/Grayjay/invidious-companion (live only).


## 8. PO-token minting state (what changed under everybody)

- **Challenge bound to the page session (late July 2026).** LuanRT, BgUtils PR [#44](https://github.com/LuanRT/BgUtils/pull/44)
  (merged 2026-08-04, v4.0.3): "YouTube started binding the initial attestation challenge to the
  `yt.config_` object (specifically `yt.config_.EVENT_ID`). As a result, WebPO tokens generated using
  challenges from `/att/get` are now rejected if your session is part of the experiment and you are using
  the `WEB` or `MWEB` InnerTube clients". Fix: take the initial `window.ytAtN(...)` challenge and the
  `ytcfg` from the **same** youtube.com page and inject `yt.config_` before the BotGuard snapshot.
  EDIT 2 in the PR: "the attestation challenge from the TV client can be used. It doesn't require
  `EVENT_ID` (yet?)" — `GET /tv_config?action_get_config=true&client=lb4&theme=cl` →
  `challengeParams.R.bgChallenge` + `challengeRequestKey` → `GenerateIT`.
- **bgutil-ytdlp-pot-provider** issue #242 (07-27→08-21): "YouTube / GVS has occasionally rejected the
  tokens … responding with HTTP 403s after half a minute"; PR #243 (1.3.2, 2026-08-21) ports the homepage
  pair: stock `/att/get` **14/24 (58 %)** vs homepage pair **22/24 (92 %)** success; one extra homepage
  fetch per minter, not per token. 2.0.0 (09-08) security fixes only.
- **SmartTube upstream** ported it as `PoTokenWebView4` (a722df75 08-08, default since 3d9521ff 08-12).
  **VoidTube's fork still selects `PoTokenWebView`**, which uses `https://www.youtube.com/api/jnn/v1/Create`
  + `GenerateIT` (`potokennp2/generators/PoTokenWebView.kt:124,168`). Minting and binding are separate
  problems: VoidTube's streaming token is minted against the web visitorData
  (`PoTokenProviderImpl.kt` ~L193, `generatePoToken(webPoTokenVisitorData)`), and `PoTokenWebView4` keeps
  an `/att/get` fallback (L138), so selecting it does not prove the homepage path minted — log it.
- **GVS token binding moved to the video id** where the ytcfg experiment
  `html5_generate_content_po_token=true` is set (yt-dlp detects it and binds GVS to video_id; wiki: tokens
  are now "bound to the video ID"; inotia00 #468: only MWEB and WEB_REMIX still take visitor-bound tokens).
  SABR takes the content-bound token as raw bytes in `streamer_context.po_token`.
- **TV Simply cold-start token** (Morphe #2533): `/tv_config` challenge, video-bound PL token + visitor-bound
  GVS token, challenge cached ~6 h.
- **Android attestation** (DroidGuard) remains out of reach for everyone except Morphe's external
  PotHelper (keybox) and anddea's hard-coded provider; microG tokens have been invalid for ~2.5 years
  (Morphe #2618). This is why ANDROID/IOS/ANDROID_VR/ANDROID_REEL are dying for everyone.
- **invidious-companion** still mints from `/att/get` (bgutils 3.2.0) — expect it to hit the same 403s.


## 9. VoidTube vs the ecosystem: request-shape diffs worth knowing

| item | VoidTube (fork working tree 2026-09-28) | ecosystem | why it may matter |
|---|---|---|---|
| `devicePlaybackCapabilities.supportXhr` | `!isTVClient && !isEmbedded` → **true** for WEB, WEB_SAFARI, MWEB, IOS, VISIONOS, ANDROID* | yt-dlp/innertubex: block never sent; rvx: false; Morphe: true (wants SABR); upstream MSC: `!isTV` | measured: WEB_EMBED true → SABR-only, false → URLs. Unmeasured on the other clients |
| `isInlinePlaybackNoAd` / `lactMilliseconds` | sent to every client with an sts | yt-dlp: never; rvx: isInlinePlaybackNoAd **+ `inline=1` query** | the preroll wait (§7) |
| `/player` query | bare `/youtubei/v1/player` | Morphe/NPE: googleapis host + `fields=`/`alt=proto`, `t=`, `id=`; rvx: `inline=1` | ad data, fingerprint |
| embed identity | flags + visitor cached **6 h**, not per video; static body | yt-dlp/innertubex: embed page **per video**; yt-dlp replays the page's whole `INNERTUBE_CONTEXT`; innertubex uses the page's `INNERTUBE_CLIENT_VERSION`, top-level `thirdParty.embedUrl=/embed/<id>`, `videoCheckOk` | robustness if flags turn out per-video in some sessions |
| PO-token minter | `PoTokenWebView` (`jnn/v1/Create` challenge) | upstream MSC `PoTokenWebView4`, bgutil 1.3.2, BgUtils 4.0.3, FreeTube: homepage `ytAtN` + ytcfg EVENT_ID; Morphe: TV `/tv_config` challenge | GVS 403 ~30 s in for web tokens in the experiment |
| TVHTML5_SIMPLY client-name id | **74** (from YouTube.js) | yt-dlp, Morphe, PipePipe, innertubex: **75** | header/body mismatch is a cheap fingerprint |
| IOS | `clientName "iOS"`, osName `iOS` | yt-dlp `IOS` / osName `iPhone`; upstream MSC osName `iPhone` (07-18) | IOS is non-viable anyway without attestation |
| WEB_CREATOR version | `1.20241203.01.00` | `1.20260708.06.00` | only if ever used (needs cookies) |
| WEB_REMIX version | `1.20250219.01.00` | `1.20260707.12.00` (yt-dlp, upstream, innertubex) | candidate 5 |
| WEB / MWEB / WEB_EMBEDDED versions | 2.20260708.00.00 / 2.20260708.05.00 / 2.20260708.00.00 | upstream MSC 09-13: 2.20260907.06.00 / 2.20260907.05.00 / 2.20260908.01.00; yt-dlp still 20260708 | yt-dlp refreshes clientVersion from page ytcfg anyway |
| TV_DOWNGRADED UA | `Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version` | upstream: `(DirectFB; Linux x86_64) Cobalt/4.13031-qa … Starboard/1`; PR #17723 Tizen (= TV_TIZEN) | tcl experiment |
| visitor per client | shared web visitor for web family + ANDROID_VR + VISIONOS (`usesWebVisitorData`), embed-page visitor for WEB_EMBED, app visitor for the rest | Morphe: one `/visitor_id` visitor per client, refreshed from each response | bot score |
| ad-wait handling | none | yt-dlp, FreeTube | finding 1 |
| VOD HLS fallback | only iOS "extended HLS" opt-in (inherited) | yt-dlp, LibreTube, innertubex | finding 2 |
| SABR `EnabledTrackTypes` | STATUS.md says "no video-only value" | googlevideo: VIDEO_ONLY = 2 (Grayjay sends 2) | SABR correctness |

---

## Review (cross-model, 2026-09-28)

An adversarial pass by OpenAI `gpt-6-astra` (codex-agent, read-only, 93k tokens; raw output in
`…/scratchpad/inv/codex-review.md`)
checked the draft against the local sources. Accepted and applied: yt-dlp requests `tv_downgraded`
**before** `web_embedded` for kids (append order vs pop order — verified at `_video.py:3053/3063`); the
reference `tv_downgraded` is plain Cobalt, Tizen is only the open PR; the preroll-wait explanation is a
hypothesis until the ad renderers are checked, and yt-dlp waits full ad durations (the 80 % is iter.ca's
measurement); Morphe's TV_SABR declares no PO token; yt-dlp's `main` player variant is a default, not
forced; FreeTube also parses prerolls; HLS failure stages; VoidTube's TV_DOWNGRADED still gets the `001`
sts suffix (`usesTvSignatureTimestamp`), so a UA swap alone is not upstream's recipe; PO-token minting
vs binding. Ranking changes: a single yt-dlp-shaped WEB_EMBED baseline added as #0, HLS-VOD promoted to
joint first, preroll split into measure / honour / avoid, the supportXhr sweep demoted for IOS/ANDROID
(they need attestation anyway), WEB_EMBEDDED SABR added to the SABR arms. Not changed: the claim that
the 48–68 s window matches "fake buffering" stays as the leading hypothesis because every independent
source (yt-dlp #14081/#14646, iter.ca, rvx, FreeTube) describes exactly a first-request refusal lasting
about the preroll length.

