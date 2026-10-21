> Copied from a private working folder on 2026-09-28: the tools it names (harness, appbench, recap, proxy, corpus) now live in `tools/netbench/`; the raw results and logs it cites are not in the repo.

# VoidTube /player engine inventory (ground truth for the source benchmark)

Written 2026-09-28 by reading the code, READ-ONLY. Nothing was built or run in the repo.

- Main repo `smarttube-port` at `985d04c1` (branch `main`), plus uncommitted edits to `MobileMainApplication.java`.
- `MediaServiceCore` (MSC) at `795091f7`, plus the **uncommitted WEB_EMBED work** in 9 main and 3 test files.
- `[WT]` marks a value or behaviour that the uncommitted working tree changed. The HEAD value is given next to it.
- `[inferred]` marks a conclusion drawn from code or logs that no test or measurement confirms.
- Paths are relative to `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/` unless they start with `smarttubetv/`, `common/` or `~`. `VIS` = `videoinfo/V2/VideoInfoService.java`. `QB` = `common/helpers/QueryBuilder.kt`. `AC` = `common/helpers/AppClient.kt`. `MMA` = `smarttubetv/src/stmobile/java/com/newtube/mobile/MobileMainApplication.java`.
- Several source files are CRLF, and logcat dumps contain NUL bytes, so use `grep -a`.

---

## 0. Summary

- **21 AppClient entries.** 13 are in `VIDEO_INFO_TYPE_LIST`. On the phone, 9 of those 13 are actually asked, plus TV_DOWNGRADED when signed in. The others:
  - `VISIONOS` is added in front as the fast head.
  - `TV_TIZEN` is added only as the "account route".
  - `ANDROID`, `ANDROID_SDK_LESS`, `WEB_CREATOR`, `WEB_MUSIC`, `TV_KIDS` and `INITIAL` are never reached, and cannot be forced with the debug hook.
- **Signed-out normal walk, working tree:** `VISIONOS → WEB → WEB_SAFARI → GEO → MWEB → ANDROID_VR → ANDROID_REEL → TV → IOS → WEB_EMBED`.
  - A recovery walk never includes VISIONOS.
  - At HEAD, WEB_EMBED is skipped entirely.
- **Every client sends the same request skeleton:**
  - `context.client` (acceptLanguage/acceptRegion, no hl/gl) and `user` (safety mode off).
  - `playbackContext` (always carries a signatureTimestamp, `-1` when unknown; `supportXhr`).
  - `racyCheckOk`/`contentCheckOk`, `videoId` and `cpn`.
  - Every client except WEB_EMBED sends `devicePlaybackCapabilities.supportXhr=true` unless it is a TV client. yt-dlp sends no `devicePlaybackCapabilities` at all. The WEB_EMBED fix showed that `supportXhr=true` produced SABR-only answers **for that client**; nobody has tested the others.
- **Almost every client carries the same visitor:**
  - The persistent `youtube.com/tv` visitor goes to all clients. The "web-pot" session adopts the same value (see §1.4).
  - The one exception is WEB_EMBED `[WT]`, which now carries the embed page's own visitor.
  - Real Pixel log of 2026-09-28: `visitor=3b756832b9` on VISIONOS, WEB, WEB_SAFARI, GEO, MWEB, ANDROID_VR, ANDROID_REEL, TV and IOS; `b7b1dc0074` on WEB_EMBED.

---

## 1. Every AppClient entry

### 1.1 Identity (what is on the wire)

Ordinal = the persisted "winner" value (`getData().setVideoInfoType`, by ORDINAL; `VIS:3410-3416`). Ring = `VIDEO_INFO_TYPE_LIST` membership. Name id = the `X-Youtube-Client-Name` header, from `CLIENT_NAME_IDS` in `innertube/utils/Constants.kt:196-212`.

