> Copied from a private working folder on 2026-09-28: the tools it names (harness, appbench, recap, proxy, corpus) now live in `tools/netbench/`; the raw results and logs it cites are not in the repo.

# PO-token (BotGuard) minting: state of VoidTube's fork, what changed upstream, and a port plan (2026-09-28)

Read-only research. Nothing in `smarttube-port` was edited, built, staged or committed. Inside
`MediaServiceCore` I only ran `git fetch upstream` and read remote refs. The cherry-pick trials in
§5.2 ran in a throwaway clone in my scratchpad (`…/scratchpad/msc-port`). This machine sent no
YouTube or googlevideo traffic; the device facts below come from logs already on disk.

**Bases.**
- MediaServiceCore (MSC) fork `master` = **`16e67076`** ("WEB_EMBED: send the embed page's identity"). Its working tree is clean.
- `upstream/master` = `476357b4` (2026-09-27). Merge base = `ef98dcd8` (2026-06-27): 62 upstream commits ahead, 62 fork commits ahead.
- SharedModules fork `1503e1e`, upstream merge base `17ea42b`.
- yt-dlp `c7fb478` (local, shallow clone).
- bgutil-ytdlp-pot-provider `master` (session_manager.ts as of 2026-09-28), LuanRT/BgUtils `main` (v4.0.3).
- Morphe `fccde737f` (the scratchpad clone used by `inventory-external.md`).

**Paths.** `app/` = `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/`.
`VIS` = `videoinfo/V2/VideoInfoService.java`. `MMA` = `smarttubetv/src/stmobile/java/com/newtube/mobile/MobileMainApplication.java`.
Line numbers are for `16e67076` (fork) or `476357b4` (upstream).

**Evidence tags.**
- **[code]** = read in source.
- **[log]** = a NetPath/logcat line already on disk.
- **[doc]** = a maintainer's PR, issue or wiki text.
- **[inferred]** = my conclusion, not verified.

---

## 0. Summary

