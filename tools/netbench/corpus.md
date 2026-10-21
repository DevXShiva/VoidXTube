# VoidTube playback benchmark — video corpus

42 videos, verified signed out on 2026-09-28 between 16:05 and 16:26 UTC from the owner's
home connection in Spain (country ES). The machine-readable version is `corpus.json` in this
folder (same order). Categories: ordinary 5, made_for_kids 8, age_restricted 3, music 2, live 6, shorts 2, long 3, embed_disabled 2, region_blocked 2, expected_fail 5, history 4. 9 are made for kids; 30 are expected to
play signed out and 12 are expected to fail.

`expected_signed_out` = `play` means yt-dlp got media formats from at least one signed-out client
from ES. It does NOT mean every client works: the kids and two of the age-restricted videos only
play through `web_embedded` (see Surprises). `fail` = no signed-out client returned formats, and
none is expected to.

## How it was verified

- `yt-dlp --version` (PATH, `~/.local/bin/yt-dlp`): **2026.08.19**. The probes ran from the local
  checkout `~/projects/yt-dlp` (git HEAD `c7fb478`, 2026-09-16; it reports
  `stable@2026.08.19 from yt-dlp/yt-dlp [594bd50c2] (source)`), which is the build issue5/findings.md used.
- JS runtime deno 2.9.6, EJS challenge solver v0.8.0 (from the local cache). No PO-token provider.
- Default client selection of that build: `visionos` + `web` (the web response is the one embedded in
  the watch page), and yt-dlp adds `tv_downgraded` + `web_embedded` itself for made-for-kids pages
  and `web_embedded` for age-gated ones. Web client version on the pages: `2.20260925.01.00`.
  YouTube served player JS `7460dd14` to most runs and `fb50cd46` to two.