| ord | enum | clientName | name id | clientVersion | HTTP + body `userAgent` | derived `browserName`/`browserVersion` (`AC:237-259`) | device/OS fields in body | ring? |
|---|---|---|---|---|---|---|---|---|
| 0 | TV | TVHTML5 | 7 | 7.20260707.07.00 | Cobalt 25 "current" (`DefaultHeaders.kt:39`) | Cobalt / 25.lts.30.1034943-gold | none | yes |
| 1 | TV_LEGACY | TVHTML5 | 7 | 7.20260707.07.00 | = TV | = TV | none | yes (phone-skipped) |
| 2 | TV_EMBED | TVHTML5_SIMPLY_EMBEDDED_PLAYER | 85 | 2.0 | Fire TV Cobalt 22 (`DefaultHeaders.kt:48-49,81`) | Cobalt / 22.lts.3.306369-gold | none; `clientScreen=EMBED` (`AC:46`) | yes (phone-skipped) |
| 3 | TV_SIMPLY | TVHTML5_SIMPLY | **74** (yt-dlp: 75, `~/projects/yt-dlp/.../_base.py:388`) | 1.0 | Fire TV | Cobalt / 22.lts.3.306369-gold | none | yes (phone-skipped) |
| 4 | TV_KIDS | TVHTML5_KIDS | **none** (innerTubeName null, `AC:50`) → header omitted | 3.20231113.03.00 | Fire TV | Cobalt / 22.lts… | none | no |
| 5 | TV_DOWNGRADED | TVHTML5 | 7 | 5.20260707 (`AC:52`) | `Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version` | Cobalt / "Version" | none | yes (phone-skipped signed out) |
| 6 | WEB | WEB | 1 | 2.20260708.00.00 | Chrome 147 desktop (`DefaultHeaders.kt:65,82`) | Chrome / 147.0.0.0 | none | yes |
| 7 | WEB_EMBED | WEB_EMBEDDED_PLAYER | 56 | 2.20260708.00.00 | `[WT]` Safari 15.5 `…Safari/605.1.15,gzip(gfe)` (`AC:57-61`, `DefaultHeaders.kt:76`); HEAD: Chrome 147 | `[WT]` Safari / 605.1.15 (HEAD: Chrome / 147.0.0.0) | none; **`clientScreen=WATCH`** (default, `AC:31,39`) | yes (see §2) |
| 8 | WEB_CREATOR | WEB_CREATOR | 62 | 1.20241203.01.00 (yt-dlp 1.20260708.06.00) | Chrome 147 | Chrome / 147.0.0.0 | none | no |
| 9 | WEB_MUSIC | WEB_REMIX | 67 | 1.20250219.01.00 (yt-dlp 1.20260707.12.00) | Chrome 147 | Chrome / 147.0.0.0 | none | no |
| 10 | WEB_SAFARI | WEB | 1 | 2.20260708.00.00 | Safari 15.5 (same string as WEB_EMBED `[WT]`) | Safari / 605.1.15 | none | yes |
| 11 | MWEB | MWEB | 2 | 2.20260708.05.00 | `Mozilla/5.0 (Linux; Android 10; K) … Chrome/147.0.0.0 Mobile Safari/537.36` (yt-dlp uses an iPad Safari UA) | Chrome / 147.0.0.0 | none | yes |
| 12 | ANDROID | ANDROID | 3 | 21.26.364 | `com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip` | none | `"androidSdkVersion":"30"` (a STRING; yt-dlp sends an int), `osName Android`, `osVersion 11` (`AC:71-73`) | no |
| 13 | ANDROID_SDK_LESS | ANDROID | 3 | 21.26.364 | = ANDROID | none | `osName`/`osVersion` only | no (commented out: "hangs on cronet!") |
| 14 | ANDROID_REEL | ANDROID | 3 | 21.26.364 | = ANDROID | none | = ANDROID; **different endpoint and body wrap** (§1.3) | yes |
| 15 | ANDROID_VR | ANDROID_VR | 28 | 1.65.10 | `com.google.android.apps.youtube.vr.oculus/1.65.10 (…Android 12L…) gzip` | none | `androidSdkVersion "32"`, `osName Android`, `osVersion 12L`, `deviceModel Quest 3`, `deviceMake Oculus` (`AC:76-79`) | yes |
| 16 | IOS | **iOS** (yt-dlp: `IOS`) | 5 | 21.26.4 | `com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)` | none | `deviceModel iPhone16,2`, `osVersion 18.3.2.22D82`. No `deviceMake`/`osName` (yt-dlp sends Apple/iPhone) (`AC:80-81`) | yes |
| 17 | INITIAL | WEB (clone) | 1 | — | — | — | **not a /player call**: scrapes `ytInitialPlayerResponse` from `watch?v=…&bpctr=…` (`innertube/initialresponse/InitialResponseService.kt`) | no |
| 18 | GEO | WEB (clone) | 1 | 2.20260708.00.00 | Chrome 147 | Chrome / 147.0.0.0 | none; adds top-level `"params":"CgIQBg%3D%3D"` (a URL-encoded string inside JSON, `QB:11,289-296`) | yes |
| 19 | VISIONOS | VISIONOS | 101 | 1.02 | `Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) … Version/26.0 Safari/605.1.15` | Safari / 605.1.15 | `deviceMake Apple`, `deviceModel RealityDevice17,1`, `osName visionOS`, `osVersion 26.5.23O471` (`AC:88-91`) | **no** (the phone's head, injected) |
| 20 | TV_TIZEN | TVHTML5 | 7 | 5.20260707 | `Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 … Version/2.4.0 TV Safari/538.1` (`AC:29-30`) | Safari / 538.1 | `deviceMake Samsung`, `deviceModel SmartTV`, `osName Tizen`, `osVersion 2.4.0` (`AC:27-28,123`) | **no** (account route only) |

Other identity facts:

- `clientScreen` is `WATCH` for every client except TV_EMBED (`AC:31-32`). That includes WEB_EMBED. yt-dlp builds web_embedded's context from the embed page's own ytcfg, and has a test named "Age-gated video embedable only with clientScreen=EMBED" (`_video.py:374`). See §6.
- `AppClient.referer` is never sent on /player. It is used only by `getRefererUrl()` (`AC:135-144`) to build the embed page URL `https://www.youtube.com/embed/<id>?html5=1` for the ytcfg fetch.
- The browser fields come from sniffing the UA with the regex `<name>/([a-zA-Z0-9.-]+)`, trying SamsungBrowser, LG Browser, Cobalt, Chrome and Safari in that order. For the Safari UAs this yields `605.1.15`, the WebKit build, not `15.5`.

### 1.2 Predicates (`AC:156-235`)

| enum | isWebPotRequired (182) | isEmbedded (235) | isTVClient (214, name starts with "TV") | isWebClient (234) | isAuthSupported (156) / isAuthCapable (171) | isSabrSupported (210) | isPlayerPotSupported (181) | usesTvSignatureTimestamp (233) |
|---|---|---|---|---|---|---|---|---|
| TV | – | – | ✓ | – | ✓ | – | – | ✓ (+001) |
| TV_LEGACY | – | – | ✓ | – | ✓ | – | – | ✓ |
| TV_EMBED | – | ✓ | ✓ | – | ✓ | – | – | – |
| TV_SIMPLY | – | – | ✓ | – | – ("Can't use authorization") | – | – | – |
| TV_KIDS | – | – | ✓ | – | ✓ | – | – | – |
| TV_DOWNGRADED | – | – | ✓ | – | ✓ | – | – | ✓ |
| WEB | ✓ | – | – | ✓ | – (debug `web_auth=WEB` makes it capable) | – | – | – |
| WEB_EMBED | ✓ (still true `[WT]`, but it now never gets a token) | ✓ | – | ✓ | – (debug `web_auth=1`) | – | – | – |
| WEB_CREATOR | – | – | – | ✓ | – | – | – | – |
| WEB_MUSIC | – | – | – | ✓ | – | – | – | – |
| WEB_SAFARI | ✓ | – | – | ✓ | – | – | – | – |
| MWEB | ✓ | – | – | ✓ | – | – | – | – |
| ANDROID | – | – | – | – | – | ✓ | – | – |
| ANDROID_SDK_LESS | – | – | – | – | – | ✓ | – | – |
| ANDROID_REEL | – | – | – | – | – | ✓ | – | – |
| ANDROID_VR | – | – | – | – | – | ✓ | ✓ | – |
| IOS | – | – | – | – | – | ✓ | – | – |
| INITIAL | ✓ | – | – | ✓ | – | – | – | – |
| GEO | ✓ | – | – | ✓ | – | – | – | – |
| VISIONOS | – | – | – | – | – | ✓ | – | – |
| TV_TIZEN | – | – | ✓ | – | ✓ | – | – | – (real 5-digit sts) |

`isPlaybackBroken` (`AC:212`) and `PoTokenGate.getColdStartPoToken` have no callers. They are dead code.

### 1.3 Request payload per client

Legend for the visitor column:
- **app** = `AppService.getVisitorData()`: the persistent visitor from `GET https://www.youtube.com/tv`. That fetch uses the Fire TV UA, the stored `VISITOR_INFO1_LIVE` and a SOCS consent cookie (`app/AppServiceInt.java:26-49`). The value is persisted for 10 h (`AppServiceIntCached`).
- **web-pot** = `PoTokenGate.getWebVisitorDataForPlayer()` (`app/PoTokenGate.kt:195-207`). It peeks or builds the BotGuard session's visitor. That session adopts `AppService.visitorData` verbatim unless a rotation is armed (`app/potokennp2/PoTokenProviderImpl.kt:145-148`), and rotation is disabled on the phone. So in practice **web-pot = app**, the same string.
- **embed-page** `[WT]` = `YtCfgService.getEmbedIdentity(videoId).visitorData` (`innertube/ytcfg/YtCfgService.kt:32-61`).

Where the visitor is chosen: `VideoInfoApiHelper.java:36-39,53-63`. It goes into BOTH `context.client.visitorData` and the `X-Goog-Visitor-Id` header.

| enum | visitor | PO token in /player body | PO token on media URLs (`pot=`) | supportXhr (`QB:321`) | signatureTimestamp | other body chunks | request specifics | per-attempt timeout (`VIS:2955-2973`) |
|---|---|---|---|---|---|---|---|---|
| TV | app | none | none | false | 8-digit (5-digit + "001", `QB:80-83`) | – | – | 15 s (it is in AUTHENTICATED_HEAD, even when signed out) |
| TV_LEGACY | app | none | none | false | 8-digit | – | /player identical to TV (differs only in `postDataBrowse`) | 7 s |
| TV_EMBED | app | none | none | false | 5-digit | `thirdParty.embedUrl=https://www.reddit.com/`; `encryptedHostFlags` looked up from the **WEB** embed page but paired with the app visitor (`QB:348-349`), i.e. the same mismatch that caused 152-18 `[inferred]` | QueryBuilder.build() itself does a network fetch for it | 7 s |
| TV_SIMPLY | app | none | none | false | 5-digit | – | – | 7 s |
| TV_KIDS | app | none | none | false | 5-digit | – | no X-Youtube-Client-Name header | 7 s |
| TV_DOWNGRADED | app | none | none | false | 8-digit | – | – | 15 s |
| WEB | web-pot | video-bound BotGuard token (`WEB_CONTENT` → `playerRequestPoToken`, `PoTokenGate.kt:82-93`) | the same video-bound token (`VideoInfoServiceBase.java:171-177`); live manifests get `/pot/<streamingDataPoToken>` (`:127-128`) | true | 5-digit | `serviceIntegrityDimensions.poToken` | – | 20 s |
| WEB_EMBED | `[WT]` embed-page visitor; if the page fetch fails, falls back to web-pot with no flags → 152-18 expected. HEAD: web-pot | `[WT]` **none** (`PoTokenSelection.kt:57`). HEAD: video-bound token | `[WT]` none, including live manifests. HEAD: video-bound token | `[WT]` **false** (HEAD: true) | 5-digit | `thirdParty.embedUrl=reddit`; `[WT]` `encryptedHostFlags` only from the same EmbedIdentity (`QB:340-356`). HEAD: `getCachedEncryptedHostFlags` (cached per process forever), paired with the web-pot visitor | the embed page `GET https://www.youtube.com/embed/<id>?html5=1` (Safari UA, `Referer: https://www.reddit.com/`, `YtCfgApi.kt`) runs inside the attempt; cached 6 h (`YtCfgService.kt:12`); invalidated when the answer text contains "152" (`VIS:1316-1322`) or on recovery (`PoTokenGate.kt:226-229`) | 20 s |
| WEB_CREATOR | app | none (not web-pot) | none | true | 5-digit | – | – | 7 s |
| WEB_MUSIC | app | none | none | true | 5-digit | – | – | 7 s |
| WEB_SAFARI | web-pot | video-bound token | video-bound token | true | 5-digit | poToken | – | 20 s |
| MWEB | web-pot | video-bound token | video-bound token | true (the `[WT]` comment at `QB:303-304` says MWEB "behaves the same way" as WEB_EMBED but was left unchanged) | 5-digit | poToken | – | 20 s |
| ANDROID | app | none | none | true | 5-digit | – | – | 7 s |
| ANDROID_SDK_LESS | app | none | none | true | 5-digit | – | – | 7 s |
| ANDROID_REEL | app | none | none | true | 5-digit | `racyCheckOk`/`contentCheckOk`/`videoId`/`cpn` wrapped in `"playerRequest":{…}` (`QB:226-231`); `playbackContext` stays top-level | **`POST https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch`**; response read from `$.playerResponse`. No `Origin`. The interceptor adds `Referer: https://www.youtube.com/tv`, `key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8` and a random `t=` (`RetrofitOkHttpHelper.kt:86,202-213`). Logged as `api-http`, NOT `player-http`. Only the 10 s connect bound applies, not the 8 s /player bound | 7 s |
| ANDROID_VR | web-pot | none by default; video-bound token only with `debug.arc.player_pot=1` (`PoTokenSelection.kt:60`) | none (never, by design) | true | 5-digit | – | – | 7 s |
| IOS | app | none | none | true | 5-digit | – | – | 7 s |
| INITIAL | n/a (page scrape) | n/a | web token (isWebPotRequired) | n/a | n/a | – | not a /player call | 20 s |
| GEO | web-pot | video-bound token | video-bound token | true | 5-digit | `"params":"CgIQBg%3D%3D"` | – | 20 s |
| VISIONOS | web-pot (`VideoInfoApiHelper.java:60-63`) | none | none | true | 5-digit | – | – | 7 s |
| TV_TIZEN | app | none | none | false | **real 5-digit** (`AC:233`) | – | – | 15 s (`VIS:2959-2961`) |

Signature and n-transform:
- Both are data-driven, not decided per client. `VideoInfoServiceBase.decipherFormats` (`:135-178`) collects every format's `s` (signatureCipher) and `n` and solves them with the V8/EJS solver on the **TV player JS** (the playerUrl comes from `youtube.com/tv`). The `player-sig` NetPath line reports distinct/non-null counts per open.
- **Needs no JS player:** VISIONOS, ANDROID_VR, ANDROID, IOS. This comes from the comments at `Constants.kt:100-105` and `VIS:46-47`, and from yt-dlp's `REQUIRE_JS_PLAYER=False` for these clients.
- **Ciphered (signature and n):** TV_TIZEN (`AC:97`, "~0.2-0.6 s signature solve"), TV_DOWNGRADED and TVHTML5_SIMPLY ("22 ciphered formats", `AC:111-112`, HANDOFF §26). WEB_EMBED answered with signatureCipher on `dQw4w9WgXcQ` (`~/projects/newtube-launch/issue5/findings.md`).
- **WEB_EMBED, `_WB5hh7WOb4` family:** direct URLs with `n` only, `player-sig … n=3/26 s=0/0` (Pixel, 2026-09-28).
- **Every client still asks for `signatureTimestamp`.** So even token-free and cipher-free clients depend on the player JS having been fetched and parsed. When sts is unknown the body carries `-1` (test `QueryBuilderTimestampTest.missingOrInvalidCachedTimestampRetainsPreviousSentinelWithoutCrashing`).

### 1.4 Common body and headers (for byte-identical reproduction)

**Body.** Built by `QueryBuilder.build()` (`QB:52-109`). Each template line is trimmed and the lines are concatenated with no separator. Then `removeTrailingObjectCommas` runs. The inner `"key": value` spaces survive (`"clientName": "VISIONOS"`), while `postData` fragments have none (`"osName":"visionOS"`).

Key order:
1. `context.client` in this order:
   1. clientName, clientVersion, clientScreen, userAgent
   2. [browserName, browserVersion]
   3. [postData fields]
   4. acceptLanguage, acceptRegion, utcOffsetMinutes (a **string**)
   5. [visitorData]
2. `context.clickTracking`, only when the open came from a card.
3. `context.user`.
4. `context.thirdParty` (embedded clients only).
5. `playbackContext`.
6. `serviceIntegrityDimensions`.
7. `racyCheckOk`, `contentCheckOk`, `videoId`, `cpn`.
8. `params` (GEO only).

Language, region and offset come from `LocaleManager`: the saved app locale, else the system locale.

There is **no `hl`/`gl`**, and no `lactMilliseconds`/`isInlinePlaybackNoAd` in yt-dlp's version. Our `playbackContext.contentPlaybackContext` has `html5Preference`, `lactMilliseconds: 60000`, `isInlinePlaybackNoAd: true`, `signatureTimestamp` and [`encryptedHostFlags`]. `devicePlaybackCapabilities` is `{supportsVp9Encoding: true, supportXhr: …}` (`QB:298-326`). yt-dlp sends only `html5Preference`, `signatureTimestamp` and [`encryptedHostFlags`] (`~/projects/yt-dlp/yt_dlp/extractor/youtube/_video.py:2692-2712`).

Derived from the code, not executed. This is WEB_EMBED `[WT]`; confirm with the harness in §4:

```
{"context": {"client": {"clientName": "WEB_EMBEDDED_PLAYER","clientVersion": "2.20260708.00.00","clientScreen": "WATCH","userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)","browserName": "Safari","browserVersion": "605.1.15","acceptLanguage": "<lang>","acceptRegion": "<CC>","utcOffsetMinutes": "<min>","visitorData": "<embed VISITOR_DATA>"},"user":{"enableSafetyMode": false,"lockedSafetyMode":false},"thirdParty": {"embedUrl": "https://www.reddit.com/"}},"playbackContext": {"contentPlaybackContext": {"html5Preference": "HTML5_PREF_WANTS","lactMilliseconds": 60000,"isInlinePlaybackNoAd": true,"signatureTimestamp": <sts>,"encryptedHostFlags":"<flags>"},"devicePlaybackCapabilities": {"supportsVp9Encoding": true,"supportXhr": false}},"racyCheckOk": true,"contentCheckOk": true,"videoId": "<id>","cpn": "<16-char cpn>"}
```

**Headers on `POST https://www.youtube.com/youtubei/v1/player?prettyPrint=false`.** From `videoinfo/V2/VideoInfoApi.java:16-19` and `googlecommon/common/helpers/RetrofitOkHttpHelper.kt:179-230`:

- `Content-Type: application/json`. The `@Headers` value overrides the converter's `; charset=UTF-8`, and the body is the raw string (`JsonPathRequestBodyConverter.java:21`).
- `Origin: https://www.youtube.com`
- `X-Goog-Visitor-Id: <same visitor as the body>`. If null, the interceptor falls back to the app visitor (`:194-195`).
- `User-Agent: <client UA>`
- `X-Youtube-Client-Name: <id>` (absent for TV_KIDS)
- `X-Youtube-Client-Version: <clientVersion>`
- `Accept-Encoding: gzip, deflate, br`. The phone sets the brotli gate (`MMA:455`, `DefaultHeaders.kt:99-104`); TV and tests without it send `gzip, deflate`.
- No `Referer` for /player (`:202-204`). No `Cookie`: there is no cookie jar, and `player-http` logs `cookie=n`.
- Anonymous requests carry **no `key=`** (`:208-213`). When signed in AND the client is auth-capable, `Authorization: Bearer <TV device-flow OAuth>` is added, plus `X-Goog-Pageid` for brand accounts (`YouTubeSignInService.java:326-332`), still without a key.
- Transport: HTTP/2 on the phone (`MMA:87`). OkHttp limits /player to 8 s connect and 8 s read (`RetrofitOkHttpHelper.kt:24,161-177`).

---

## 2. The ring as the phone runs it

### 2.1 Raw list (upstream's; never edit it, per CLAUDE.md)

`VIS:44-59`: `WEB_EMBED, ANDROID_VR, ANDROID_REEL, TV, WEB, WEB_SAFARI, IOS, GEO, MWEB, TV_LEGACY, TV_DOWNGRADED, TV_EMBED, TV_SIMPLY` (13 entries).

### 2.2 Phone gates (`MMA`, all set once in `onCreate`; TV never calls them)

| line | call | effect on /player |
|---|---|---|
| 87 (static init) | `OkHttpManager.setPreferHttp2(true)` | InnerTube over H2 (logged as `protocol=h2`) |
| 212 | `setPreferNoPotClient(true)` | VISIONOS becomes the head (`VIS:69,1141-1146`); all timeouts and the 45 s budget turn on; fixes run async; winner restore; mobile-only bot-check and consensus logic |
| 218 | `setPreferAttestedWebFallback(true)` | Web-family partition in `buildVisitOrder`; the recovery cursor and the persisted hint are one-shot (`VIS:1015-1018`) |
| 225 | `setKeepSigRuntimeAlive(true)` | keeps the V8 solver runtime between opens |
| 253-334 | DEBUG-only block | `debug.arc.player_client`, `player_pot`, `web_auth`, `botwall`, `early_preconnect`, `keep_sig_runtime`, `eager_cold` (§5) |
| 341 | `setPreferDashManifestForLive(true)` | holds a live answer without DASH and walks on, to ANDROID_VR only (`VIS:1492-1497,1569-1571`) |
| 350 | `setSkipLiveDashInfoWithManifest(true)` | skips the googlevideo dash-info probe when a manifest URL exists |
| 357 | `setSkipTvFallbackClients(true)` | `isSkippedClient`: TV_LEGACY, TV_DOWNGRADED, TV_EMBED and TV_SIMPLY are passed over (`VIS:203-205,446-450`); TV_DOWNGRADED is exempted when signed in (`VIS:1267-1271`) |
| 364 | `[WT]` `setWebEmbedLast(true)`; HEAD had `setSkipWebEmbed(true)` | `[WT]` moves WEB_EMBED to the end of every non-forced, non-walled order (`VIS:1185-1187,294-308`). HEAD skipped it |
| 374 | `setAuthRouteQuarantineStore(...)` | the 403 / no-media quarantine of the TV heads survives restarts |
| 380 | `setBotWallStore(...)` | the BotWallBook persists within a boot |
| 382-391 | (not called) `setRotateVisitorOnAnonChallenge` | visitor rotation retired 2026-09-25 |
| 398 | `setSkipStoryboardEnrichment(true)` | no deferred IOS /player for storyboards |
| 455 | `DefaultHeaders.setBrotliEnabled(true)` | `Accept-Encoding: gzip, deflate, br` |
| 463-464 | `setPrefetchOnOpenEnabled`, `setSingleFlightEnabled` | /player starts at tap time; concurrent same-id fetches share one walk |
| 486 | `setPersistedAppInfoEnabled(!fresh_app_info)` | the app visitor and playerUrl come from prefs (10 h) |
| 503 | `SabrSourcePreference.initialize` | `SabrVodCapability.enabled` = user "Prefer SABR" OR debug `sabr_fallback`/`sabr_vod` (changes "playable", §2.6) |
| 543-546 | `warmUpPoTokenGate()` after the first frame | BotGuard WebView warm-up |

Not set on the phone: `setPlayerPotEnabled` (debug only), `setWebEmbedAuthEnabled`/`setWebAuthClient` (debug only) and `setSkipWebEmbed` `[WT]`.

### 2.3 Before any /player: things that return without walking

- **Format cache** (`YouTubeMediaItemFormatInfo.isCacheActual`, `:484-496`). It needs media, a current player cache and a live web pot. `[WT]` The pot condition no longer applies to WEB_EMBED. Entries are scoped to the network attachment their URLs came from (2fc6b599). The Pixel report of 2026-09-28 shows `cached WEB_EMBED` reuse 48-68 s later with no /player call.
- **Negative cache:** the same videoId within 30 s returns the stored unplayable verdict (`YouTubeMediaItemService.java:151`).
- **Bot-check circuit** (`VIS:995-998,2761-2820`). An armed circuit returns the stored challenge for any video. On mobile this happens only if the arming walk exhausted the ring, and one probe per 60 s passes through. It is cleared on a network change. Signed-in users get one bypass.
- **Walled plan with an empty order:** zero requests; the verdict is attributed to VISIONOS (`VIS:1176-1178,1735-1741`).

### 2.4 How the order is built (`VIS:1097-1213`)

1. **lastWinner** = the recovery suspect during a recovery walk, else `mActualInfoType`. `mActualInfoType` is overwritten by every resolution, prefetches included (`VIS:1105-1106`).
2. **beginType:**
   - Signed in and not recovering: `TV_DOWNGRADED`, or `WEB_EMBED` when both heads are quarantined (`authenticatedWebFirst`). It ignores the cursor.
   - Otherwise: `mNextInfoType`, which is the recovery cursor or the restored persisted winner. Failing that, it is VISIONOS (`VIS:1135-1146`).
3. **Forced client** (debug): the order is the single forced client and nothing else is applied (`VIS:1162-1164`).
4. **Walled network** (BotWallBook): order = the plan (§2.5 d).
5. **Otherwise:**
   1. `buildRequestVisitOrder` (`VIS:2377-2415`) → `buildVisitOrder` (`VIS:2864-2947`): the begin client, then lastWinner (unless deferred), then the rest of the ring. The Web-family partition applies when signed out, recovering or web-first.
   2. Signed in, not recovering: `promoteAuthenticatedTvFallback`, then `insertTokenFreeClientBeforeWebPot` (VISIONOS goes before the first web-pot client), then `demoteQuarantinedHeadBehindTokenFreeClient`.
   3. Signed in and (recovering or web-first): `leadWithTokenFreeClient` puts VISIONOS first.
   4. Anonymous challenge active: `deprioritizeWebPotClients` moves all web-pot clients, WEB_EMBED included, to the back.
   5. `[WT]` `moveWebEmbedLast` (skipped only when `debug.arc.web_auth=1`).
6. **Inside the loop:** skip `isSkippedClient`; skip non-candidates while a live answer is held; count the budget; send; log; update bot-wall and auth-route evidence; check transport-down; bot-check hold or trip; unplayable consensus; return the first playable (`VIS:1242-1506`).

### 2.5 Effective orders (derived from the code; confirmed where tests or logs exist)

Skipped TV clients are removed. `+auth` means an Authorization header is sent when signed in.

**(a) Signed-out normal open.** Confirmed by the Pixel log of 2026-09-28 (`issue5/pixel/report.md`, "Ring on every kids open").

| state | order |
|---|---|
| `[WT]` | `VISIONOS, WEB, WEB_SAFARI, GEO, MWEB, ANDROID_VR, ANDROID_REEL, TV, IOS, WEB_EMBED` (10 requests max) |
| HEAD | the same without WEB_EMBED (9) |
| anon-challenged (15 min, same network) | `VISIONOS, ANDROID_VR, ANDROID_REEL, TV, IOS, WEB, WEB_SAFARI, GEO, MWEB, WEB_EMBED` |
| cold start with a restored persisted winner that is IOS, TV or ANDROID_REEL (`VIS:3366-3399`) | begins at that client, e.g. `TV, WEB, WEB_SAFARI, GEO, MWEB, IOS, ANDROID_VR, ANDROID_REEL, WEB_EMBED`. **VISIONOS is absent from this walk** (it is off-ring and not the begin client) `[inferred from code]` |

- The previous winner is placed second when it is web-pot.
- A WEB_EMBED winner is pushed back to last `[WT]`.
- A non-web winner does not change the effective order.
- A healthy video costs one request (VISIONOS).

**(b) Signed in.**

| state | order |
|---|---|
| both heads healthy | `TV_DOWNGRADED+auth, TV+auth, VISIONOS, ANDROID_VR, ANDROID_REEL, WEB, WEB_SAFARI, IOS, GEO, MWEB, WEB_EMBED` (no Web partition here) |
| one head quarantined (say TV_DOWNGRADED) | `TV+auth, VISIONOS, TV_DOWNGRADED+auth, ANDROID_VR, …, WEB_EMBED` |
| both quarantined (`authenticated-web-first`; per HANDOFF §26/§30 the usual state on the owner's Pixel) | `VISIONOS, WEB, WEB_SAFARI, GEO, MWEB, ANDROID_VR, ANDROID_REEL, TV+auth, IOS, TV_DOWNGRADED+auth, WEB_EMBED` |

- **Any state:** the first anonymous `LOGIN_REQUIRED` (a bot check or an age gate) inserts `TV_TIZEN+auth` as the very next attempt, unless TV_TIZEN is benched for this video or attachment (`VIS:1675-1683`). Test `VideoInfoWebEmbedLastTest.signedInAgeGateGoesToTheAccountRoute` checks `VISIONOS, TV_TIZEN+auth`.
- **After TV_TIZEN has won,** it is the remembered lastWinner and sits in the order behind the Web partition, until the next LOGIN_REQUIRED moves it forward.
- **UnplayableConsensus** can end the walk early, signed in only: at least 3 clients, one of them `srvAuth=y` and one anonymous, all giving the same allowlisted terminal reason (`VIS:903-961,1441-1454`, `BotCheckDetector.java:94-140`). A signed-out walk can never meet it.

**(c) Recovery walk after a playback failure.** The chain:

1. A media3 source error reaches `ErrorFixerController` (`common/…/ErrorFixerController.java:405-447`).
2. `anchorRouteToVideo(videoId)`.
3. If the error was HTTP 403: `markCurrentPlaybackRouteForbidden()`.
4. `applyNoPlaybackFix()`, which calls `invalidateCache` and `switchNextFormat()` (`YouTubeServiceManager.java:104-107`, `VIS:3097-3133`).
5. The video reloads.

The first start timeout with no HTTP status only mints fresh URLs from the same client (`transportOnly`). The cap is 3 consecutive auto-fixes, then a backoff of 5/15/45/120/300 s.

| suspect (the client that served the failing video) | what `switchNextFormat` does | next walk (signed out, `[WT]`) |
|---|---|---|
| VISIONOS | `PoTokenGate.resetCache()`; cursor = `getNextValue(list, VISIONOS)` = element 0 = WEB_EMBED | `WEB, WEB_SAFARI, GEO, MWEB, ANDROID_VR, ANDROID_REEL, TV, IOS, WEB_EMBED` (no VISIONOS). Test `aRecoveryWalkStillStartsAtWeb` |
| ANDROID_VR | reset web session; cursor = ANDROID_REEL | `WEB, WEB_SAFARI, GEO, MWEB, ANDROID_REEL, TV, IOS, ANDROID_VR, WEB_EMBED` |
| WEB / WEB_SAFARI / GEO / MWEB | `resetCache(client)` rebuilds the web session and returns true, so the **cursor is unchanged** and the next walk is normal with the suspect second. Within 60 s of the previous reset (throttle, `PoTokenGate.kt:282-297`) it returns false and the cursor moves on | e.g. suspect WEB, throttled: `WEB_SAFARI, GEO, MWEB, WEB, IOS, ANDROID_VR, ANDROID_REEL, TV, WEB_EMBED` |
| WEB_EMBED `[WT]` | `resetCache(WEB_EMBED)` drops the embed identity and returns false; cursor = ANDROID_VR | `WEB, WEB_SAFARI, GEO, MWEB, ANDROID_VR, ANDROID_REEL, TV, IOS, WEB_EMBED` (Pixel log: `player-ring recovery begin=ANDROID_VR suspect=WEB_EMBED first=WEB`) |
| signed in, TV_DOWNGRADED | cursor = TV_EMBED | `VISIONOS, WEB, WEB_SAFARI, GEO, MWEB, ANDROID_VR, ANDROID_REEL, TV+auth, IOS, TV_DOWNGRADED+auth, WEB_EMBED` |
| TV_TIZEN | media 403 benches it in BotWallBook (for this video; for the attachment on a second video); cursor = WEB_EMBED (element 0) | signed-in recovery as above; TV_TIZEN is not reinserted while benched |

- If the previous walk ended in transport-down, the recovery is suppressed and the last winner is not deferred (`VIS:1116-1120`).
- A Pixel build before the Codex-review fixes behaved differently. There `resetCache(WEB_EMBED)` still returned true the first time (same visitor `7eb9cf49bc` on every retry), so "WEB_EMBED #10/#9" = attempt 10 in the normal walk, 9 in recovery.

**(d) Active bot wall** (`videoinfo/V2/BotWallBook.java`; used only on mobile when no client is forced, `VIS:1152-1179`).

- **Established by:**
  - one walk with at least 3 anonymous clients giving the EXPLICIT bot text, at least 2 of them non-web; or
  - a non-web challenge on a second video within 10 min; or
  - a challenge during 30 min of probation.
- **Plan** (`plan()`, `:301-342`): [probe client if due] + [TV_TIZEN, unless benched or already spent], capped at **3 requests per video per 3 min**, counting recovery reloads.
  - **Signed out:** the probe rotates VISIONOS → ANDROID_VR → WEB, starting with the first family not challenged. Intervals are 1, 2, 4, 8, then 15 min. TV_TIZEN is asked once per wall, anonymously.
  - **Signed in with a working route:** the probe is VISIONOS only, at 5, 10, 20, then 30 min, then `TV_TIZEN+auth`.
  - **WEB_EMBED is never in a walled plan** (`[WT]` comment at `:106-111`).
- **Mid-walk shortcut:** a walk whose own evidence is strong replaces the rest of its order with the plan (`VIS:1659-1668`).
- **Ends when:** any anonymous client is served; 15 min pass without a challenge; the 60 min cap is hit; or the network changes (the wall is keyed per attachment).

**(e) Live videos.**

- A playable live answer **without** `dashManifestUrl` is held, and only ANDROID_VR is asked after it (`isLiveDashCandidate`, `VIS:1569-1571`; every other client is skipped at no cost).
- If ANDROID_VR has a DASH manifest it wins; otherwise the held HLS answer is returned.
  - Signed out: `VISIONOS (held, hls) → ANDROID_VR`.
  - Signed in, healthy: `TVD, TV → VISIONOS (held) → ANDROID_VR`.
- A live winner becomes the current client but is **not persisted** as the cold-start hint, and a restored ANDROID_VR is ignored (`VIS:3389-3393,3432-3436`).
- The loader opens the DASH URL, or HLS when there is no DASH (`VideoLoaderController.java:572-586`).
- The web live manifest gets `/pot/<streaming pot>` (WEB_EMBED `[WT]`: none).

### 2.6 Budgets and timeouts (mobile-only; TV is unbounded)

| constant | value | where |
|---|---|---|
| `RING_WALK_BUDGET_MS` | 45 000 (the whole walk; each attempt is clamped to what remains) | `VIS:120` |
| `MIN_ATTEMPT_BUDGET_MS` | 1 500 (stop if less remains, after ≥1 attempt) | `VIS:132,1251-1261` |
| `CLIENT_ATTEMPT_TIMEOUT_MS` | 7 000 (fast clients) | `VIS:75` |
| `AUTH_HEAD_ATTEMPT_TIMEOUT_MS` | 15 000 (TV_DOWNGRADED, TV, TV_TIZEN) | `VIS:89,2955-2966` |
| `WEB_POT_ATTEMPT_TIMEOUT_MS` | 20 000 (WEB, WEB_SAFARI, MWEB, GEO, INITIAL, WEB_EMBED) | `VIS:107` |
| `TRANSPORT_DOWN_STREAK` | 2 consecutive no-response attempts (timeout or IOException) end the walk with `null` | `VIS:126,1371-1383,3058-3068` |
| OkHttp open path | 8 s connect + 8 s read for /player and /next; 10 s connect for other APIs | `RetrofitOkHttpHelper.kt:24,28,161-177` |
| bot-check circuit | 15 min, one probe per 60 s | `VIS:133,141` |
| anonymous challenge | 2 hits in one walk → 15 min deprioritisation (per network attachment) | `VIS:187,193,2611-2631` |
| auth-route quarantine | 2 different videos; 10 min × 4^(strikes−1), capped at 24 h, strikes forgotten after 48 h; per transport | `VIS:153-154`, `AuthRouteQuarantineBook` |
| embed identity | 6 h TTL `[WT]` | `YtCfgService.kt:12` |
| web session reset throttle | 60 s | `PoTokenGate.kt:294` |
| negative verdict reuse | 30 s per videoId | `YouTubeMediaItemService.java:151` |

### 2.7 What counts as "usable"

- **Ring level:**
  - `playable = result != null && !result.isUnplayable()` (`VIS:1313`).
  - `isUnplayable` is true when status is UNPLAYABLE, ERROR, LOGIN_REQUIRED, AGE_CHECK_REQUIRED or CONTENT_CHECK_REQUIRED, **or** every adaptive format is "broken" (`url`, `cipher` and `signatureCipher` all null) and SABR is not accepted (`videoinfo/models/VideoInfo.java:235-266,523-539`, `formats/VideoFormat.java:312-314`).
- **Consequences** `[inferred from code]`:
  - status OK with **zero** adaptive formats is "playable" (for example HLS-only). `AGE_VERIFICATION_REQUIRED` and `LIVE_STREAM_OFFLINE` are **not** in the unplayable list.
  - A ciphered format counts as usable before deciphering.
- **`sabr=y`** in the log means `serverAbrStreamingUrl` is present. With `usableAdaptive=0` that is the "SABR-only" shape, which is unplayable unless `SabrVodCapability.accepts()`:
  - The capability must be enabled: the user's "Prefer SABR" experiment in Settings > Player > Experimental, which can be switched on in release builds (`MobileBrowseActivity.java:719-726`), or debug `sabr_fallback`/`sabr_vod`.
  - The client must be in `isSabrSupported` (VISIONOS, IOS, ANDROID*, ANDROID_VR), status OK, not live, with an ustreamer config (`SabrVodCapability.java`).
  - **With it enabled, a SABR-only answer from VISIONOS, IOS, ANDROID, ANDROID_SDK_LESS, ANDROID_REEL or ANDROID_VR counts as playable and ends the walk.** It then plays over SABR, which hits the ~60 s attestation wall for everything except VISIONOS (HANDOFF §28).
- **HLS** never makes a VOD answer playable at ring level. The loader uses HLS only for live, or for "merged" 1080p+HLS when high-bitrate is on (`VideoLoaderController.java:572-606`). A VOD answer with only HLS reaches "Empty format info". The 2026-09-28 Pixel report confirms that VOD HLS is ignored.
- **The loader's choice** (`VideoLoaderController.java:537-616`):
  1. unplayable → notice
  2. live with DASH/HLS URL → manifest
  3. adaptive formats → generated MPD (`dash-mpd`)
  4. SABR formats → `sabr-vod`
  5. live DASH
  6. live HLS
  7. URL list
  8. nothing

### 2.8 Every fallback and every extra /player

| fallback / extra call | when | client / shape | logged as |
|---|---|---|---|
| TV_TIZEN account route | signed in and an anonymous answer was LOGIN_REQUIRED; walled plans (signed in or out) | `TV_TIZEN` (+auth when signed in); real sts; ciphered | `player-ring account-route next reason=bot-check\|login-required after=<C> attempt=<n>` |
| WEB_EMBED last resort `[WT]` | every normal and recovery walk, after everything else | embed identity, no pot | `player-context … visitorSource=embed-page` |
| SABR fallback | **debug/benchmark builds only** (`debug.arc.sabr_fallback 1`); the "Prefer SABR" experiment is a release user toggle (`SabrSourcePreference.java:27-62`) | VISIONOS, IOS, ANDROID*, ANDROID_VR | `info … sabr=y`, `prepare type=sabr-vod` |
| HLS | live only (see above) | – | `prepare type=hls` |
| Deferred WEB subtitle enrichment | after a win whose answer has captions but fewer than 100 translation languages, when the process cache is cold and the winner has `isAuth()==false` (`VIS:3309-3357`) | `WEB` (mints a video-bound BotGuard token); no `player-result` line | `player-context client=WEB` + `player-http` with no `player-result` |
| Deferred IOS extended-HLS | only if `FORMATS_EXTENDED_HLS` is enabled (default is DASH\|URL, `MediaServiceData.java:306`) and the first adaptive format is 1080p with no HLS | `IOS` (`getVideoInfoIOSHls`, `VIS:3224-3235`) | `player-context client=IOS` |
| Storyboard IOS refetch | disabled on the phone (`MMA:398`) | – | – |
| History sync (signed in, anonymous winner) | `updateHistoryPosition` → `syncWithAuthFormatIfNeeded` → `getAuthVideoInfo` (`YouTubeMediaItemService.java:492-516,985-998`, `VIS:1079-1088`) | `TV` +auth, single request, no ring | `player-context client=TV` without `player-result` |
| SessionWarmup | every process launch, 1.2 s after the first feed paint (15 s fallback), unless a playback started first (`SessionWarmup.java:41-49,105`, `SessionWarmupGate.java`) | a full ring walk for `aqz-KE-bpKQ` | normal walk lines with `video=aqz-KE-bpKQ` |
| Tap prefetch / next-video prefetch | tap time, and 20 s before the end of the current video (round 3) | full walks for the other video | normal lines with the other video id |
| INITIAL | not reachable on the phone | watch-page scrape | – |

---

## 3. History per client, and the "dead / always fails" claims

### 3.1 Timeline

Commits are from `git log --author=Aleix` in MSC and the `MobileMainApplication` history in main. U = upstream-only (yuliskov, **not merged** into our fork, which is 62 commits behind `upstream/master`).

**VISIONOS**
- `da2eb515` 2026-07-28: added at the END of the enum (ordinal hazard) and made the phone's fast head. A probe answered OK with 32 adaptive formats where ANDROID_VR and TVHTML5 5.x were challenged. Won the ABBA against ANDROID_VR.
- `3bdc5a13` 2026-07-28: leads the anonymous partition in signed-in fall-through and recovery.
- `add30654` / `6eba2e87` 2026-09-08: SABR-capable. The only client with no ~60 s attestation wall on SABR.
- `4f163bca` 2026-09-25: no longer waits for BotGuard (peeks the visitor).
- `2fc6b599` 2026-09-26: the BotWallBook probe client.

**ANDROID_VR**
- `530bb2ef` / `fbd419d1` 2026-07-01: phone head (TTFF −12 %).
- `2d80b1d7` / `c685fd65` 2026-07-12: token-less clients die at ~60 s on CGNAT, so the phone flipped back to WEB_EMBED-first.
- `1e119c3b` / `73698a0f` 2026-07-13: "repaired": uses the web visitor, osVersion 12L; hybrid VR → Web routing.
- `da2eb515` 2026-07-28: replaced as head by VISIONOS.
- `f5065206` 2026-09-07: a player PO token does not prevent its deep-range 403 about 9 s in. yt-dlp dropped it on 2026-08-17 (`dae52d8`, "ALL formats 403'd with 1.65.10").
- `e326a467` 2026-09-07: the only live DASH-manifest candidate.
- `4f163bca` 2026-09-25: never restored as the cold-start hint.

**WEB_EMBED**
- Upstream ring head ("Restricted (18+) videos").
- `530bb2ef` 2026-07-01: demoted behind ANDROID_VR.
- `c685fd65` 2026-07-12: WEB_EMBED-first again on the phone ("attested flow → immortal URLs"). Live answers HLS-only, and segments 403 instantly.
- `73698a0f` 2026-07-13: first in the Web partition of the hybrid ring.
- `3bdc5a13` 2026-07-28: VISIONOS goes before it.
- `f5065206` / `7dc60232` 2026-09-07: an OAuth bearer on WEB_EMBED (and on WEB) gets HTTP 400 "invalid argument".
- `ff07d254` / `add6c517` 2026-09-26: **skipped on the phone** — "answers error 152-18 on every network, yt-dlp's too".
- U `9df453a0` 2026-09-18: Safari UA, not merged.
- `[WT]` 2026-09-28: the 152-18 was our visitor/flags mismatch. Fixed with the Safari UA, `supportXhr=false`, no token, and the client moved LAST.
- Pixel on the same day: `/player` works, but fresh URLs 403 on the first media request while the same URLs play about 1 minute later (`issue5/pixel/report.md`).

**WEB, WEB_SAFARI, GEO, MWEB**
- `bb3c1e5b` / `d8002c0e` 2026-07-13: the attested-Web-first partition.
- `c36c0289` 2026-07-27: 42/42 LOGIN_REQUIRED on Telefónica CGNAT with a valid token → anonymous-challenge deprioritisation.
- `2fc6b599` 2026-09-26 (09-25 wall): the Web family was challenged even with a fresh visitor and a token.
- WEB is used for subtitle enrichment.
- Pixel 2026-09-28, kids video: WEB, WEB_SAFARI and MWEB answered `OK usableAdaptive=0 sabr=y`; GEO answered `ERROR … Verlo en la última versión de YouTube`.

**TV (TVHTML5 7.x)**
- `d834535d` 2026-07-16: SABR-only when signed in.
- `c36c0289` 2026-07-27: URLs 403 after ~60 s, so it was replaced as the signed-in head.
- `f5065206` 2026-09-07: "reload page" when signed in. Later attributed to a missing `+001` (HANDOFF §23).
- `04d65fe2` 2026-09-07: a SABR-only answer counts as a no-media verdict.
- `add30654` 2026-09-08: its SABR POST gets 403 with 0 bytes.
- 2026-09-25: live answers `dash=n hls=n sabr=y`.

**TV_DOWNGRADED**
- `83056437` 2026-07-12: phone-skipped as a TV fallback.
- `c36c0289` 2026-07-27: the signed-in head.
- `cbcbd4e6` 2026-07-28: 15 s head budget.
- `04d65fe2` / HANDOFF §26 2026-09-07: real sts → "reload page"; `+001` → OK, but the URLs 403 on the first byte.
- U `28c3c819`: "TV_DOWNGRADED as final fallback".

**TV_TIZEN**
- `2fc6b599` 2026-09-26: added from yt-dlp PR #17723. Off-device, 2026-09-25: OK, 206 at byte 5 000 000. Pixel with the OAuth account, 2026-09-25: first frame, a seek to 60 %, 150 s with no 403. Kept off the ring because of its 223-646 ms signature solve.

**TV_LEGACY, TV_EMBED, TV_SIMPLY**
- `83056437` 2026-07-12: phone-skipped.
- TV_SIMPLY, HANDOFF §26: real sts → 206 on the first bytes, 403 on deep ranges; "must not be added yet".
- STATUS: "yt-dlp deleted tv_embedded … Jan 2026". TV_SIMPLY name id 74 vs 75 is noted as open in STATUS.

**IOS**
- 2026-07-12: token-less, dies at ~60 s on CGNAT.
- HANDOFF §27/§28 2026-09-08: "IOS, ANDROID and TVHTML5 … SABR-only today". Its SABR answers hit the ~60 s attestation wall.
- Used for the deferred extended-HLS fetch.

**ANDROID_REEL**
- In the upstream ring ("doesn't require pot and cipher"). Upstream now comments it out ("hangs on all engines").
- No VoidTube-specific evidence found. Pixel 2026-09-28: `OK usableAdaptive=0 sabr=y`.

**ANDROID, ANDROID_SDK_LESS, WEB_CREATOR, WEB_MUSIC, TV_KIDS, INITIAL**
- Never on the phone ring.
- WEB_CREATOR carries "Request contains an invalid argument." (`AC:62`, undated); yt-dlp now marks it `REQUIRE_AUTH`.
- Upstream says "TV KIDS not exists anymore".

### 3.2 Claims that a client is dead or always fails

Status key: "stands" = no contradicting evidence found; "re-test" = measured with a request shape we now know was wrong, or never re-measured.

| # | claim | source | status |
|---|---|---|---|
| 1 | WEB_EMBED "answers 152-18 to every request, on every network", yt-dlp too, even on `HtVdAasjOgU` | `VIS:206-217`, `ff07d254`, `add6c517`, HANDOFF §31 (lines 2015, 2066), STATUS:229 | **Refuted** 2026-09-28 `[WT]`: cause was the flags/visitor mismatch plus the Chrome UA plus `supportXhr=true`. Why yt-dlp also failed on 09-25/26 is unexplained (the 09-28 yt-dlp `c7fb478` run works) |
| 2 | "TVHTML5 family has no working configuration today, with or without the account" | HANDOFF §26 | Superseded for 5.x by TV_TIZEN (Tizen device fields, 2026-09-25). TV 7.x: re-test with `supportXhr` variants |
| 3 | Authenticated TVHTML5 answers "reload page" on every video | `f5065206`, HANDOFF §17 | Superseded by HANDOFF §23 (a missing sts suffix) |
| 4 | "Restoring authenticated playback needs a different credential, not a different client" | `f5065206`, `a28beba5`, `AC:304-307` | Superseded by TV_TIZEN |
| 5 | An OAuth bearer on WEB or WEB_EMBED gets HTTP 400 | `7dc60232` (Wi-Fi, 2026-09-07) | Stands for WEB (the 400 follows the credential); **never re-measured on the `[WT]` WEB_EMBED identity** |
| 6 | Token-less clients (ANDROID_VR, TV, IOS) serve exactly ~60 s, then 403, on CGNAT; client-side `pot` cannot rescue them | `2d80b1d7`, HANDOFF §8 (2026-07-12) | Partly re-explained: ANDROID_VR's cause was the visitor identity (`1e119c3b`); VISIONOS token-less plays in full. Re-test per client |
| 7 | ANDROID_VR: a player token does not prevent the deep-range 403; "do not re-litigate" | HANDOFF §17 | Stands (2026-09-07), but measured with `supportXhr=true` and the older ring |
| 8 | "IOS, ANDROID and TVHTML5 already return zero formats with URLs — SABR-only today" | HANDOFF §27 (2026-09-08) | **Re-test**: every one of those requests sent `supportXhr=true`, the exact knob that made WEB_EMBED SABR-only `[inferred]` |
| 9 | WEB_EMBED live answers are HLS-only and their segments 403 instantly | `VIS:421-427`, `MMA:336-340`, HANDOFF §8 | **Re-test** with the `[WT]` identity (it was measured with the shared visitor, a token and Chrome) |
| 10 | "No client other than ANDROID_VR has ever returned a live dashManifestUrl" | `VIS:1557-1567` | Stands; VISIONOS and IOS live not reported |
| 11 | Anonymous Web family on CGNAT: LOGIN_REQUIRED 42/42 | `c36c0289` | Network- and time-specific |
| 12 | TVHTML5_SIMPLY 403s deep ranges without a GVS token | HANDOFF §26 | Stands (one run) |
| 13 | "Made for kids videos aren't available" on VISIONOS and ANDROID_VR | `Constants.kt:85,106` (a yt-dlp comment) | Consistent with the 2026-09-28 logs (UNPLAYABLE "not available") |
| 14 | ANDROID_VR "often hangs?", TV_SIMPLY "hangs?", ANDROID_SDK_LESS "hangs on cronet!" | `VIS:46,57-58` (upstream comments) | Unmeasured folklore |
| 15 | WEB_CREATOR "Request contains an invalid argument." | `AC:62` | Undated; possibly the same bearer 400; yt-dlp now says it requires sign-in |
| 16 | GEO "Fix video clip blocked in current location" | `VIS:52` | Pixel and emulator 2026-09-28: `ERROR … watch on the latest version of YouTube` where WEB (the same client minus `params`) answered OK → the `params` value is suspect `[inferred]` |
| 17 | Visitor rotation does not rescue walls | HANDOFF §31, `VIS:2633-2667` | Stands (retired) |
| 18 | SABR fallback is "structurally capped at the first minute" | HANDOFF §28 | Stands for IOS/ANDROID/VR; VISIONOS SABR is unwalled |

---

## 4. The exact code path, and how an off-device harness can reproduce it

### 4.1 Path of one attempt

1. `VIS.firstPlayable` (`:1097`) → `getVideoInfoWithTimeout` (`:2984-3040`). On mobile this is a Future on the `VideoInfoAttempt` executor with the per-client deadline.
2. → `getVideoInfoWithRentFix` (`:3153`) → `getVideoInfo(client, videoId, ctp)` (`:3164`). INITIAL branches off to `InitialResponseService`.
3. → `VideoInfoApiHelper.getVideoInfoRequest(client, videoId, ctp)` (`VideoInfoApiHelper.java:25-51`):
   1. `PoTokenGate.getPlayerRequestPoToken` → `selectPoTokenSource(PLAYER_REQUEST)` (`PoTokenSelection.kt:47-63`) → a WebView BotGuard mint (`PoTokenProviderImpl.getWebClientPoToken`).
   2. `[WT]` If WEB_EMBED, `YtCfgService.getEmbedIdentity(videoId)`. Otherwise the visitor comes from `getPlayerVisitorData(client)`.
   3. Log `player-context`.
   4. `new QueryBuilder(client).setVideoId().setClickTrackingParams().setPoToken().setVisitorData().setEncryptedHostFlags()[WT].enableGeoFix(client==GEO).build()`.
   5. `build()` itself pulls the locale from `LocaleManager`, the cpn from `AppService.getClientPlaybackNonce()` (the player JS or `YouTubeHelper.generateCPNParameter()`; reset per `getVideoInfo`), and the sts from `AppService.getSignatureTimestamp()` (regex `signatureTimestamp:(\d+)` over the TV player JS, `app/playerdata/CommonExtractor.kt:25,56`, suffixed for TVHTML5).
4. → `getVideoInfo(client, request)` (`VIS:3186-3198`):
   1. `auth = client.isAuthCapable() && mAuthBlock`.
   2. `VideoInfoApi.getVideoInfo(body, visitorData, UA, innerTubeName, clientVersion)`, or `getVideoInfoReel`.
   3. `RetrofitHelper.get(call, auth)`; `auth=false` → `addAuthSkip`.
5. → `RetrofitOkHttpHelper` interceptors (`:179-230`): Accept-Encoding, the visitor header fallback, `prettyPrint=false`, no key, Authorization, the 8 s bounds, `player-http[S|C|E]` logging.
6. → the shared OkHttp client from SharedModules `OkHttpManager`. Non-2xx → `body()==null` → `player-result parsed=null`. Not an IOException, so it does not count toward transport-down.
7. After the win: `transformFormats` → `decipherFormats` (V8, TV player JS) and the media `pot=` (`VideoInfoServiceBase.java:89-178`).

### 4.2 Recommended harness: a Robolectric request dumper inside `youtubeapi`, then a pure replica checked against it

**Plain JVM will not work.** About 50 of the 93 `youtubeapi` test files use `RobolectricTestRunner` (`build.gradle:8,45`). That includes every test that touches `VideoInfoService`, `QueryBuilder` or `AppService`. Only pure decision tables such as `PoTokenSelectionTest` and `BotWallBookTest` run on plain JUnit. The request path calls `android.util.Log.d` (`VideoInfoApiHelper.java:41`, `VIS` everywhere), and `unitTests.returnDefaultValues` is not set. So a plain JUnit run throws "Method d in android.util.Log not mocked".

**Model the dumper on existing tests:**
- `common/helpers/QueryBuilderTimestampTest.java` has a `ShadowAppService` that supplies sts, visitor and cpn with no network.
- `VideoInfoBotWallTest.java:770-835` has `ShadowTokenGate` (it disables `PoTokenGate`'s static WebView init) and `ShadowWalk`.
- `VideoInfoVisitorScopeTest.java` seeds the persisted app info via `MediaServiceData.setAppInfo(AppInfoCached.fromString(...))`.
- `videoinfo/bench/PlayerOpenCpuBenchTest.java` is an env-gated opt-in test (`VOIDXDEV_BENCH_DIR`).

**What the dumper must do:**
1. Set the phone gates it depends on:
   - `DefaultHeaders.setBrotliEnabled(true)`
   - leave the player token off
   - JVM defaults for `Locale` and `TZ` matching the phone (`LocaleManager` falls back to `Locale.getDefault()`, `googlecommon/common/locale/LocaleManager.java:85-92`), or shadow `LocaleManager`
2. Shadow `AppService`:
   - `getSignatureTimestamp`: the harness fetches `youtube.com/tv` → playerUrl → regex, or reads an env value.
   - `getVisitorData`: a real visitor obtained by the harness.
   - `getClientPlaybackNonce`: `YouTubeHelper.generateCPNParameter()`, which is pure Java.
3. Shadow `PoTokenGate`:
   - `getPlayerRequestPoToken` returns an injected token (or null for token-less arms).
   - `getWebVisitorDataForPlayer` returns the visitor that token was minted for.
   - BotGuard needs an Android WebView. Off-device it has to come from outside: bgutils/yt-dlp PO-token providers, or a real browser through `agent-chrome`.
4. Let `YtCfgService.getEmbedIdentity` hit the network for real: Retrofit/OkHttp run under Robolectric. Or shadow it with a captured pair.
5. Call `VideoInfoApiHelper.getVideoInfoRequest(client, videoId, null)` → gives the exact `query` bytes and `visitorData`.
6. Build `RetrofitHelper.create(VideoInfoApi.class).getVideoInfo(...)` and capture the **final** request by reflection:
   - Replace `RetrofitOkHttpHelper.client` (a lazy val) with `client.newBuilder()` plus a last interceptor that records `url`, headers and body, and returns a canned 200.
   - This yields the real interceptor output with zero network.
   - Alternatively read `call.request()` and add the interceptor headers listed in §1.4.
7. Write one JSON per (client, video): `{url, method, headers, body}`.
8. Run it via `gw :youtubeapi:testDebugUnitTest --tests '*PlayerRequestDumpTest*'` **in a separate worktree/clone**, because this checkout is shared.

**Blockers:**
- J2V8 is an Android `.so`, so `PlayerDataExtractor.validate()` cannot solve on the host JVM. Shadow sts and cpn, and do the signature/n transform with the app's own solver under node (`youtubeapi/src/main/assets/nsigsolver/*.js`, as HANDOFF §26 did) or with yt-dlp's jsc.
- In JUnit, `MediaServiceData` switches `FORMATS_ALL` on (`MediaServiceData.java:92-97`). Irrelevant to request bytes, but it would trigger extended-HLS enrichment if the walk ran.
- TV_EMBED's `build()` fetches the embed page itself (`QB:348-349`).
- Authenticated arms (TV*, TV_TIZEN +auth) need the OAuth bearer. It is not available off-device, and the owner's account is hands-off. Anonymous arms only, unless the owner provides a test account.

**Then:** replicate the template in Python for scale, including the whitespace quirks, the string-typed `utcOffsetMinutes`/`androidSdkVersion` and the `iOS` clientName. Diff it byte-for-byte against the dumper's golden files. Existing off-device tooling: `tools/403-playground/gvs-matrix.sh` (yt-dlp mint plus curl range probes). Note that `probe-captured.sh` greps `load[E-url]`, a line the app no longer emits (`MobilePlaybackActivity` now logs a `load[E-request]` fingerprint only).

---

## 5. Debug hooks and NetPath lines

### 5.1 Hooks

All are `adb shell setprop` values. Most are read at process start, so force-stop after setting them; `botwall` is re-read on every answer. `BuildConfig.DEBUG` only unless noted (`MMA:253-334`).

| prop | effect |
|---|---|
| `debug.arc.player_client <ENUM>` / `none` | Forces exactly one client, with no fallback, no bot wall and no `moveWebEmbedLast`; the skip gates are bypassed. Accepts only ring members plus VISIONOS and TV_TIZEN (`VIS:331-350`), so ANDROID, ANDROID_SDK_LESS, WEB_CREATOR, WEB_MUSIC, TV_KIDS and INITIAL cannot be forced. Unknown names log `unknown debug.arc.player_client=` |
| `debug.arc.player_pot 1` | ANDROID_VR gets a video-bound Web token in its /player body |
| `debug.arc.web_auth 1` \| `<WEB-family ENUM>` | That client carries the account (WEB_EMBED then leads the web-first walk and is not moved last) |
| `debug.arc.botwall anon\|all\|<C,…>\|reset\|none` | Replaces real answers with LOGIN_REQUIRED "not a bot" after the real request is made (`DebugBotWall.java`, `VIS:1777-1842`) |
| `debug.arc.fresh_app_info 1` | fetches the app info and visitor fresh instead of using the persisted copy |
| `debug.arc.sabr_fallback 1`, `debug.arc.sabr_vod 1` (debug + benchmark) | enables `SabrVodCapability` (changes "playable") |
| `debug.arc.poison_itag`, `poison_once_itag`, `timeout_once_itag` | synthetic media 403 or timeout (`DebugMediaShaper.java:33-35`); exercises recovery |
| `debug.arc.blackhole_via\|_host\|_scope` (debug + benchmark) | dead googlevideo host |
| `debug.arc.eager_token_warmup off`, `keep_sig_runtime 0`, `early_preconnect 0` | startup A/B switches |

**Conflict for a timing benchmark.** Per HANDOFF §30, only compiled release builds give valid TTFF: a debug build runs uncompiled and the VISIONOS parse took 1.3 s. But client forcing is DEBUG-only. The 2026-09-28 Pixel check APK was a *benchmark* build, and it could not force a client. Either accept debug-build latency for per-client verdicts and use release builds for the natural ring, or extend the hook to `BuildConfig.BENCHMARK`, which is a code change.

### 5.2 NetPath lines to parse

All lines use tag `NetPath`, via `android.util.Log`, so they are present in release builds too, except `load[…]`. Real examples come from `~/projects/newtube-launch/issue5/pixel/b-kids2-lte.log` (benchmark build type, `-PsideBySide`, 2026-09-28).

There is **no line called `player-outcome`**. The per-client outcome line is `player-result`.

- **`player-context`**: one per /player build, including enrichment and history sync (`VideoInfoApiHelper.java:41-46`).
  `player-context video=wGltuo1B1sM client=WEB_EMBED cver=2.20260708.00.00 visitorSource=embed-page visitor=b7b1dc0074 visitorAgeMs=-1 playerPot=n`
- **`player-http[S]`, `[C]`, `[E]`** (`RetrofitOkHttpHelper.kt:346-386`). `client=` is the **numeric** id: WEB, WEB_SAFARI, GEO and INITIAL all log `1`, so join on order with `player-context`. `auth=` here means the Authorization header was actually sent.
  `player-http[S] rid=9 video=wGltuo1B1sM client=56 cver=2.20260708.00.00 visitor=b7b1dc0074 pot=n auth=n cookie=n authUser=n contentOk=y racyOk=y sts=y stsDigits=5 ua=7be0fa519f origin=youtube referer=none key=n net=cell:166`
  `player-http[C] rid=1 video=wGltuo1B1sM code=200 ms=290 net=cell:166 protocol=h2 tls=TLSv1.3 host=www.youtube.com redirects=0 ctype=application/json clen=2065 encoding=br`
  `player-http[E] rid=<n> code=<http> bodyHash=<sha5> body=<first 240 printable>`, or `player-http[E] rid= video= ms= <Exception>: <msg>` on an IOException.
- **ANDROID_REEL** logs `api-http[S] aid=1 method=POST endpoint=youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch auth=n visitor=… bodyBytes=1317 net=…` and `api-http[C] … code=200 ms=366 …` instead of `player-http`.
- **`player-result`**: one per ring attempt, from `logPlayerOutcome` (`VIS:2108-2141`). **`auth=` is `isAuthCapable && mAuthBlock`, not "an Authorization header was sent"**: signed-out TV logs `auth=y` while its `player-http` says `auth=n`.
  `player-result video=wGltuo1B1sM client=WEB_EMBED attempt=10 status=OK playable=y auth=n srvAuth=n formats=24+1 usableAdaptive=24 dash=n hls=y sabr=y reason="null"`
  `player-result video=wGltuo1B1sM client=WEB attempt=2 status=OK playable=n auth=n srvAuth=n formats=24+0 usableAdaptive=0 dash=n hls=n sabr=y reason="null"`   ← the SABR-only shape
  `player-result video=_WB5hh7WOb4 client=WEB_EMBED attempt=1 status=ERROR playable=n … reason="This video is unavailable • Error code: 152 - 18 <a href=…"`   ← HEAD / pre-fix
  `player-result video=<id> client=<C> attempt=<n> parsed=null`   ← HTTP error, timeout or cancel
- **Ring lines:**
  - `player-ring <CLIENT> attempt=<n> playable=y|n` (attempt > 1 only)
  - `player-ring attempt-timeout client= video= ms= clamped=y|n`
  - `player-ring budget-exhausted video= attempts= budgetMs= remainingMs= nextClient=…`
  - `player-ring transport-down video= attempts= streak= lastClient=`
  - `player-ring definitive-unplayable video= clients= reason-hash= attempts= skipped=`
  - `player-ring forced-client=<C>`
  - `player-ring recovery begin=<C> suspect=<C> first=<C>`
  - `player-ring circuit-break suspect= next=`
  - `player-ring authenticated-first=<C> [demoted=…]`
  - `player-ring authenticated-web-first reason=auth-head-quarantined failedClients=[…]`
  - `player-ring anon-deprioritized network= first=`
  - `player-ring anon-challenged network= hits= …`
  - `player-ring account-route next reason= after= attempt=`
  - `player-ring account-route failed client= reason= scope=`
  - `player-ring botwall route|established|suspect|confirmed|shortcut|cleared …`
  - `player-ring <C> live-no-dash, walking on`
  - `player-ring live-no-dash exhausted …`
  - `player-ring quarantine-auth-route client= reason= network= strike= escalation= cooldownMs= quarantined=n/2`
  - `player-ring winner-kept reason=live …`
  - `player-ring restore-skipped client=ANDROID_VR reason=live-dash-client`
  - `bot-check walk-on client= signal= attempt=`
  - `bot-check trip client= signal= authAttempted= ringExhausted= cooldownMs=`
  - `bot-check cooldown|probe|bypass=…`
- **After the win:**
  - `player-fixes video= client= ms=`
  - `player-sig video= holders= n=<distinct>/<nonnull> s=<distinct>/<nonnull> nOut=… sOut=… ms=`
  - `player-transform video= client=<winner> ms=`
  - `player-enrichment web=n reason=authenticated video=`
  - `web-pot-session new reason= visitorSource= visitor= prevAgeMs= buildMs= binding=…`
- **Milestones:**
  - `ep=<n> video=<id> tap`
  - `… open +<ms> "<title>"`
  - `… info +<ms> dash=<n> hls=y|n sabr=y|n live=y|n`
  - `… prepare +<ms> type=dash-mpd|dash-url|hls|sabr-vod|url-list`
  - `… first-frame +<ms>`
  - `… error +<ms> <Exception>… causes=…`
  - `… media-load init +<ms> track= id=<itag> h=`
  - `recovery-source http403=y|n freshUrls=y|n …`
  - debug only: `load[S|C|X|E] …` and `load[E-http] code=<n> body[..] hash= text=`. Grep with `load\[[SCXE]\]`.

**Parser rules:**
- Correlate `player-context` → `player-http[S]` → `player-http[C]` → `player-result` by video id and order. Walks are serialized by the service monitor. Enrichment and history sync run on other threads and have **no** `player-result`.
- Exclude `video=aqz-KE-bpKQ` (SessionWarmup) and prefetches of other ids.
- The winner is `player-transform client=`.
- A reopened video within a minute may produce no /player call at all (format or negative cache).
- The embed-page fetch for WEB_EMBED logs nothing. Its cost is buried in the gap before WEB_EMBED's `player-context` (~250 ms on the Pixel).

---

## 6. Open questions and suspicious spots

1. **Signed-out age-gated videos probably never reach WEB_EMBED, and may arm the bot-check circuit** `[inferred from code; no test covers it]`.
   - `isRepeatedLoginRequired` (`BotCheckDetector.java:44-56`) turns the same LOGIN_REQUIRED reason from two clients into a "challenge".
   - `hasUnchallengedClientAfter` (`VIS:1892-1906`) treats every web-pot client, WEB_EMBED included, as the challenged identity.
   - So once IOS (the last non-web client) repeats the age-gate reason, `recordChallenge` returns false. The walk trips the circuit (`ringExhausted=y`) and returns before WEB_EMBED.
   - For the next ~60 s, other videos get the stored "bot check" verdict (`VIS:2800-2819`).
   - `VideoInfoWebEmbedLastTest` scripts distinct UNPLAYABLE reasons, not a repeated LOGIN_REQUIRED.
   - `VideoInfoSkipWebEmbedTest.signedOutAgeGateEndsWithTheReason` (every client answers LOGIN_REQUIRED(AGE)) asserts only "unplayable + reason". It does not check `isBotCheckRequired()` or the circuit.
   - Cheapest confirmation: the same script with `WEB_EMBED → playable`, run under `setWebEmbedLast(true)`. Assert WEB_EMBED is called and `mBotCheckResult` stays null.
2. **`WEB_EMBED.isWebPotRequired` is still true `[WT]`,** though it now has no token and its own visitor. This still drives:
   - its 20 s budget
   - `deprioritizeWebPotClients`
   - the BotWallBook "web vs platform" count
   - `hasUnchallengedClientAfter`
   - the refusal to restore it as a winner
   - `clearAnonChallenge` on its success
   - `usesWebVisitorData`

   Only `PoTokenSelection`, `resetCache` and `isCacheActual` special-case it.
3. **`supportXhr=true`** goes to VISIONOS, IOS, ANDROID*, ANDROID_VR, WEB, WEB_SAFARI, MWEB and GEO. yt-dlp sends no `devicePlaybackCapabilities`, `lactMilliseconds` or `isInlinePlaybackNoAd`, and uses no `acceptLanguage`/`acceptRegion`. The `[WT]` comment admits MWEB behaves like WEB_EMBED did. The 2026-09-28 Pixel walk shows WEB, WEB_SAFARI, MWEB, ANDROID_REEL, TV and IOS all `OK usableAdaptive=0 sabr=y`. **This is the first benchmark arm to run: `supportXhr` = true / false / absent, per client.**
4. **WEB_EMBED sends `clientScreen=WATCH`.** yt-dlp uses the embed page's full `INNERTUBE_CONTEXT` and has an "embedable only with clientScreen=EMBED" test. We also drop the embed page's cookies (no cookie jar), which yt-dlp keeps.
5. **Wire mismatches with yt-dlp:**
   - IOS `clientName "iOS"` (yt-dlp `IOS`), with no deviceMake/osName
   - `androidSdkVersion` as a string
   - TV_SIMPLY id 74 (yt-dlp 75)
   - stale WEB_REMIX, WEB_CREATOR and TV_KIDS versions
   - MWEB on an Android Chrome UA (yt-dlp: iPad Safari, "did not require PO Token with this UA")
6. **GEO `params` is the URL-encoded string `"CgIQBg%3D%3D"` inside JSON.** GEO answered ERROR "watch on the latest version of YouTube" where plain WEB answered OK (Pixel and emulator, 2026-09-28). It is probably a wasted round trip on every hard walk.
7. **`player-result auth=` / `VideoInfo.isAuth()` means "auth-capable client", not "authenticated".** A signed-out TV or TV_TIZEN challenge is therefore not counted as anonymous: not by `noteAnonymousChallenge`, not by BotWallBook's `anonymous`, and not as the UnplayableConsensus witness. It also suppresses the WEB enrichment.
8. **VISIONOS is absent** from every recovery walk and from a cold-start walk that restored an IOS, TV or ANDROID_REEL winner.
9. **WEB_EMBED is the 10th attempt,** so it is exposed to the 45 s budget on slow links, to the bot-check trip (item 1), and to the invalidation check `result.getPlayabilityStatus().contains("152")` on a localized text (it works on the Spanish and English samples seen).
10. **Fresh WEB_EMBED URLs 403 on the first media request, and the same URLs play about 1 minute later.** In the Pixel report, 3 of the 5 cold opens won by WEB_EMBED got 403 on their first video request, all three on `_WB5hh7WOb4`; the same cached answers played 48-68 s later. The lead is yt-dlp's `playback_wait`. Separately, `KUcmvVHh_RA` via WEB_EMBED answered SABR-only on LTE but usable on Wi-Fi 8 min later. Nothing in the code waits.
11. **VOD HLS is never used** (see §2.7). Every "HLS as a resilience lane" idea (STATUS) is unbuilt.
12. **Unplayable autoplay storm:** about 90 /player calls per minute when related videos are all unplayable (Pixel report). The 30 s negative cache is per videoId and does not help.
13. **Stale comments that will mislead a reader:**
    - `MMA:202-211` (ANDROID_VR fast path) and `MMA:336-340` ("VOD keeps the WEB_EMBED-first order")
    - `VIS:60-67`, `421-427` ("WEB_EMBED (the ring head)"), `816-818` ("the phone skips WEB_EMBED"), `2911-2913` and the `sSkipWebEmbed` doc (`VIS:206-217`), still asserting 152-18 everywhere
    - `tools/403-playground/README.md` (Android VR head, `load[E-url]`)
14. **Dead gates:** `setSkipWebEmbed` and `isSkippedClient`'s WEB_EMBED branch are no longer called on the phone `[WT]`. If both skip and last were set, skip would win.
15. **Upstream divergence** (62 commits behind `upstream/master`):
    - Upstream's ring is now `VISIONOS, TV_DOWNGRADED, WEB, WEB_EMBED, WEB_SAFARI, IOS, GEO, MWEB, ANDROID_VR`.
    - Upstream has VISIONOS **mid-enum** (before INITIAL). A merge would re-point every winner we persisted by ordinal.
    - Upstream still sends `supportXhr=!isTVClient` and the cached flags with the shared visitor, so upstream's WEB_EMBED likely still gets 152-18 `[inferred]`.
    - Upstream-only items worth reading: `28c3c819` (TV_DOWNGRADED final fallback), `86c87883` ("fix restricted videos by downgrading UA"), `858ce2c9` (drops the `isAdaptiveFormatsBroken` check "since we almost fixed sabr"), `96cfe447` / `4f350041` / `90ae880a` (client values), `da8102d4` (TV UA).
16. **Not verified: debug-build full HTTP logging.** SharedModules `OkHttpCommons.debugSetup` adds `HttpLoggingInterceptor` at `BODY` level when `sharedutils` `BuildConfig.DEBUG` is true (`OkHttpCommons.java:320-340`). If that is true in debug variants, debug logcat carries full /player headers and bodies, including `Authorization`. That is useful for capture and a leak risk in a debug diagnostic-log export.
17. **Outside scope, noticed:** `OkHttpCommons.configureToIgnoreCertificate` installs a trust-all TLS manager on API ≤ 24, and minSdk is 24, so Android 7.0 devices are covered.