1. **Our fork mints from the old NewPipe "WAA Create" challenge.** It calls `POST www.youtube.com/api/jnn/v1/Create`, then runs BotGuard with no page, no `ytcfg` and no `EVENT_ID`, then calls `GenerateIT`. The selected generator is `PoTokenWebView` (`app/potokennp2/misc/PoTokenSelector.kt:5`).
   - Upstream's own label on this class, added 2026-08-16 in `12b7957e`: *"Original generator taken from NewPipe project. It is outdated and probably not working at all."*
   - LuanRT (BgUtils #44, v4.0.3) describes this challenge type as one that "only some clients accept … e.g. YouTube Music".
2. **Correction to the brief: our VOD GVS token is already bound to the video id, not the visitor.**
   - VOD `pot=` on every format URL comes from `PoTokenGate.getPoToken(client, videoId)`, which resolves to `WEB_CONTENT` = `generatePoToken(videoId)`. The same string goes into the `/player` body.
   - The visitor-bound "streaming" token is used in exactly one place: the live-manifest `/pot/` segment (`VideoInfoServiceBase.java:127`).
   - The `binding=streaming:visitor,player:video` on the `web-pot-session` line describes the two mints, not where they are used.
   - So the port is a **minting** problem (challenge + `ytcfg`/`EVENT_ID`). Binding only needs changing for live.
3. **Upstream's fix, `PoTokenWebView4`, is bgutil PR #243 ported to a WebView.** It was added in `a722df75` (08-08), polished through `3d87d39e` (08-10), and made the default in `3d9521ff` (08-12). The flow:
   - `GET https://www.youtube.com`, then take the first `ytcfg.set({...})` and the `window.ytAtN({...}).R.bgChallenge` from that same page.
   - Download the interpreter JS, then run BotGuard with `yt = {config_: ytcfg}` injected.
   - Call `GenerateIT` on `www.youtube.com/api/jnn/v1` with the same request key.
   - Fall back to `/youtubei/v1/att/get` if the homepage parse fails, and from there to the old `PoTokenWebView` if the generator fails.
4. **bgutil's "58 % → 92 %" is PR #243 (1.3.2, 2026-08-21).**
   - It fetches the homepage through the caller's proxy and prefers that `(ytcfg, ytAtN)` pair over both the webpage challenge yt-dlp passes and `/att/get`.
   - It injects `yt.config_` so the BotGuard snapshot sees `EVENT_ID`.
   - Numbers: 24 trials, 4 videos, yt-dlp 2026.07.04, residential proxies. Stock `/att/get` went 14/24, the homepage pair 22/24. The 2 remaining failures were per-IP bot challenges.
   - yt-dlp itself changed no PO-token code between July and September 2026.
5. **Per yt-dlp (code), the WEB, WEB_SAFARI, MWEB, WEB_MUSIC and TV_SIMPLY clients all need a GVS token for https/DASH; HLS is only "recommended".**
   - None needs a player token: yt-dlp never fetches one for them, although the official embed does send a cold-start token in `/player` (§4).
   - GVS binding: the video id when the client's `ytcfg` carries `html5_generate_content_po_token=true`, otherwise visitorData (signed out) or dataSyncId (signed in).
   - TV_SIMPLY never downloads a `ytcfg` in yt-dlp, so it stays visitor-bound.
   - WEB_EMBEDDED has no policy at all.
6. **The cold-start token is a SABR placeholder, not an attestation.**
   - BgUtils: it "can be used while `sps` (StreamProtectionStatus) is 2, but will not work once it changes to 3".
   - The official players send it (10 bytes, empty identifier) until BotGuard finishes, then switch to a minted 85–90-byte token.
   - Its effect on URL-format refusals, such as TV_SIMPLY's and WEB_MUSIC's single-run refusal at media 55–60 s, is **unmeasured**. I expect no lift [inferred], but the SABR evidence does not establish that.
   - Its only documented role is bridging time to first frame. That matters for a future WEB/MWEB SABR path.
   - Our fork has a generator for it (`app/potoken/PoTokenService.kt:52`) that nothing calls, and it uses the video id as the identifier where the official player uses an empty one.
7. **The port is small in code but must be path-limited, not a series of cherry-picks.** On `16e67076`:
   - Only `3d9521ff`, `ef7dbd34` and `394eebc5` apply as whole commits. `394eebc5` is undone by `82e9ccde`, which conflicts.
   - Every other commit that touches `PoTokenWebView4` also carries unrelated hunks (`VIDEO_INFO_TYPE_LIST`, `QueryBuilder`, `InitialResponseService`, client constants).
   - Taking the upstream file plus 3 small hunks applies cleanly, then needs 4 compile adaptations: OkHttp 4, RxJava 3, and a missing `OkHttpManager.doRequest`.
8. **Five fixes belong in the port itself.**
   - (a) Re-apply our VOIDXDEV(pot-init-timeout) ordering; upstream reads `potWv` before checking `completed`.
   - (b) A mint timeout that fails with an exception instead of `UninitializedPropertyAccessException`. Our current `PoTokenWebView` has no mint timeout at all.
   - (c) Catch errors in the homepage path, as bgutil does. Upstream's `getChallengeFromHomepage` has no try/catch, and a network error there escapes a `@JavascriptInterface` method. [inferred] That skips `/att/get` and lands on the old generator.
   - (d) A consent cookie and browser-like headers on the homepage GET. [inferred] An EU cookieless load may get a consent response instead of the homepage, which silently means `/att/get`.
   - (e) NetPath fields that say which challenge actually minted: `challenge=`, `ytcfg=`, `eventId=`, `contentFlag=`, `potLen=`.
9. **The user-visible gain is capped until a route carries URL formats with a token.**
   - WEB, WEB_SAFARI and MWEB, as VoidTube requests them today (`supportXhr=true`), answered SABR-only on the Pixel on 2026-09-28 (`OK usableAdaptive=0 sabr=y`, one kids video, `inventory-engine.md` §3). `sabr-media3` sends no `po_token`.
   - This is one build, one profile and one session, not a property of the phone. The official MWEB watch page and WEB_REMIX got URL formats (`observed-official.md` §3).
   - Routes that would carry a token: MWEB with `supportXhr=false`/absent (URL formats in the netbench sweep), WEB_SAFARI HLS, live DASH/HLS `/pot/`, and later SABR with `streamer_context.po_token`.
   - TV_SIMPLY and WEB_MUSIC (URL formats; refused at media ~55–60 s in one sustain run each) need a token *policy* and a minted token.
10. **Effort.** The core implementation (generator, live binding, telemetry, unit tests) is ~2.5–3.5 person-days. Device verification is a separate ~1.5–3 person-days of labor, spread over several sessions because of bot walls (counting in §5.7). Optional token arms add ~2.5–4 days: MWEB, WEB_MUSIC, and TV_SIMPLY with a TV challenge. Cold-start comes only with SABR work (~0.5 day). These are planning estimates, not measured.

---

## 1. What our fork does today (MSC `16e67076`)

### 1.1 Wiring and warm-up

- `PoTokenGate` object init: `PoTokenProviderImpl.poTokenFactory = selectFactory()` (`app/PoTokenGate.kt:54-56`). `selectFactory()` returns `PoTokenWebView` (`app/potokennp2/misc/PoTokenSelector.kt:5`).
- Warm-up, phone only:
  - `MMA` ~L511-548 → `LaunchMilestones.runAfterFirstFrame(4 s fallback)` → `VideoInfoService.warmUpPoTokenGate()` (`VIS:405`).
  - → `PoTokenGate.warmUp()` (`PoTokenGate.kt:165-174`, thread "PoTokenWarmUp").
  - → `getWebSessionPoToken()` → `PoTokenProviderImpl.getWebClientPoToken("")`.
  - Debug switch: `debug.arc.eager_token_warmup off`.
- VISIONOS and ANDROID_VR ride the session's visitor without waiting for BotGuard: `getWebVisitorDataForPlayer` → `PoTokenProviderImpl.peekSessionVisitorData()` (`PoTokenGate.kt:195-207`; `PoTokenProviderImpl.kt:64-74`).

### 1.2 WebView setup (`app/potokennp2/generators/PoTokenWebView.kt`)

- **Pre-checks** (`:386-393`): `hasThermalServiceBug` (API 29) and `hasUsbServiceBug` throw `BadWebViewException`.
- **Settings** (`:43-56`):
  - JavaScript on; Safe Browsing off (guarded against `AbstractMethodError`, `:80-89`).
  - `blockNetworkLoads = true`.
  - UA `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3` (sic, `:382-383`).
  - JS interface name `PoTokenWebView`.
  - A console "Uncaught" is treated as a broken WebView (`:58-77`).
- **Page** (`:96-113`): `loadDataWithBaseURL("https://www.youtube.com", assets/potokennp2/po_token.html + "PoTokenWebView.downloadAndRunBotguard()")`. The page contains Promise/Symbol polyfills, `loadBotGuard`, `snapshot`, `runBotGuard` and `obtainPoToken`.
- **Construction** runs on the main thread. The caller waits on a latch for 20 s; VOIDXDEV(pot-init-timeout) checks `completed` before touching `potWv` (`:395-421`).

### 1.3 Challenge fetch (WAA "Create")

- **Request** (`:119-146`, `:314-343`): `POST https://www.youtube.com/api/jnn/v1/Create`.
  - Body `[ "O43z0dpjhgX20SCx4KAo" ]`.
  - Headers: the UA above, `Accept: application/json`, `Content-Type: application/json+protobuf`, `x-goog-api-key: AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw`, `x-user-agent: grpc-web-javascript/0.1`.
  - Sent through `OkHttpManager.instance()`, the base SharedModules client. It has no InnerTube interceptors, no auth and no cookie jar (the interceptors live on `RetrofitOkHttpHelper`'s derived client, `RetrofitOkHttpHelper.kt:88-89,189`).
- **Parse** (`JavaScriptUtil.kt:15-52`): if element 1 is a string it is base64-decoded and each byte gets +97 ("descramble"). Then:
  - `[0]` messageId, `[1]` inline interpreter JS, `[2]` trusted URL, `[3]` interpreterHash, `[4]` program, `[5]` globalName, `[7]` clientExperimentsStateBlob.
- **What it lacks:** no youtube.com page is involved, so there is no `ytcfg` and no `EVENT_ID` [code].
- **Measured** [log] `~/projects/newtube-launch/issue5/emu_fixed.log` 17:47:58: `POST …/jnn/v1/Create` 200 in 223 ms (26-byte body).

### 1.4 Program and interpreter

- `runBotGuard` (`po_token.html`):
  1. `new Function(interpreterJavascript)()`.
  2. `vm = this[globalName]`, then `vm.a(program, vmFunctionsCallback, true, undefined, noop, [[],[]])`.
  3. Poll every 1 ms, up to 10 000 ticks, until `asyncSnapshotFunction` exists.
  4. `snapshot({webPoSignalOutput})` returns `botguardResponse`, and `webPoSignalOutput` is kept on `this` (`PoTokenWebView.kt:130-145`).
- Measured [log]: about 330 ms between the Create answer and the `botguardResponse` line.

### 1.5 Integrity token

- **Request** (`:163-187`): `POST https://www.youtube.com/api/jnn/v1/GenerateIT`, body `[ "O43z0dpjhgX20SCx4KAo", "<botguardResponse>" ]`, same headers.
- **Answer:** `[integrityToken, estimatedTtlSecs, mintRefreshThreshold(, websafeFallbackToken)]`.
- **Expiry:** `expirationMs = now + (ttl − 600) s`.
- **Measured** [log], emulator 2026-09-28: GenerateIT 200 in 273 ms, answer `["…",43200,100]`, so TTL is 12 h and the generator lives ~11 h 50 min.
  - Whole session build: `buildMs=1103` (emulator, `emu_fixed.log`) and `buildMs=830` (Pixel, `issue5/pixel/warmup-wifi.log`).

### 1.6 Minting

- **Generator level** (`PoTokenWebView.kt:197-233`):
  - `obtainPoToken(webPoSignalOutput, integrityToken, u8(identifier))` calls `getMinter(integrityToken)` on **every** mint, then `mintCallback(identifier)`.
  - The result is sent back as a comma list of bytes and converted to base64url (`JavaScriptUtil.kt u8ToBase64`).
  - **The caller waits with `latch.await()` and no timeout (`:228`).** A WebView that lost its content can block the /player thread indefinitely [code; the `player-mint-failed` retry only covers thrown errors].
- **Session level** (`app/potokennp2/PoTokenProviderImpl.kt:102-243`, under `WebPoTokenGenLock`):
  - **When it rebuilds:** there is no generator, the state was cleared, a rebuild was forced, or the generator expired (`:108-109`).
  - **Visitor:** `AppService.visitorData`, the persistent `/tv` visitor. VOIDXDEV(anonymous-recs) (`:125-149`) falls back to `VisitorService` (`POST /youtubei/v1/visitor_id`, Firefox UA, `SOCS=CAE=`). Rotation is dormant.
  - **Generator:** built from `poTokenFactory`. If it throws `BadWebViewException` or `PoTokenException` and is not already `PoTokenWebView`, the code falls back to `PoTokenWebView` (`:172-191`).
  - **Session ("streaming") token** = `generatePoToken(visitorData)`, minted once per session **before** any content token (`:193-196`).
  - **Content ("player") token** = `generatePoToken(videoId)`, minted outside the lock (`:215-234`). On failure it retries once with a forced rebuild and logs `NetPath web-pot-session player-mint-failed error=… action=recreate`.
  - **Session line:** `NetPath web-pot-session new|failed reason=<initial|mint-failed|rotation|reset|expired> visitorSource=<app|visitor-api> visitor=<fp> prevAgeMs= buildMs= binding=streaming:visitor,player:video generator=<class>` (`:254-262`).
  - Real line [log], Pixel: `web-pot-session new reason=initial visitorSource=app visitor=3b756832b9 prevAgeMs=-1 buildMs=830 binding=streaming:visitor,player:video generator=PoTokenWebView`.
- **Observed token sizes** [log], 8 mints in `issue5/emu_*.log`:
  - Content tokens are 204–460 base64url chars; `_WB5hh7WOb4` = 208 chars = 156 bytes.
  - Visitor tokens are 852 chars.
  - For comparison: upstream reports 124 chars (120 with `ytcfg`) for its `PoTokenWebView3/4` tokens (`PoTokenWebView4.kt:137`, androidTest), and the official players' minted tokens were 85–90 bytes (`novel/observed-official.md` §5).
  - [inferred] Token length is therefore a cheap, secret-free signal of which flow minted a token (§5.5).

### 1.7 Caching and TTL

- **Generator:** until integrity TTL − 10 min (≈11 h 50 min) or a reset.
- **`PoTokenGate` slot:** one entry `mWebPoToken`, keyed on videoId (`PoTokenGate.kt:82-93`). A mint for a new video evicts the old entry. The visitor and session token survive because they are cached inside the provider.
- **Resets:**
  - `resetWebCache()` is throttled to one per 60 s (`:282-297`).
  - VIS calls it on recovery (`VIS:3117, 3125, 3146`). The next use rebuilds the whole generator: Create + BotGuard + GenerateIT, ~1 s.
  - WEB_EMBED is special-cased: reset → `YtCfgService.invalidateEmbedIdentity()` (`:221-233`).
- **Format cache:** `YouTubeMediaItemFormatInfo.isCacheActual` (`service/data/YouTubeMediaItemFormatInfo.java:485-496`) invalidates web-pot answers, except WEB_EMBED, when the generator expired.

### 1.8 Where each token goes

| use | code | clients | token |
|---|---|---|---|
| `/player` body `serviceIntegrityDimensions.poToken` | `QueryBuilder.kt:210-213` ← `VideoInfoApiHelper.java:30` `getPlayerRequestPoToken` ← `PoTokenSelection.kt:47-63` | WEB, WEB_SAFARI, MWEB, GEO, INITIAL (`AppClient.kt:182` `isWebPotRequired`, minus WEB_EMBED); ANDROID_VR only with `debug.arc.player_pot=1` | **video-bound** (`WEB_CONTENT`) |
| VOD media `pot=` on every URL holder | `VideoInfoServiceBase.java:171-177` → `VideoUrlHolder.setPoToken` (`videoinfo/models/VideoUrlHolder.kt:99-101`); innertube path `innertube/core/Player.kt:80` | same web-pot clients | **video-bound**, the same string as the `/player` body |
| live manifests `…/pot/<token>` (HLS and DASH) | `VideoInfoServiceBase.java:127-129` → `VideoInfo.appendPotToManifestUrls` (`videoinfo/models/VideoInfo.java:181-191`) | web-pot clients only (never ANDROID_VR/TV/iOS, see `PoTokenSelection`) | **visitor-bound** (`WEB_SESSION`, `getPoToken(client)` with no videoId) |
| SABR `streamer_context.po_token` (field 2) | `sabr-media3/.../SabrProtocol.java:105-116` | – | **never sent** |
| WEB_EMBED | `PoTokenSelection.kt:57` | – | none (it carries the embed page's own identity) |
| TV_SIMPLY, WEB_MUSIC, WEB_CREATOR, TV*, IOS, ANDROID*, VISIONOS | not in `isWebPotRequired` | – | none |

The web-pot visitor is the app visitor, the same string on every client except WEB_EMBED
(`inventory-engine.md` §1.3). It goes into both `context.client.visitorData` and `X-Goog-Visitor-Id`.

### 1.9 The cloud fallback is dead code

- **When it would run:** only when `isWebPotSupported` is false (no WebView, or a broken WebView). Then:
  - `getWebSessionPoToken()` returns `PoTokenCloudService.getPoToken()` (`PoTokenGate.kt:95-103`). That only reads a persisted `MediaServiceData.poToken`, with no network.
  - The content path returns `null`.
- **The only network path is unreachable.** `PoTokenCloudService.updatePoToken()` does `GET <base>?visitorData=<visitor>` but is only called from `PoTokenGate.updatePoToken()`, which is `private` and never called (`:105-113`).
- **Its base URLs are placeholders:** `"https://service1.com", "https://service2.com"` (`app/potokencloud/Constants.kt`).
- **`potokencloud2`** points at a LAN IP (`http://192.168.31.61:4416`) and nothing imports it.
- **Recommendation:** delete it, or at least never wire `updatePoToken`. If it were ever reached it would send the visitor to unrelated domains [code].

### 1.10 Cold start

`PoTokenService.generateColdStartToken(identifier, clientState)` (`app/potoken/PoTokenService.kt:52-102`) and
`PoTokenGate.getColdStartPoToken(client, videoId)` (`PoTokenGate.kt:176-178`) exist but nothing calls them. The same is true upstream.

---

## 2. What changed upstream (yuliskov/MediaServiceCore)

### 2.1 Every upstream commit touching the PO-token paths since the merge base

Command: `git log ef98dcd8..upstream/master -- app/PoTokenGate.kt app/PoTokenSelection.kt app/potoken app/potokennp2 app/potokencloud app/potokencloud2 youtubeapi/src/main/assets`.

| hash | date (+0300) | subject | PO-token content | in fork? |
|---|---|---|---|---|
| `ef7dbd34` | 07-10 21:50 | pot: add init checks | `PoTokenWebView2/3`: throw on a 20 s init timeout (our fork re-did this for `PoTokenWebView` in `795091f7`) | no (unused classes) |
| `a722df75` | 08-08 04:04 | pot: add a new variant | **new `PoTokenWebView4.kt`** (homepage challenge + `/att/get` fallback); `JavaScriptUtil.parseLooseJSON` (org.json version); androidTest `…4` cases; also unrelated `InnertubeService`/`InitialResponseService` hunks | no |
| `0f138382` | 08-08 04:11 | upd SharedModules | WV4 doc comment; SharedModules → `a224870` ("okhttp helpers upd": adds `OkHttpManager.doRequest(url, headers, body, contentType)`) | no |
| `6a21ed72` | 08-08 17:14 | pot: upd | WV4: payload validation (`bgChallenge`/`program`/`interpreterUrl`), log lines; `parseLooseJSON` → nanojson + `quoteJson`; also `VIDEO_INFO_TYPE_LIST` reordering | no |
| `3624fcb7` | 08-08 23:21 | upd SharedModules | WV4 logging order | no |
| `3d87d39e` | 08-10 03:57 | potokennp2: update generator number 4 | **WV4 extracts `ytcfg.set({...})` and injects `yt = {config_: ytcfg}` before `runBotGuard`** (the EVENT_ID part); regex refactor; also `VIDEO_INFO_TYPE_LIST` | no |
| `3d9521ff` | 08-12 23:38 | PoTokenSelector: use sabr compatible generator | **`selectFactory() = PoTokenWebView4`** | no |
| `12b7957e` | 08-16 22:13 | added description to pot generators | doc comments; `PoTokenWebView` = "outdated and probably not working at all", `PoTokenV8` = "TODO: remove me" | no |
| `efc0e339` | 08-21 16:42 | VideoInfo: temp fix for broken SABR live | WV4 doc link only (the rest is `VideoInfo.isAdaptiveFormatsBroken`) | no |
| `daf417c3` | 08-24 22:00 | potokennp2: update docs; QueryBuilder… | WV4 note: `jnn-pa…/Waa/GenerateIT` "produces more reliable token (without 403 error) but may hang"; kept `www.youtube.com/api/jnn/v1/GenerateIT`; WV3 doc | no |
| `082e2e48` | 08-31 22:22 | VideoInfoService: force switch next client | `PoTokenGate`: `CACHE_RESET_TIME_MS` constant (cosmetic) | no |
| `394eebc5` | 09-11 02:04 | PoTokenGate: do warmup after resetting the cache | warm-up mint after reset + reset on core-data refresh | no |
| `82e9ccde` | 09-11 05:21 | PoTokenGate: remove warmup (negligible improvement) | reverts the `PoTokenGate` half of `394eebc5` | no |
| `d9ba6d3a` | 09-12 01:35 | WebViewUtil: Fix Android 10 thermal listener bug | – | **yes** (`5ac14442`, patch-equivalent) |
| `96cfe447` | 09-13 01:09 | update client versions … ThermalServiceApi29 doc | **`PoTokenProviderImpl`: catch `LinkageError`** (NoClassDefFoundError on old devices → mark the WebView bad instead of killing the lookup); WebViewUtil doc; also client versions | no |

- Unchanged since the merge base: `po_token2.html` (identical in fork and upstream), `PoTokenGate`'s token logic and binding (upstream still uses the content token for `/player` and `pot=`), `potoken/`, and `potokencloud*`.
- `PoTokenSelection.kt` exists only in our fork.

### 2.2 `PoTokenWebView4` (upstream `476357b4`) vs our selected `PoTokenWebView`

WV4 is upstream's `PoTokenWebView3` (itself V2 plus the `www.youtube.com` GenerateIT) with the homepage patch. The differences that matter:

| aspect | our `PoTokenWebView` | upstream `PoTokenWebView4` |
|---|---|---|
| HTML asset | `po_token.html`: `obtainPoToken(webPoSignalOutput, integrityToken, id)` calls `getMinter(integrityToken)` on every mint | `po_token2.html` (already in our fork, identical): `getMinter(integrityToken)` runs **once** after GenerateIT and `mintCallback` is kept; `webPoSignalOutput` is freed; `obtainPoToken(id)` (`:287-302`) |
| UA (WebView and requests) | Windows Chrome 131 | BgUtils `USER_AGENT` = `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)` (`:499`) |
| challenge | `POST /api/jnn/v1/Create` (`[requestKey]`), interpreter inline, scrambled | **`GET https://www.youtube.com`** (`:170-221`) with `accept: */*`, `accept-language: en-US,en;q=0.7`, the UA, **plus** the gRPC defaults merged in by `makeBotguardServiceRequest` (`Content-Type: application/json+protobuf`, `x-goog-api-key`, `x-user-agent`, `Accept: application/json`; `:430-447`) |
| page session | none | `ytcfg`: the first `ytcfg\.set\((\{.+?\})\);` (DOTALL), raw JSON. Challenge: `window\.ytAtN\(\s*(\{[\s\S]*?\})\s*\)` → `parseLooseJSON` → key `R` must contain `bgChallenge`, `program`, `interpreterUrl` → `parseDescrambledChallengeData(R)`, which **downloads the interpreter** from `https:` + `interpreterUrl…WrappedValue` (`JavaScriptUtil.kt:58-86`) |
| EVENT_ID injection | none | `if (ytcfg) yt = { config_: ytcfg }` evaluated before `runBotGuard` (`:140-159`); BotGuard reads `yt.config_.EVENT_ID` |
| fallback challenge | – | `POST https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false`, body `{context:{client:{clientName:"WEB",clientVersion:<AppClient.WEB>}},engagementType:"ENGAGEMENT_TYPE_UNBOUND"}` (unquoted keys, not strict JSON), `Content-Type: application/json`; no `ytcfg` (`:226-250`) |
| last-resort fallback | – | if WV4 construction throws `BadWebViewException`/`PoTokenException`, `PoTokenProviderImpl` builds the old `PoTokenWebView` (our `:175-191`; same code upstream) |
| integrity token | `www.youtube.com/api/jnn/v1/GenerateIT`, `[O43z…, bgResponse]` | same endpoint, key and body (`:274-277`); the `jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT` alternative is commented out (hangs) |
| empty-output check | none | `webPoSignalOutput.length == 0` → init error; `mintCallback` undefined → init error |
| mint wait | `latch.await()` (unbounded) | `latch.await(10, SECONDS)`, then `return pot`: on a timeout `pot` is an uninitialized `lateinit` → `UninitializedPropertyAccessException` (a RuntimeException, so the provider's retry path catches it, but for the wrong reason) |
| init wait | 20 s; VOIDXDEV checks `completed` first | 20 s; checks `potWv.initError` **before** `completed` (`:530-534`), so a timeout before `potWv` is assigned throws `UninitializedPropertyAccessException`: the exact bug our `795091f7` fixed in `PoTokenWebView` |
| HTTP helper | `OkHttpManager.doPostRequest`, `response.code`/`.body` (OkHttp 4) | `OkHttpManager.doRequest(url, headers, data?, null)` (SharedModules `a224870`), `response.code()`/`.body()` (OkHttp 3), `import io.reactivex.SingleEmitter` (RxJava 2; doc-only use) |
| token size | 204–460 chars observed (content) [log] | "120 chars instead of regular 124" with `ytcfg` (upstream comment `:137`) |

### 2.3 The new flow, step by step (endpoints, parameters, timing)

1. **`GET https://www.youtube.com/`**, no cookies (the base client has no jar). The response is the full desktop homepage HTML.
   - [inferred] several hundred KB; not measured here, since this machine sends no YouTube traffic.
   - Extract `ytcfg` (it holds `EVENT_ID`, `VISITOR_DATA`, `INNERTUBE_CONTEXT`, `EXPERIMENT_FLAGS`…) and `ytAtN(...).R.bgChallenge`.
2. **`GET https://www.google.com/js/th/<hash>.js`** (the `interpreterUrl`): the BotGuard VM. The old Create flow got it inline.
3. BotGuard runs in the WebView with `yt.config_` injected, giving `botguardResponse`.
4. **`POST https://www.youtube.com/api/jnn/v1/GenerateIT`** `[ "O43z0dpjhgX20SCx4KAo", botguardResponse ]` returns the integrity token + TTL (43200 s today [log]).
5. `mintCallback = getMinter(integrityToken)` is kept. Every mint after that is local JS, with no network (~13 ms warm per `PoTokenGate.kt:141-143`, measured on the old flow).
6. **Fallback chain:** no `ytAtN`/`ytcfg` → `/att/get` (a challenge without `EVENT_ID`, the flow bgutil calls rejected for sessions in the experiment); then any init exception → old `PoTokenWebView` (Create).

- **Cost per generator build:** homepage + interpreter download + GenerateIT, replacing Create + GenerateIT.
- **When it happens:** once per session, at warm-up, on the 12 h expiry, and on every recovery reset (throttled to 60 s).
- [inferred] `buildMs` grows by the homepage download (≈0.3–1.5 s on LTE). This stays off the open path as long as the warm-up has run.

### 2.4 Session binding: what is bound to what

- **BotGuard / integrity token** → the **homepage page session** (`EVENT_ID`) through the challenge + `ytcfg` pair from one page load. Its `VISITOR_DATA` is a fresh visitor, because the request carries no cookie.
- **Minted tokens** → whatever identifier is passed. Upstream (and our provider) mint:
  - the session token from the **session visitor** (upstream: a fresh `VisitorService` visitor, "MOD: my visitor data"; ours: the persistent app visitor);
  - content tokens from the **video id**.
- **The homepage visitor does not match the visitor on `/player`.**
  - bgutil does the same: its homepage fetch carries none of yt-dlp's cookies, and yt-dlp binds GVS to its own visitor or the video id. That scored 22/24.
  - [inferred] So the page-session binding is a property of the challenge/`EVENT_ID` pair, not of the `/player` visitor. It has not been shown for VoidTube's persistent-visitor scheme; §5.5 arm C tests it.

### 2.5 Upstream's own `PoTokenGate` binding (unchanged)

Upstream `PoTokenGate.getPoToken(client, videoId)` gives web-pot clients the content token, or the session token when there is no videoId. It feeds `/player` (`VideoInfoApiHelper.java:21` upstream) and `pot=` (`VideoInfoServiceBase.java:78,137`). Same as ours, minus our live `/pot/` and VOIDXDEV selection.

---

## 3. bgutil-ytdlp-pot-provider, BgUtils and yt-dlp, July–September 2026

### 3.1 BgUtils (LuanRT)

**Releases.**
- v4.0.0 (07-18): the library was restructured.
- v4.0.1–4.0.2 (07-24): minor.
- **v4.0.3 (08-04): PR #44** "Extract att challenge and `ytcfg` from the page".

**PR #44 [doc].**
- The quote: *"A few weeks ago, YouTube started binding the initial attestation challenge to the `yt.config_` object (specifically `yt.config_.EVENT_ID`). As a result, WebPO tokens generated using challenges from `/att/get` are now rejected if your session is part of the experiment and you are using the `WEB` or `MWEB` InnerTube clients."*
- The example's flow:
  1. `fetch("https://www.youtube.com")` (UA + `accept-language: en-US,en;q=0.7`).
  2. `ytcfg.set(...)` → `window.yt = {config_}`.
  3. `ytAtN(...)` → `parseLooseJSON(...).R.bgChallenge`.
  4. `GenerateIT` on `www.youtube.com/api/jnn/v1` (`buildURL('GenerateIT', true)`).
  5. The token is minted from the **video id** and appended as `&pot=`.
- EDIT 1: `initialAttestationDataJson.T` can be sent as `eacrToken` to `/att/get`, but you "still need `yt.config_.EVENT_ID`".
- EDIT 2: *"the attestation challenge from the TV client can be used. It doesn't require `EVENT_ID` (yet?)"*.
  - Flow: `GET /tv_config?action_get_config=true&client=lb4&theme=cl` with a Cobalt/Android-TV UA and `referrer: /tv` → strip `)]}'` → `challengeParams.R` (JSON) → `bgChallenge`; `challengeRequestKey` from the same response goes into `GenerateIT` (`www.youtube.com/api/jnn/v1`).
  - The example then mints a content token from the video id and plays **MWEB** formats with `&pot=`.
- The examples README now reads: `index.ts` "Uses WAA challenge. (only some clients accept this challenge type, e.g. YouTube Music)"; `index-innertube.ts` "Uses InnerTube attestation challenge. (this is what the official YouTube clients currently use)".
- `createColdStartToken` doc: "This can be used while `sps` (StreamProtectionStatus) is 2, but will not work once it changes to 3."

### 3.2 bgutil-ytdlp-pot-provider: exactly what raised 58 % to 92 %

**Issue #242** (opened 2026-07-27) [doc].
- The report: *"YouTube / GVS has occasionally rejected the tokens … responding with HTTP 403s after half a minute."* It hit yt-dlp stable 2026.07.04 (`web_safari`) and `web`, on residential and VPN IPs, livestreams included.
- It is intermittent, which the reporter took for an A/B test.
- After 1.3.2: one reporter saw MWEB 403s return around 09-14; on 09-21 several users said it works (on 2.0.0), and the MWEB blip "looked unrelated to POT".

**PR #243** (`[server] Mint WebPO tokens from the homepage challenge + ytcfg (fixes #242)`, merged 2026-08-21, released in 1.3.2 the same day) [doc/code, `server/src/session_manager.ts`]:
1. `getChallengeFromHomepage(potCtx)`: `potCtx.fetch("https://www.youtube.com", GET, {accept:"*/*", "accept-language":"en-US,en;q=0.7", "user-agent": USER_AGENT})`. The fetch goes **through the caller's proxy**, so it uses the same IP as the downloads.
2. `pageHtml.match(/ytcfg\.set\(({.+?})\);/s)` → `JSON.parse` → `globalThis.yt = window.yt = {config_}` ("BotGuard reads yt.config_.EVENT_ID").
3. `pageHtml.match(/window\.ytAtN\(\s*({[\s\S]*?})\s*\)/)` → `parseLooseJSON` (from bgutils-js 4.0.3) → `.R.bgChallenge`, which must have `program` and `interpreterUrl`.
4. In `getDescrambledChallenge`: `challenge = (await getChallengeFromHomepage()) ?? challenge`. The homepage pair **wins over the webpage challenge yt-dlp passes** ("plugin-passed challenges lack their page's ytcfg/EVENT_ID").
   - `/att/get` remains the last resort: `ENGAGEMENT_TYPE_UNBOUND`, yt-dlp's `innertubeContext` or WEB `2.20260817.01.00`.
5. The rest is unchanged:
   - Interpreter fetch, then `BotGuardClient.snapshot`.
   - `GenerateIT` at **`jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT`** (`buildURL("GenerateIT")`, default `useYouTubeAPI=false`), request key `O43z0dpjhgX20SCx4KAo`.
   - The minter is cached per (proxy, remoteHost) until `estimatedTtlSecs`; the extra homepage GET happens "once per minter creation … not per token".
   - Tokens are cached per content binding for `TOKEN_TTL` (6 h default).
6. Validation (the PR's own numbers): yt-dlp 2026.07.04 + provider, residential proxies, sticky sessions rotated and caches invalidated between trials, 24 trials, 4 videos, audio downloads.
   - Stock 1.3.1 (`/att/get`): **14/24 (58 %)**. Patched: **22/24 (92 %)**.
   - "29× challenge minted from the homepage; the legacy fallback engaged only when the homepage fetch itself failed through a flaky proxy exit. The remaining failures were ordinary per-IP bot challenges."

**Other bgutil releases.**
- 1.3.2 also shipped `[deps]` bgutils-js 4.0.3 (#240).
- **2.0.0 (09-08)** is security only: RCE advisory GHSA-qpv9-8xfj-xx9m, localhost binding, PAC scheme block, JSON-only `/get_pot`. No minting change.

### 3.3 yt-dlp (code at `c7fb478`, and the wiki)

**No PO-token code changed in July–September 2026.** YouTube commits touching `_video.py`/`_base.py`/`pot/` in that window:
- 07-20 `69ea200` (#17261): adds an android_vr POT policy, visionos to defaults.
- 08-18 `dae52d8` (#17461): android_vr out of defaults.
- 08-18 `5d5b634` (#17462): web_embedded fallbacks.
- 09-16 `c7fb478` (#17684): Safari UA for web_embedded.

yt-dlp delegates minting to provider plugins (bgutil).

**GVS vs player tokens** (wiki "PO Token Guide", last edited 2026-07-12 per the page) [doc]:
- **GVS** = "Google Video Server requests (video streaming - https, dash, hls, etc.)".
- **Player** = "Innertube `player` requests (fetch video format URLs)".
- *"PO Tokens have a 'content binding', meaning they are bound to the user session (Visitor ID or account Session ID) or to the video ID. Most PO Tokens (such as for `web` GVS/Player) are bound to the video ID, so a new token is required for each video."*
- TL;DR: "Use a PO Token Provider plugin to provide the `mweb` client with a PO Token for GVS requests."

**Content binding in code** (`yt_dlp/extractor/youtube/pot/utils.py:35-61`, `_video.py:2775-2782`):
- `gvs_bind_to_video_id = True` when the client's `ytcfg` has `WEB_PLAYER_CONTEXT_CONFIGS.*.serializedExperimentFlags` containing `html5_generate_content_po_token=true`. It logs "Detected experiment to bind GVS PO Token to video ID for <client> client".
- GVS → video_id if that flag is set. Otherwise dataSyncId (signed in) or visitorData (signed out); optionally the 11-char visitor id.
- **WEB_REMIX** → visitorData/dataSyncId for every context, unless the GVS flag applies.
- PLAYER and SUBS → video_id.
- The `ytcfg` comes from `_download_ytcfg` (`_base.py:993-1002`): mweb → `m.youtube.com`, web and web_safari → `www.youtube.com`, web_music → `music.youtube.com`, web_embedded → `/embed/<id>?html5=1`, tv → `/tv`.
  - **tv_simply has no page**, so the default `ytcfg` has no flag and **its GVS token stays visitor-bound**.
- WebPO clients (`WEBPO_CLIENTS`): WEB, MWEB, TVHTML5, WEB_EMBEDDED_PLAYER, WEB_CREATOR, WEB_REMIX, TVHTML5_SIMPLY, TVHTML5_SIMPLY_EMBEDDED_PLAYER.

**Per-client policy** (`_base.py`: `WEB_PO_TOKEN_POLICIES:71-94` and the client entries). Legend: `req` = required, `rec` = recommended, `!prem` = not required for Premium.

| client | GVS https / DASH | GVS HLS | player token | subs | GVS binding (signed out) | wiki note |
|---|---|---|---|---|---|---|
| `web` | req + rec, !prem | rec | not req, not rec | not req ("in rollout … detected via experiment") | video id if the flag is in the www.youtube.com `ytcfg`, else visitorData | "Subs, GVS — Only SABR formats available" |
| `web_safari` | same as web | rec | same | same | same | "GVS* … HLS (m3u8) formats which do not require PO Token for GVS at this time" |
| `mweb` | req + rec, !prem | rec | not req | – | video id if the flag is in the m.youtube.com `ytcfg`, else visitorData | "GVS" |
| `tv_simply` | req + rec (no premium exemption) | rec | not req | – | **visitorData** (no `ytcfg` download) | "GVS — account cookies not supported" |
| `web_music` | req + rec, !prem | rec | not req | – | video id if the flag is in the music `ytcfg`; else visitorData (WEB_REMIX special case) | "GVS" |
| `web_embedded` | **no policy** | – | – | – | (flag seen: "#17666 … bind GVS PO Token to video ID for web_embedded") | "Not required — only embeddable videos" |

**Other signals** [doc, from `inventory-external.md`]:
- Morphe/inotia00 (#468, 08-02): "newer PoTokens are bound to the video id; visitor-bound tokens still work only for MWEB and WEB_REMIX".
- Morphe #2533 (TV Simply signed out, 08-22):
  - Challenge from `/tv_config?action_get_config=true` (Tizen 8 UA, `Referer: /tv`), `challengeRequestKey` from the response, `GenerateIT` at `www.youtube.com/api/jnn/v1`, challenge cached 5 h 55 m.
  - **Video-bound token in `serviceIntegrityDimensions.poToken` + visitor-bound token as `&pot=`** on every format URL. The visitor is the per-client `/visitor_id` visitorData.
  - Read in code: `BotGuardManager.java`, `PoTokenManager.java`, `PoTokenGenerator.java` in the Morphe clone.

---

## 4. The cold-start token

### 4.1 How the official players build it

**BgUtils `createColdStartToken(contentBinding, clientState=1)`** (`src/core/WebPoMinter.ts`) [code]:
1. `header = [k0, k1, 0, clientState, ts>>24, ts>>16, ts>>8, ts]`, where k0 and k1 are random bytes and ts is unix seconds.
2. `packet = [0x22, len(header)+len(binding), header…, utf8(binding)…]`.
3. Every payload byte from index 2 on is XORed with `payload[i % 2]` (the two keys).
4. The result is base64url.

**What the official players send** (`novel/observed-official.md` §5) [log]:
- The official embed sends it in `/player` `serviceIntegrityDimensions.poToken` with an **empty identifier**: 10 bytes, e.g. `IgjgjOCNijZ40Q==`.
- The timestamp equals the page's `adSignalsInfo.dt`.
- When BotGuard had not finished before the first media request, the WEB/MWEB/embed players put the same kind of token into the **first SABR request's `streamer_context.po_token`**. There googlevideo answered `STREAM_PROTECTION_STATUS` 2 (attestation pending) on WEB/MWEB, still serving 1.0–4.9 MB.
- Once the page's `GenerateIT` finished, requests carried an 85–90-byte minted token and the status was 1.
- YouTube Music sent no token at all: status 2 for ~10 requests, then 1 after its own `att/get` + `GenerateIT`.
- Page flags: `html5_generate_content_po_token=true`, `html5_generate_session_po_token=true`, `html5_skip_empty_po_token=true`, `html5_enable_d6de4_cold_start_and_error=true`.

**Our fork's copy differs:** `generateColdStartToken` uses the **video id** as the identifier (21 bytes, not 10), standard base64 with padding (`app/potoken/Helpers.kt u8ToBase64`, `android.util.Base64`), and nothing calls it.

### 4.2 Does googlevideo accept it?

- **As an attestation, no.** Per BgUtils it is valid only while `sps` = 2 and "will not work once it changes to 3".
  - `sps` is a SABR/UMP concept, so the cold-start token is a **startup placeholder for SABR**: it lets the first 1–5 MB flow while BotGuard mints.
  - googlevideo re-mints on 2/3 (up to 5 times). yt-dlp's SABR PR calls cold-start "out of scope".
- **For URL formats (`pot=` / `/pot/`):** unmeasured, and the SABR observations above do not settle it. URL formats have no `sps` channel, only HTTP status.
  - [inferred] My expectation: it behaves like no token. That would mean served up to the refusal seen at media ~55–60 s (one sustain run each for TV_SIMPLY/WEB_MUSIC), then 403.
  - The only positional wall measured across several videos is the token-less SABR one (HANDOFF §28: 56–60 s, 3 videos); the URL-format refusal may share its cause, but that is not established.
  - netbench variant `obs-embed-https-coldstart` is still `abstract` (it needs a `${coldstart_pot}` harness feature).
- **In the `/player` body:** the official embed always sends it. Whether it changes the answer (formats, playability, ad wait) is **untested**. It is alternative (b) for the WEB_EMBED first-request 403 in `observed-official.md`'s verdict.

### 4.3 Could it help any client in our benchmark?

| client | today (netbench) | cold-start alone | what would help |
|---|---|---|---|
| TV_SIMPLY | URL formats; in one sustain run, audio itag 251 refused at media 55.0–60.0 s while video for the same span was served a second earlier (`sustain-20260928-wifi-test.md`: `FAIL@46s (HTTP403)`, ~17.5 MB; the report itself says "one run each, so not yet established") | **unlikely, unmeasured** [inferred] | a minted token under a real policy: Morphe's TV challenge recipe (video-bound `/player` + visitor-bound `pot=`), or yt-dlp's web token bound to visitorData |
| WEB_MUSIC | same shape, same single run, `FAIL@46s (HTTP403)` | **unlikely, unmeasured** [inferred] | a minted token. LuanRT's WAA example (our **current** Create flow!) plays YTMUSIC audio with a video-bound `&pot=`; yt-dlp binds WEB_REMIX to visitorData unless the flag. Needs WEB_REMIX version `1.20250219.01.00` → `1.20260707.12.00` first |
| MWEB | `supportXhr=true` → SABR-only (`nt-MWEB` `A:SABR`, itag 18 progressive only); `supportXhr=false` or absent → adaptive URLs served on the first probe (`sweep1-*.md`, `nt-MWEB~xhr-false`, `nt-MWEB~dpc-absent`), not sustained | URL path: **no**; SABR path: only a ~1–2 s time-to-first-frame bridge while the real token mints, and our warm-up usually mints first | URL path: content-bound `pot=` from the new flow (yt-dlp's recommendation). SABR path (sessions that are SABR-only, #17666): `sabr-media3` must send the content token as raw bytes in `streamer_context.po_token` |
| WEB_EMBED | URLs with `supportXhr=false`; first-request 403 then OK 48–68 s later on the Pixel | maybe, in `/player` only, as the official embed does (hypothesis (b)) | a one-arm test behind a debug switch, after the harness has `${coldstart_pot}` |
| IOS / ANDROID_VR SABR | `ATTESTATION_PENDING` → `REQUIRED` at 56–60 s (HANDOFF §28) | **no**: not WebPO clients (they need iOSGuard/DroidGuard) | – |

**Verdict:** cold-start is not a substitute for minting. Implement it only together with the SABR `po_token` work (fix the identifier to empty and use base64url), plus the WEB_EMBED `/player` experiment behind a debug prop.

---

## 5. Port plan

### 5.1 Steps

**Step 0: telemetry first** (MSC, no behaviour change, ~0.5 day).
- Extend `logWebPotSession` (`PoTokenProviderImpl.kt:254-262`) with:
  - `challenge=homepage|att-get|waa-create`, `ytcfg=y|n`, `eventId=y|n`;
  - `contentFlag=y|n|?` (`html5_generate_content_po_token=true` present in the homepage HTML);
  - `pageHost=` (final host after redirects, to catch `consent.youtube.com`), `pageKb=`, `pageMs=`;
  - `ttl=` (GenerateIT seconds) and `fallbackFrom=` when the provider fell back to `PoTokenWebView`.
- Add a debug-only `web-pot-mint video=<id> len=<chars> ms=<n>` line. The length is secret-free and tells the flows apart (§1.6).
- Carry the fields through a new `PoTokenGenerator.diagnostics(): String` with a default of `""`, so the interface change stays one line.

**Step 1: port the minter** (MSC youtubeapi only, no SharedModules bump, ~1.5–2 days).
1. Add `app/potokennp2/generators/PoTokenWebView4.kt` from `upstream/master:476357b4` (a path checkout, not a cherry-pick). Adapt it:
   1. Drop `import io.reactivex.SingleEmitter` (`:30`; doc-only) or use `io.reactivex.rxjava3.core.SingleEmitter`.
   2. OkHttp 4: `response.code`, `response.body` (`:449,455,459`).
   3. Replace `OkHttpManager.doRequest(url, headers, data, null)` (`:435`): our SharedModules lacks the 4-argument overload. Use `if (data == null) doGetRequest(url, headers) else doPostRequest(url, headers, data, null)`, or cherry-pick SharedModules `a224870` (a 12-line helper) and bump the pointer. Inline is less churn.
   4. **Re-apply VOIDXDEV(pot-init-timeout):** check `completed` before touching `potWv` (`:530-534`).
   5. **Mint timeout:** `if (!latch.await(10, SECONDS)) throw PoTokenException("mint timeout")` instead of reading an unset `lateinit` (`:344-348`). Give the fallback `PoTokenWebView.generatePoToken` the same bound (today `:228` has none).
   6. **Homepage request hygiene:**
      - Send only browser-like headers: no `x-goog-api-key`, `x-user-agent` or `Content-Type` on a GET. Upstream merges its gRPC defaults in, and sends both `Accept` and `accept`.
      - Add **`Cookie: SOCS=CAI`**, yt-dlp's consent cookie; our `VisitorApi` already sends `SOCS=CAE=`.
      - [inferred, a hypothesis] An EU cookieless homepage load may come back as a consent response with no `ytAtN`. For VoidTube's mostly-Spanish users that would silently mean `challenge=att-get`.
      - `pageHost`, `challenge` and a with/without-`SOCS` comparison on the first device run will show whether it happens. A consent host alone does not prove the cookie is the cause.
   7. **`/att/get` fallback:** strict JSON body with the current WEB clientVersion. Upstream's has unquoted keys; bgutil sends proper JSON.
   8. **Catch errors on the homepage path, like bgutil** (`try { … } catch → undefined`).
      - Upstream's `getChallengeFromHomepage` (`:170-221`) catches nothing. `OkHttpManager` turns every `IOException` into `IllegalStateException` (`SharedModules/…/okhttp/OkHttpManager.java:191-195`).
      - A transient LTE error, or an odd page that `parseLooseJSON`/`parseDescrambledChallengeData` cannot parse, therefore escapes `downloadAndRunBotguard()`, a `@JavascriptInterface` method.
      - [inferred] The WebView reports it to JS as an uncaught error. Our console handler maps "Uncaught" to `BadWebViewException`, and `PoTokenProviderImpl` then builds the old `PoTokenWebView` instead of trying `/att/get`.
      - Fix: return `null` so the chain goes on to `/att/get`, log `challenge=att-get reason=<ExceptionClass>`, and raise network failures as `PoTokenException`, not `BadWebViewException`.
   9. **Make `parseLooseJSON` robust.** It returns `value.toString()` for every key.
      - That works while `R` is a JS string, the shape BgUtils' `normalizeValue` implies, which is fine today.
      - If `R` were ever an object literal, nanojson's `JsonObject` (a `LinkedHashMap` with no `toString` override, checked in 1.7 and 1.10) would give `{bgChallenge={…}}`, which is not JSON.
      - Fix: use `JsonWriter.string(value)` for `JsonObject`/`JsonArray` values.
      - Also: upstream decodes `\xNN` escapes *before* its single-quote pass (BgUtils decodes after parsing), so an escaped `\x27` inside a quoted string would break it. Add it as a test fixture.
   10. Factor the HTML extraction into a pure top-level function, `extractHomepageChallenge(html): HomepageChallenge?` (ytcfg JSON, raw `R`, `eventId` present, `contentFlag`), for unit tests. It must do no network. The interpreter download stays in `parseDescrambledChallengeData`.
   11. Keep the GenerateIT endpoint on `www.youtube.com/api/jnn/v1` (what the WEB/MWEB pages use, per `observed-official.md` §5). Leave the jnn-pa variant as a later A/B arm.
2. `JavaScriptUtil.kt`: apply the upstream hunk that adds `parseLooseJSON` + `quoteJson` (the diff `ef98dcd8..476357b4` applies cleanly on `16e67076`).
3. `PoTokenSelector.kt`: `selectFactory() = PoTokenWebView4` (`3d9521ff` applies cleanly). Add a debug override `debug.arc.pot_generator=1|4`: `MMA` → a static `PoTokenGate.setGeneratorOverride(...)` before `warmUp()`, following the existing gate pattern. That makes the A/B in §5.5 possible.
4. `PoTokenProviderImpl.kt`:
   - Port the `catch (e: LinkageError)` hunk of `96cfe447` (applies cleanly).
   - Keep all VOIDXDEV visitor logic.
   - Keep the `PoTokenWebView` fallback: it still yields tokens [doc: WAA "YouTube Music"-grade]. Log it as `fallbackFrom=PoTokenWebView4`.
5. Optional: the doc comments from `12b7957e`, and `ef7dbd34` (applies cleanly; unused generators).

**Step 2: binding for live** (~0.5 day).
- VOD `pot=` and the `/player` body keep the content (video-id) binding. That already matches yt-dlp under the flag, the official players and upstream.
- **Live `/pot/`** (`VideoInfoServiceBase.java:127`): use `getPoToken(client, videoId)` (content) when `contentFlag=y`, keeping the session token otherwise.
  - `observed-official.md` saw the flag on every page on 2026-09-28.
  - Live is the least-measured path (HANDOFF §8: WEB_EMBED live `/pot/` still 403'd in July). Put it behind its own switch and measure.
- Keep minting the session (visitor) token at build time. It is cheap, and needed for the fallback binding and for visitor-bound clients (Step 3).

**Step 3: optional token policies for more clients** (separate switches, after Step 1 is verified; they depend on the source catalog in `DESIGN.md` §3.1).

| arm | what | effort |
|---|---|---|
| **MWEB + URL formats** | profile `supportXhr=false` or absent + content-bound `pot=` + the `/player` token (as today) | ~0.5–1 d, mostly measurement |
| **WEB_MUSIC** | bump WEB_REMIX `1.20250219.01.00` → `1.20260707.12.00` (upstream `4f350041`); add it to a token policy; A/B: `pot=` video-bound vs visitor-bound; also A/B the old WAA generator vs WV4 | ~0.5–1 d |
| **TV_SIMPLY** | fix client-name id 74 → 75 first. (a) the WV4 web token, visitor-bound GVS (yt-dlp/bgutil); (b) a new `PoTokenWebViewTv` generator (reusing `po_token2.html`): `GET /tv_config?action_get_config=true[&client=lb4&theme=cl]` with a TV UA + `Referer: https://www.youtube.com/tv`, strip `)]}'`, `challengeParams.R`, per-response `challengeRequestKey` → `GenerateIT`; video-bound `/player` token + visitor-bound `pot=` (Morphe #2533). **Conflicts with `PoTokenSelectionTest.foreignPlatformClients`**, which lists TV_SIMPLY as must-never-get-a-Web-token: a deliberate test change, and a new `PoTokenSource` (TV challenge ≠ Web) | ~1.5–2 d |
| **SABR `po_token`** | `sabr-media3`: the content token as base64url-decoded raw bytes in `StreamerContext.po_token`; re-mint on `sps` 2/3; cold-start for the first request only | part of the SABR track (inventory-external §3.6) |

**Step 4: cleanup** (~0.25 day).
- Delete or neutralize `potokencloud` (placeholder hosts) and `potokencloud2` (LAN IP).
- Fix `generateColdStartToken`: empty identifier, base64url, okio instead of `android.util.Base64`, so it is JVM-testable.

### 5.2 Cherry-pick or re-implement (trials on `16e67076` in a scratch clone)

| upstream commit | as a whole commit | decision |
|---|---|---|
| `a722df75` | conflicts (`InitialResponseService.kt`) | **take the files**: `PoTokenWebView4.kt` from `476357b4`, and the androidTest hunk (applies cleanly) |
| `0f138382`, `3624fcb7` | conflict (SharedModules pointer) | skip; the SharedModules helper is inlined (or cherry-pick `a224870` into our SharedModules fork) |
| `6a21ed72`, `3d87d39e` | conflict (`VideoInfoService.java` `VIDEO_INFO_TYPE_LIST`, `JavaScriptUtil.kt`) | skip; their WV4/`JavaScriptUtil` content is included in the `476357b4` versions. **Never take the `VIDEO_INFO_TYPE_LIST` hunks** (CLAUDE.md) |
| `3d9521ff` | clean | cherry-pick (or a one-line edit) |
| `12b7957e`, `efc0e339`, `daf417c3` | conflict (WV4 absent; `VideoInfo.java`, `QueryBuilder.kt`, constants) | skip; doc-only for PO tokens |
| `ef7dbd34` | clean | optional |
| `082e2e48` | conflicts | skip (a cosmetic constant) |
| `394eebc5` + `82e9ccde` | clean / conflicts (`nsigsolver/Utils.kt`) | skip both (net zero for `PoTokenGate`) |
| `96cfe447` | conflicts (client constants) | take only the `PoTokenProviderImpl` `LinkageError` hunk (applies cleanly) |
| `d9ba6d3a` | already in the fork (`5ac14442`) | – |

Path-limited set on `16e67076`: `PoTokenWebView4.kt` (new), plus the hunks for `JavaScriptUtil.kt`, `PoTokenSelector.kt`, `PoTokenProviderImpl.kt` (LinkageError) and androidTest `PoTokenProviderImplTest.kt`. All apply cleanly (`git apply --3way`). The compile adaptations of §5.1 step 1 come on top; they were identified by reading the code, and nothing was compiled.

### 5.3 Conflicts with VOIDXDEV changes

- **Files the port touches and what they carry:**
  - `PoTokenProviderImpl.kt`: VOIDXDEV `visitor-rotation` `:36`, `ttff` `:57`, `visitor` `:112`/`:246`/`:282`, `anonymous-recs` `:125`.
  - `PoTokenGate.kt`: `ttff` `:197`, `web-embed-identity` `:223`, `visitor` `:244`.
  - `PoTokenSelection.kt`: `web-embed-identity` `:53`.
  - `PoTokenWebView.kt`: `pot-init-timeout` `:414`.
- **None overlaps the port's hunks:** the LinkageError catch sits in `getWebClientPoToken(videoId)` above our code; WV4 is a new file.
- **Semantic interactions to keep:**
  - (1) `peekSessionVisitorData` assumes the session adopts `AppService.visitorData` verbatim. It still does; WV4 changes only the challenge.
  - (2) `web-pot-session` line parsers (netbench, HANDOFF greps) must tolerate the new fields; append them at the end.
  - (3) `generator=PoTokenWebView` in old logs now means "fallback".
  - (4) `resetWebCache` on recovery now costs a homepage GET; keep the 60 s throttle.
  - (5) `PoTokenSelectionTest` invariants hold unless Step 3's TV_SIMPLY arm is enabled.
- **Main repo:** `MMA` (the warm-up and the new debug override); no conflict.
  - The main repo's working tree has uncommitted `MMA` edits (another session). Coordinate before touching `MMA`.

### 5.4 Tests to add

**Pure JVM** (like `PoTokenSelectionTest`; no Robolectric, no network):
- `HomepageChallengeParserTest` on `extractHomepageChallenge`:
  - (a) a homepage fixture yields `ytcfg` with `EVENT_ID`, an `R` with `bgChallenge/program/interpreterUrl`, and `contentFlag`;
  - (b) a consent-page fixture yields `null` plus a reason;
  - (c) a `ytAtN` literal with `\x22` escapes, single quotes and trailing commas parses via `parseLooseJSON`;
  - (d) the first `ytcfg.set` is chosen;
  - (e) no `ytAtN` yields `null`;
  - (f) garbage or truncated HTML yields `null` and never throws (step 1.8);
  - (g) a quoted `R` containing an escaped `\x27` still parses, and an object-literal `R` comes back as valid JSON (step 1.9).
  - **Fixtures must be captured from an allowed network** (the device's debug dump or the probe box), with ids stripped. Never from this home IP while it is walled.
- `ParseLooseJsonTest`: BgUtils-style vectors (hex escapes, single-quoted strings, unquoted keys, nested objects returned as strings).
- `ColdStartTokenTest` (after the okio switch): an empty identifier gives 10 bytes, `[0]=0x22`, `[1]=8`; the XOR decode gives state 1 and a timestamp within ±2 s; an identifier round-trips; >118 bytes throws.
- `WebPotSessionLineTest`: the formatter emits `challenge/ytcfg/eventId/contentFlag/pageHost/generator` and never a raw token or visitor. Extract the formatter to a pure function, as `webPotSessionReason` already is.
- `PoTokenSelectionTest` additions: live manifest + `contentFlag` gives `WEB_CONTENT`; WEB_EMBED stays `NONE`. If Step 3 lands: the WEB_MUSIC/TV_SIMPLY policies and the changed `foreignPlatformClients` list.
- Init/mint timeout mapping: extract `awaitOrThrow(latch, ms, what)` and test that a timeout yields `PoTokenException`, never `UninitializedPropertyAccessException`.

**Instrumented** (`androidTest`, live network, on the emulator or a device on an allowed network):
- The upstream `PoTokenProviderImplTest` `…4` cases: token non-empty, the empty-videoId path, `PoTokenResponse`.
- Replace the hard-coded `124` with "≤ 130 chars and `challenge=homepage`": upstream's own comment says 120 with `ytcfg`, and our old flow gives 204+.

### 5.5 How to verify on device

**Constraints.**
- The home IP is bot-challenged today, and the emulator shares it. Run on the Pixel over Movistar LTE using the signed-out `.check` side-by-side package (not the owner's signed-in app; `pixel-account-hands-off`), under the HARD Pixel harness rules (focus/shade/call guard; no airplane mode). Or wait until the home IP is clean.
- Debug builds are for correctness (`load[S|C|E]` lines); compiled release/benchmark builds are for timing only.
- Client forcing (`debug.arc.player_client`) is DEBUG-only, and WEB_MUSIC is not forceable today (`inventory-engine.md` §5.1).

**Expected NetPath after the port:**
```
web-pot-session new reason=initial visitorSource=app visitor=<fp> prevAgeMs=-1 buildMs=<~1-3 s>
  binding=streaming:visitor,player:video generator=PoTokenWebView4 challenge=homepage ytcfg=y
  eventId=y contentFlag=y pageHost=www.youtube.com ttl=43200
web-pot-mint video=<id> len=~120 ms=<~10-30>          (debug)
player-context video=<id> client=MWEB … visitorSource=web-pot … playerPot=y
player-http[S] rid=… client=2 … pot=y …
```

**Failure signals:**
- `challenge=att-get` plus a reason: the homepage path failed (network, parse, or no `ytAtN`). Read `pageHost`; a `consent.*` host points to a possible consent response (confirm with the `SOCS` A/B).
- `generator=PoTokenWebView … fallbackFrom=PoTokenWebView4`.
- `web-pot-session failed`, `player-mint-failed`.
- **Token length is descriptive, not an invariant.** Old-flow content tokens were 204–460 chars in 8 mints; upstream reports ~120 for WV4. Use a jump in `len` as a hint to cross-check against `challenge=`, never as proof.

**Arms**, each ≥5 videos × ≥3 opens × Wi-Fi and LTE, decoded playback to 150 s or EOF with a seek past 50 % (`DESIGN.md` §4 acceptance):
- **A: minting A/B.** `debug.arc.pot_generator=1` (old Create) vs `=4`, alternating, same videos, on a route that actually uses `pot=` URLs. Today that is MWEB with `supportXhr=false` (Step 3 profile) or live `/pot/`.
  - Pass = no `load[E-http] code=403` on web-pot URLs after ~30 s with `=4`.
  - bgutil's 58 → 92 % is background evidence from a different population and procedure (yt-dlp audio downloads through residential proxies). It is not a predicted result.
- **B: live `/pot/` content vs session** on 2–3 24/7 streams (web-pot client forced).
- **C: page-session consistency.** Homepage GET with vs without the persistent `VISITOR_INFO1_LIVE` cookie, so the page visitor equals the `/player` visitor [inferred to be unnecessary, per bgutil; cheap to confirm].
- **D: time to first frame.** Compiled release: a cold share-link open with a web-family fallback, with the warm-up raced vs not. `buildMs` before/after; VISIONOS opens must be unchanged (it peeks the visitor).
- **E (Step 3):** WEB_MUSIC and TV_SIMPLY sustained past media 60 s with tokens (netbench `sustain` can replicate this once the harness accepts an injected token from the device).

**Stop rule:** any `bot-check trip` / `player-ring botwall` during a run ends that session's arms (request budget).

### 5.6 Risks

1. **EU consent** [hypothesis, high impact if true]: see §5.1 step 1.6. Without `SOCS`, the new flow may silently degrade to `/att/get` for Spanish users. It shows only in `challenge=`/`pageHost=`.
2. **The benefit is capped by delivery** (§0.9). With today's request profile, the web family answered SABR-only in the 2026-09-28 Pixel walk, so the port alone may change few user-visible outcomes until MWEB-URL or SABR-with-token routes exist.
3. **Upstream bugs carried in:** the init-order, mint-timeout and uncaught-homepage-error issues in WV4, plus the `parseLooseJSON` `toString` fragility. All are fixed in the port. Upstream has shipped WV4 as default since 08-12 on TV devices, which is some field validation [inferred].
4. **Extra traffic and fingerprint:**
   - One desktop-homepage GET plus an interpreter GET per generator build, including every recovery reset (≤ 1/60 s).
   - A cookieless Mac-UA homepage load from a phone IP [inferred low risk; bgutil does the same from proxies].
   - It also changes the WebView UA from Windows Chrome 131 to the BgUtils Mac string.
5. **A/B variance:** enforcement is bucketed. bgutil's 92 % is not 100 %, and #242 saw another MWEB blip in mid-September. One-sample device results must not enable defaults.
6. **Markup drift:** `ytcfg.set(`/`ytAtN(` regexes on HTML. The fallback chain keeps playback going, but the telemetry must page us (`challenge=` ≠ homepage).
7. **Old WebViews:** `po_token2.html` + a large injected `ytcfg` literal on Android 7–9 WebViews [inferred OK; same polyfills as today; VoidTube has never selected WV2/3/4].
8. **Testing hazard:** the home IP is walled; the emulator shares it. Budget LTE sessions.
9. **Upstream merge hazard:** the unrelated change scope is smaller than cherry-picking, but it does not go away.
   - `PoTokenWebView4.kt` becomes a locally adapted copy: OkHttp 4, RxJava 3, the VOIDXDEV fixes.
   - Every future upstream edit to it needs a manual reconcile. Mark the local changes `VOIDXDEV(pot-wv4)` so the next diff is readable.
   - A later full upstream merge still has to keep `PoTokenSelection` and the VOIDXDEV visitor logic.

### 5.7 Effort

Planning estimates, not measurements. Implementation labor and verification labor are counted separately, and neither includes calendar slack for bot walls.

| item | implementation (person-days) |
|---|---|
| Step 0 telemetry | 0.5 |
| Step 1 minter port + adaptations + the five fixes | 1.5–2 |
| Step 2 live binding | 0.5 |
| Unit + instrumented tests | included above (written alongside) |
| **Core implementation** | **≈ 2.5–3.5** |
| Step 3: MWEB / WEB_MUSIC / TV_SIMPLY (TV challenge) | +0.5–1 / +0.5–1 / +1.5–2 |
| Cold start (with the SABR `po_token` work only) | +0.5 |
| Step 4 cleanup | +0.25 |

**Verification labor**, with the acceptance bar from `DESIGN.md` §4 (≥5 videos × ≥3 opens × 2 networks = 30 opens per condition, up to 150 s each):
- Arm A has 2 conditions: 60 opens ≈ 2.5 h of playback.
- Arms B (2 conditions × 2–3 live streams × 3 opens × 2 networks), C (2 conditions, 30 opens each) and D (compiled builds, ~10 cold opens per condition) add roughly 3–4 h.
- **≈ 6–7 h of device time**, plus setup, log pulls and analysis: **≈ 1.5–3 person-days**.
- Calendar time is longer: sessions must be spread out and stop at the first bot wall.

Commit order (project CLAUDE.md: the submodule forks use `master`): MSC fork commit(s) first (push `origin master`), then the main-repo pointer bump + `MMA` debug override on `main`. SharedModules is untouched if the helper is inlined.

### 5.8 Open questions to settle with the first device run

- Does the cookieless homepage from Spain carry `ytAtN` (with and without `SOCS=CAI`)?
- Does the homepage `ytcfg` itself carry `html5_generate_content_po_token`, or only watch pages? The observed flag was "on every page" of the watch/embed surfaces.
- Are WV4 content tokens ~120 chars on device, like upstream reports and like the official 85–90 bytes?
- Does MWEB with `supportXhr=false` keep returning URL formats across sessions (netbench: yes in one sweep, "SABR-only in the next" once)?
- WEB_MUSIC: is the old WAA token already enough (LuanRT), making Step 3's WEB_MUSIC arm independent of the port?

---

## Review (cross-model, 2026-09-28)

OpenAI `gpt-6-astra` (codex-agent, read-only, 47k tokens) reviewed a draft of this file. Raw output:
`…/scratchpad/potreview/review.md`.

**It declined to check the BotGuard mechanics:** token binding, endpoints and headers, timeouts, compile dependencies. Per the standing rule those checks stayed in-house.
- I re-read the fork at `16e67076` and upstream at `476357b4` for every line cited in §1–§2.
- The in-house pass added two findings the draft missed: §5.1 step 1.8, the uncaught homepage error that skips `/att/get`, and step 1.9, the `parseLooseJSON` `toString` fragility.

**Accepted and applied:**
- The 55–60 s refusal is one sustain run per client, not an established wall.
- Cold-start on URL formats is "unmeasured", not "no".
- "SABR-only on the phone" is qualified by build, profile, video and date.
- A consent host is a hypothesis to test, not a diagnosis.
- The path-limited port leaves a locally adapted file that still needs reconciling.
- Token sizes are descriptive, and bgutil's 58 → 92 % is background, not a prediction.
- Effort now separates implementation from verification labor, with the verification counted out.

**Rejected:** "pushing to `master` conflicts with the workflow". The reviewer applied a generic main-branch rule. This project's CLAUDE.md says the MSC and SharedModules forks use `master` and the main repo uses `main`, which is what §5.7 says.