- Command (signed out; never the owner's config or cookies):
  ```
  PYTHONPATH=~/projects/yt-dlp python3 -m yt_dlp --ignore-config --no-cookies --no-cookies-from-browser \
    --remote-components ejs:github --sleep-requests 4 --skip-download -J -v --write-pages \
    [--extractor-args youtube:skip=hls   (VOD after the first 10 videos) | youtube:skip=dash (live)] URL...
  ```
  Several videos shared one process so the ~3 MB player JS was fetched once per run, not per video.
  `--write-pages` saved every page and player response. Per-client statuses and streamingData
  (`adaptive/url/cipher/hls/sabr`) in `corpus.json` come from those saved responses, not from
  yt-dlp's summary.
- **Budget:** about 143 YouTube HTTP requests in 19 yt-dlp runs (17 probe runs plus 2 channel-tab listings), all spaced by at least 4 s (yt-dlp
  `--sleep-requests 4`, plus 5 s between runs). **No bot wall**: no "Sign in to confirm you're not a
  bot", no "unusual traffic", no HTTP 429 in any log or saved page.
- **Format counts are not all comparable.** To stay inside the budget, HLS manifests were only fetched
  for the first 10 videos and the live ones (`hls_formats_listed` in the JSON). For the rest,
  `yt_dlp_formats` counts only https formats, and HLS availability shows as `hls=True` in each client's
  `streaming` string. For example, kids videos show 33 to 35 formats with HLS listed and 23 to 25 without.
  DASH manifests were never fetched.

## How made_for_kids was determined

No field says "made for kids" in the signed-out player response (`videoDetails` and
`playerMicroformatRenderer` only have `isFamilySafe`, which is `true` for Rick Astley too). Two
signals in the **watch page HTML** do, and both were learned on `_WB5hh7WOb4` (Cip-Cirip, issue #5):

1. `ytInitialPlayerResponse.playabilityStatus.miniplayer.miniplayerRenderer.playbackMode` is
   **`PLAYBACK_MODE_PAUSED_ONLY`** with the popup text *"Miniplayer is off for videos made for kids.
   Tap play to resume"*. Every non-kids playable video has `PLAYBACK_MODE_ALLOW`.
2. `ytInitialData` has the channel's notification-bell button disabled with the text *"This action is
   turned off for content made for kids"*.

The two signals agreed on all 42 videos: 9 yes, 22 no. `made_for_kids` is `null` (11) when the page
has no playable player response, so it carries no miniplayer block (private, removed, members-only,
region-blocked, age-gated, dead streams). The 3 age-restricted ones cannot be made for kids anyway,
because YouTube does not allow MFK content to be age-restricted. yt-dlp itself uses a cruder
version of this, `'made for kids' in webpage`, to decide when to add `web_embedded`/`tv_downgraded`.
The raw signals are kept per video in `mfk_signal`. No kids video turned up outside the kids
category except the Peppa Pig stream, which I put under live on purpose.

## Table

MFK = made for kids. `OK-sabr-only` = status OK, but every format is SABR-only (no `url`, no
`signatureCipher`), so none can be used without SABR and a PO token. `OK-sabr+itag18` = the same,
except the progressive 360p itag 18 still has a URL or cipher. The per-client format counts come
**after** yt-dlp's cross-client itag de-duplication: a client showing 0 formats can still have
returned usable ones (usually its itag 18 lost to another client's copy). The raw per-client numbers
are in each `yt_dlp_clients_used[].streaming` string. `dur` is h:mm:ss; for the two dead Lofi Girl
streams it is the stream's whole runtime.

| # | id | category / subcategory | title | channel | dur | MFK | age | live_status | expect | yt-dlp formats (by client) | clients tried → status | yt-dlp error |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `jNQXAC9IVRw` | ordinary / vlog | Me at the zoo | jawed | 0:19 | no | 0 | not_live | play | **24** (visionos 24) | web(page):OK-sabr-only → visionos:OK |  |
| 2 | `5KLPxDtMqe8` | ordinary / tech-science | Your Brain is Plastic | SciShow | 4:08 | no | 0 | not_live | play | **42** (visionos 42) | web(page):OK-sabr-only → visionos:OK |  |
| 3 | `kJQP7kiw5Fk` | ordinary / vevo-music-video | Luis Fonsi - Despacito ft. Daddy Yankee | Luis Fonsi | 4:42 | no | 0 | not_live | play | **40** (visionos 40) | web(page):OK-sabr-only → visionos:OK |  |
| 4 | `dQw4w9WgXcQ` | ordinary / official-music-video | Rick Astley - Never Gonna Give You Up (Of… | Rick Astley | 3:33 | no | 0 | not_live | play | **45** (visionos 44, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 5 | `0e3GPea1Tyg` | ordinary / dubbed-multi-audio | $456,000 Squid Game In Real Life! | MrBeast | 25:41 | no | 0 | not_live | play | **200** (visionos 200) | web(page):OK-sabr-only → visionos:OK |  |
| 6 | `_WB5hh7WOb4` | made_for_kids / RO / Cip-Cirip (issue #5) | 🐵 Cinci Maimuțele / Cântecel Vesel pentru… | Cip-Cirip - Cantece si de… | 2:12 | **yes** | 0 | not_live | play | **33** (web_embedded 33) | web(page):OK-sabr-only → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 7 | `e_04ZrNroTo` | made_for_kids / US / CoComelon | Wheels on the Bus / @CoComelon Nursery Rh… | Cocomelon - Nursery Rhymes | 3:49 | **yes** | 0 | not_live | play | **75** (web_embedded 75) | web(page):OK-sabr-only → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 8 | `9DqETNOlcDE` | made_for_kids / UK / Peppa Pig Official | A Trip to the Animal Zoo 🦁 / Peppa Pig Of… | Peppa Pig - Official Chan… | 2:01:12 | **yes** | 0 | not_live | play | **33** (web_embedded 33) | web(page):OK-sabr-only → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 9 | `pZw9veQ76fo` | made_for_kids / CA / Super Simple Songs | Five Little Ducks / Kids Songs / Super Si… | Super Simple Songs - Kids… | 2:54 | **yes** | 0 | not_live | play | **35** (web_embedded 35) | web(page):OK-sabr+itag18 → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 10 | `717IVcyUrNE` | made_for_kids / ES / Cleo y Cuquin (Familia Telerin) | Cinco patitos 🦆  Canciones infantiles con… | Cleo y Cuquin - Canciones… | 2:17 | **yes** | 0 | not_live | play | **23** (web_embedded 23) | web(page):OK-sabr+itag18 → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 11 | `XqZsoesa55w` | made_for_kids / KR / Pinkfong Baby Shark | Baby Shark Dance / #babyshark Most Viewed… | Baby Shark - Pinkfong Kid… | 2:16 | **yes** | 0 | not_live | play | **23** (web_embedded 23) | web(page):OK-sabr+itag18 → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 12 | `wGltuo1B1sM` | made_for_kids / RO / Siaris Kids (small, ~700 subscribers) | 🍂 Toamna a Venit! Cele Mai Frumoase Cânte… | Siaris Kids | 12:40 | **yes** | 0 | not_live | play | **25** (web_embedded 25) | web(page):OK-sabr+itag18 → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 13 | `Fc5aA77Nlf0` | made_for_kids / RO / TraLaLa | Cățelul Bingo - Cântece cu animale pentru… | TraLaLa - Cantece si dese… | 23:54 | **yes** | 0 | not_live | play | **23** (web_embedded 23) | web(page):OK-sabr+itag18 → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 14 | `WaOKSUlf4TM` | age_restricted / embeddable 18+ (web_embedded bypass works) | Assassin’s Creed Valhalla: Story Trailer … | Assassin's Creed | 2:28 | ? | 18 | not_live | play | **33** (web_embedded 33) | web(page):LOGIN_REQUIRED → visionos:LOGIN_REQUIRED → web_embedded:OK |  |
| 15 | `HtVdAasjOgU` | age_restricted / embeddable 18+ (web_embedded bypass works) | The Witcher 3: Wild Hunt - The Sword Of D… | The Witcher | 2:22 | ? | 18 | not_live | play | **27** (web_embedded 27) | web(page):LOGIN_REQUIRED → visionos:LOGIN_REQUIRED → web_embedded:OK |  |
| 16 | `qkO6iBwcoe4` | age_restricted / non-bypassable 18+ | Rammstein - ''Pussy'' - (OFFICIAL VIDEO) … | Eliass Kevrelis | 4:02 | ? | 18 | – | fail | none | web(page):LOGIN_REQUIRED → visionos:LOGIN_REQUIRED → web_embedded:UNPLAYABLE | Sign in to confirm your age. Use --cookies-from-browser or --cookies … |
| 17 | `MgNrAu2pzNs` | music / topic-art-track | Voyeur Girl | Stephen | 2:49 | no | 0 | not_live | play | **23** (visionos 23) | web(page):OK-sabr-only → visionos:OK |  |
| 18 | `XclachpHxis` | music / ytm-art-track (premium-style) | Firefly | Jim Yosef - Topic | 4:17 | ? | – | – | fail | none | web(page):UNPLAYABLE → visionos:UNPLAYABLE | Video unavailable |
| 19 | `5yx6BWlEVcY` | live / 24/7 livestream (Chillhop Radio) | Chillhop Radio - jazzy & lofi hip hop bea… | Chillhop Music | – | no | 0 | is_live | play | **14** (visionos 8, web 6) | web(page):OK → visionos:OK |  |
| 20 | `v03RjDNwG1o` | live / recently ended stream with replay (NASA) | Progress 96 Cargo Ship Docking | NASA | 52:20 | no | 0 | was_live | play | **178** (visionos 177, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 21 | `j9epFget1W8` | live / upcoming scheduled stream (NASA) | Space Station Operations Update (Sept. 28… | NASA | – | no | – | is_upcoming | fail | none | web(page):LIVE_STREAM_OFFLINE → visionos:LIVE_STREAM_OFFLINE | This live event will begin in 2 hours. |
| 22 | `gDcYk-rDY5I` | live / ended 12 h kids stream (Peppa Pig "Live 24/… | 🔴 Peppa Pig / Full Episodes / All Series … | George Pig - Official Cha… | 11:54:08 | **yes** | 0 | was_live | play | **33** (web_embedded 33) | web(page):OK-sabr+itag18 → visionos:UNPLAYABLE → tv_downgraded:UNPLAYABLE → web_embedded:OK |  |
| 23 | `X4VbdwhkE10` | live / dead 24/7 stream (Lofi Girl, HANDOFF) | lofi hip hop radio 📚 beats to relax/study… | Lofi Girl | 1820:15:55 | no | – | post_live_unavailable | fail | none | web(page):UNPLAYABLE → visionos:UNPLAYABLE | This live stream recording is not available. |
| 24 | `jfKfPfyJRdk` | live / dead 24/7 stream (Lofi Girl canonical) | lofi hip hop radio 📚 beats to relax/study… | Lofi Girl | 33778:11:52 | no | – | post_live_unavailable | fail | none | web(page):UNPLAYABLE → visionos:UNPLAYABLE | This live stream recording is not available. |
| 25 | `BGQWPY4IigY` | shorts / short (creator) | MET THE LOVE OF MY LIFE ON AN AIRPLANE *c… | Allie Schnacky | 0:14 | no | 0 | not_live | play | **26** (visionos 26) | web(page):OK-sabr-only → visionos:OK |  |
| 26 | `MT8tg5b3b8E` | shorts / short (NASA official) | Artemis II Watches Earth Set Behind the M… | NASA | 0:53 | no | 0 | not_live | play | **26** (visionos 25, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 27 | `Vop-h-u9B4o` | long / 10h+ | Nyan Cat 10 hours (original) | HermTrololol | 10:00:00 | no | 0 | not_live | play | **5** (visionos 5) | web(page):OK-sabr-only → visionos:OK |  |
| 28 | `LXb3EKWsInQ` | long / 4K60 HDR | COSTA RICA IN 4K 60fps HDR (ULTRA HD) | Jacob + Katie Schwarz | 5:14 | no | 0 | not_live | play | **40** (visionos 40) | web(page):OK-sabr-only → visionos:OK |  |
| 29 | `wE-aQO9XD1g` | long / 360 / VR | NASA’S Perseverance Rover’s First 360 Vie… | NASA Jet Propulsion Labor… | 1:00 | no | 0 | not_live | play | **24** (visionos 24) | web(page):OK-sabr-only → visionos:OK |  |
| 30 | `MeJVWBSsPAY` | embed_disabled / embedding disabled (lyrics upload) | OOMPH! - Such Mich Find Mich (Lyrics) | Herr Lurik | 3:30 | no | 0 | not_live | play | **15** (visionos 14, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 31 | `s5qx1X78ujE` | embed_disabled / embedding disabled (Russian TV series ep.) | МОЛЧАНИЕ - Серия 1 / Детектив / СМОТРИТЕ … | EPIC MEDIA | 49:48 | no | 0 | not_live | play | **29** (visionos 28, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 32 | `sJL6WA-aGkQ` | region_blocked / JP-only (blocked in ES) | 西野カナ 『Dear Bride』MV(Short Ver.) | 西野カナ Official YouTube Cha… | 2:08 | ? | – | – | fail | none | web(page):UNPLAYABLE → visionos:UNPLAYABLE | Video unavailable |
| 33 | `jwCz9KmGuYY` | region_blocked / JP-only (blocked in ES) | 【公式】その着せ替え人形はラジオをする（第16回） | アニプレックス チャンネル | 59:13 | ? | – | – | fail | none | web(page):UNPLAYABLE → visionos:UNPLAYABLE | Video unavailable |
| 34 | `yZIXLfi8CZQ` | expected_fail / private |  |  | – | ? | – | – | fail | none | web(page):LOGIN_REQUIRED → visionos:LOGIN_REQUIRED | Private video |
| 35 | `6SJNVb0GnPI` | expected_fail / removed (policy) |  |  | – | ? | – | – | fail | none | web(page):ERROR → visionos:ERROR | This video has been removed for violating YouTube's policy on hate sp… |
| 36 | `EwVsbUg4u2I` | expected_fail / removed (Shorts re-upload) |  |  | – | ? | – | – | fail | none | web(page):ERROR → visionos:ERROR | Video unavailable |
| 37 | `w664JpkrDio` | expected_fail / members-only | Dimension 20: Dungeons and Drag Queens Tr… | Dropout | 2:46 | ? | – | – | fail | none | web(page):UNPLAYABLE → visionos:UNPLAYABLE | Join this channel to get access to members-only content like this vid… |
| 38 | `6ULI7RH_0U8` | expected_fail / paid movie (YouTube Movies) | I Still Know What You Did Last Summer | YouTube Movies | 1:40:31 | ? | – | – | fail | none | web(page):UNPLAYABLE → visionos:UNPLAYABLE | Video unavailable |
| 39 | `Fo89b8zAIE4` | history / bot-check incident | Rusowsky: Tiny Desk Concert | NPR Music | 19:29 | no | 0 | not_live | play | **24** (visionos 23, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 40 | `p7YpVl35pac` | history / audio 403 on resume | Milo J: Tiny Desk Concert | NPR Music | 16:28 | no | 0 | not_live | play | **24** (visionos 23, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 41 | `u_vnA6nlDvs` | history / initial error + recovery | Charlie Puth: Tiny Desk Concert | NPR Music | 23:09 | no | 0 | not_live | play | **24** (visionos 23, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |
| 42 | `X1bx9_TL20A` | history / preload media 403 | The Cast of Buena Vista Social Club: Tiny… | NPR Music | 20:18 | no | 0 | not_live | play | **24** (visionos 23, web 1) | web(page):OK-sabr+itag18 → visionos:OK |  |

## Surprises and things the benchmark should know

1. **Every made-for-kids video fails on the default clients, on every channel and in every country.**
   9/9 (RO ×3, US, UK, CA, ES, KR, and a 12 h Peppa Pig stream replay from the George Pig channel) gave the same answer:
   `visionos` UNPLAYABLE "This video is not available", `tv_downgraded` UNPLAYABLE "The page needs
   to be reloaded.", and the watch-page WEB response OK but with SABR-only adaptive formats. yt-dlp got formats **only through
   `web_embedded`** (with the embed page's `encryptedHostFlags`, as in issue5/findings.md).
   Channel size makes no difference: Siaris Kids (699 subscribers) and Baby Shark (85 M) behave the
   same. So issue #5 affects all MFK content signed out, not just Cip-Cirip.
2. **Some `web_embedded` answers are ciphered only.** CoComelon `e_04ZrNroTo` (39) and Baby Shark
   `XqZsoesa55w` (23) returned only `signatureCipher` formats. The other kids videos returned plain
   `url`s. A revived WEB_EMBED path in VoidTube has to decipher (sts + sig), or those two stay broken.
3. **Two of the three age-restricted videos play signed out.** `WaOKSUlf4TM` and `HtVdAasjOgU`
   (`playable_in_embed` true) work only through `web_embedded`; `visionos` and WEB say LOGIN_REQUIRED. `qkO6iBwcoe4`, the
   HANDOFF age-gate test video, fails everywhere (`web_embedded`: "Sorry, this content is
   age-restricted"). Also, yt-dlp's "multiple audio streams" fixture `WaOKSUlf4TM` is now an 18+
   Assassin's Creed trailer with a single audio language.
4. **For everything else, yt-dlp's formats came from `visionos` alone.** The watch-page WEB response
   was SABR-only for all 27 VODs where it answered OK. In 15 of them the progressive 360p itag 18 was
   still usable (the single `web` format on several rows). For the live stream, WEB did return HLS
   with URLs. No playable non-kids, non-age-gated video needed a third client.
5. **Both Lofi Girl stream ids are dead.** `X4VbdwhkE10` (HANDOFF §6: "Reliable 24/7 live stream",
   also the Live DVR PASS video) and the canonical `jfKfPfyJRdk` both return "This live stream
   recording is not available." (post-live, manifestless). HANDOFF should be updated. The working
   24/7 stream here is Chillhop `5yx6BWlEVcY`, which is also from HANDOFF's live-dash note.
6. **The upcoming control expires today.** `j9epFget1W8` was "will begin in 2 hours" at 16:13Z, so it
   goes live around 18:15Z and becomes was_live after that. Before a benchmark run, find a new one:
   `yt-dlp --ignore-config --flat-playlist -J https://www.youtube.com/@NASA/streams` lists upcoming
   entries first (1 request).
7. **The paid-movie control is also geo-blocked.** `6ULI7RH_0U8` (YouTube Movies, `hasYpcMetadata`)
   is only offered in MX. From Spain both clients say "Video unavailable" / "This video is not
   available", never "requires payment". I did not find a rental that is offered in ES without
   browsing a signed-in storefront.
8. **The YouTube Music-only style track is gone signed out.** `XclachpHxis` (Jim Yosef - Topic, yt-dlp's
   "Requires Premium via YTM URL" fixture) is UNPLAYABLE on both clients with no availableCountries,
   so it is a fail control for WEB_REMIX/YTM clients. The ordinary Topic art track
   `MgNrAu2pzNs` plays, but its availableCountries covers only 123 countries (ES included), against
   ~249 for normal uploads.
9. **Dubbed audio shows up in unexpected places.** Besides MrBeast (24 audio languages), CoComelon
   (5), the embed-disabled Russian series `s5qx1X78ujE` (ru original + en-US) and the NASA replay
   `v03RjDNwG1o` (20 languages, auto-dubbed) all carry several audio tracks. Language tags come
   from audio-only formats; the muxed itag 18 sometimes carries a different tag.
10. **Some fixtures are thin.** Nyan Cat 10 h has only 5 formats (240p max). The 360 video's
    projection shows only in the player response (`projectionType: MESH` on 14 formats); yt-dlp's
    JSON does not report it.

## Videos with a history of trouble (from our docs)

- `_WB5hh7WOb4` (made_for_kids): GitHub issue #5 "Unknown source error" (newtube-launch/issue5/findings.md): VoidTube ring of 9 clients all fail signed out. yt-dlp plays it ONLY through web_embedded.
- `wGltuo1B1sM` (made_for_kids): issue #5 check on the Pixel over LTE (issue5/pixel/b-kids2-lte.log): VISIONOS..IOS (9 clients) all unusable, the 10th, WEB_EMBED, played with 24 usable formats. Small/independent kids channel.
- `Fc5aA77Nlf0` (made_for_kids): issue #5 emulator repro (issue5/emu_signedout.log, emu_webembed.log): failed like _WB5hh7WOb4.
- `qkO6iBwcoe4` (age_restricted): HANDOFF.md section 6: "Age-gated test video used across rounds" (Rammstein, [FIXED AUDIO] re-upload). web_embedded answers "Sorry, this content is age-restricted".
- `5yx6BWlEVcY` (live): HANDOFF.md "live dash search" note: one of two 24/7 streams where every web-family client answered dash=n and ANDROID_VR dash=y. Working 24/7 stream in this corpus (the Lofi Girl ids below are dead).
- `X4VbdwhkE10` (live): HANDOFF.md section 6 calls it the "Reliable 24/7 live stream"; section on Live DVR PASS used it. Now "This live stream recording is not available." (post-live manifestless). Update HANDOFF.
- `Fo89b8zAIE4` (history): BOT-CHECK-2026-09-07.md: Pixel showed "Inicia sesion para confirmar que no eres un bot" (LOGIN_REQUIRED) on this video; the fixed video of PERFORMANCE/SABR docs; plays in official app.
- `p7YpVl35pac` (history): LIVE-PASS-2026-09-07.md: historical resume at 945.9 s got one audio HTTP 403, recovered by reload.
- `u_vnA6nlDvs` (history): PIXEL-TTFF-GATE-2026-09-07.md: "One initial error, ordinary recovery, then playback" (overall TTFF 3585 ms).
- `X1bx9_TL20A` (history): PIXEL-STARTUP-ABR-2026-09-07.md: speculative next-video preload got media 403s (reason=error); the real open recovered once.

## Candidates not probed (request budget)

These came up in the doc search but were left out to stay under ~150 requests:

- `yi0PiY1i3XU` Grupo Frontera Tiny Desk (PIXEL-TTFF-GATE-2026-09-07.md: one initial error then
  recovery, same as `u_vnA6nlDvs`).
- `4xDzrJKXOOY`, the second 24/7 stream in HANDOFF's live-dash note (ANDROID_VR at attempt 2).
- `2Szdo6fRc5c` (STATUS.md: signed-in TV_DOWNGRADED soak, which passed).
- `syUvNGm7mt0`, `4-8dBKAiC-Q`, `5p8-_QNihtc`: other Romanian kids videos that failed in the issue #5
  emulator run (issue5/emu_signedout.log). Expect the same result as `Fc5aA77Nlf0`.
- `ouuPSxE1hK4`, `bdneye4pzMw`, `6tCjflXY9CM`: SABR-CELLULAR-2026-09-08 measurement videos (no
  failure history).
- `Tq92D6wQ1mg`: yt-dlp's "age-gated, embeddable only with clientScreen=EMBED" fixture.
- `bTYoscjqaaw` El Reino Infantil (AR kids), plus `M3HKLzjvKPc` / `awQzjn72bI0` (NASA ISS 24/7,
  `is_live` in the /streams listing at 16:10Z).
