# netbench harness

Host-side benchmark of **which InnerTube `/player` request shapes yield media that googlevideo
actually serves**, per video category and per network, for VoidTube. Every request shape is a
declarative *variant* (YAML/JSON), so new ideas are added as data, not code.

**PLAY means "media bytes were served"**: HTTP 200/206 with a body for every resource a delivery
needs (adaptive: video + audio; HLS: the variant's segment + its audio-group rendition + EXT-X-MAP
init when present; DASH: video + audio). It does not mean decoded or played for N seconds. Decode-proof
is the phone stage.

```
harness/
  netbench                  entry point (python3)
  nb/                       the harness (net, identity, playerjs, innertube, media, classify, runner, report)
  variants/newtube.yaml     nt-*    VoidTube's current request per AppClient (+ supportXhr/IOS/GEO siblings)
  variants/ytdlp.yaml       ytdlp-* GENERATED from the yt-dlp checkout (tools/gen_ytdlp_variants.py)
  variants/examples.yaml    x-*     README examples and single-change experiments
  variants/astra.yaml       x-*     compatibility arms from ../novel/astra.md (cookie-free control)
  variants/axes.yaml        axes: named patches applied to any variant with --axis
  corpus/seed.json          3-video seed corpus (used only if ../corpus.json is missing)
  corpus/pixel-2026-09-28.json  extra Pixel fixtures (KUcmvVHh_RA, wGltuo1B1sM), merged by default
  results/<run_id>.jsonl    one header line, one line per attempt, one footer line
  .cache/                   player JS by id, yt-dlp cache (EJS solver lib); never ~/.cache/yt-dlp
```

## Safety (enforced in code, not by convention)

- **Signed out only.** No OAuth, no account token, nothing from a browser. Cookies are never read
  from disk. The only cookies ever sent are ones YouTube set on this run's own anonymous page
  fetches (in memory), and only where a variant says so. Each attempt records the cookie names
  sent per phase (`cookie_policy.pages / player / media`); variants carry a `cookies` tag
  (`nt-*`: `tv-page:SOCS; player:none` - the app sends its SOCS consent cookie on the /tv fetch).
- **yt-dlp as a library only**: `YoutubeDL` is constructed with explicit params (library use never
  reads a config file), no `cookiefile`/`cookiesfrombrowser` (asserted), plugins disabled
  (`YTDLP_NO_PLUGINS`), cache dir `harness/.cache/yt-dlp`. It never downloads anything itself: the
  player JS and the EJS solver lib go through the harness's network layer (proxy, pacing, byte
  accounting); the lib's sha3-512 is checked against the checkout's pinned hash.
- **Pacing and caps**: >= 3 s between youtube.com / youtubei requests (`--delay-s`, floor 3),
  >= 1 s between googlevideo requests (`--media-delay-s`, floor 1), hard caps `--max-requests 400`
  (YouTube hosts) and `--max-media-requests 1500` (googlevideo). Hitting a cap stops the run.
- **Bot-wall stop**: "confirm you're not a bot" / "Sign in to confirm" in any /player playability text,
  a LOGIN_REQUIRED with a bot reason, HTTP 429 from any YouTube or googlevideo host, or an identity page
  that is a real interstitial (redirect to google.com/sorry, a watch page whose own playabilityStatus says
  so, or the bot text on a page without ytcfg) -> the attempt is written, the footer is written, the run
  exits with code 3. (Raw page text is not evidence: the /tv and watch pages ship that sentence among
  their UI strings.)
- **Pre-roll wait: measured, never avoided.** No variant or axis may exist to remove or shorten
  YouTube's enforced pre-roll wait (`inline=1`, `adPlaybackContext`, ad-context fields). `nt-*` keep
  `isInlinePlaybackNoAd: true` only because that is what the app sends today.
- **Privacy of results**: googlevideo URLs contain the caller's public IP (`ip=`); results never store
  a media URL, only host + itag + a few non-identifying params. visitorData / encryptedHostFlags are
  stored as 10-hex fingerprints. `--save-responses` stores /player JSON with `ip=` redacted.

## Requirements

Python 3.12 with `curl_cffi` 0.15 and `PyYAML`; a yt-dlp checkout (`NETBENCH_YTDLP`, default
`~/projects/yt-dlp`); deno (`~/.deno/bin/deno`, override `NETBENCH_DENO`). Nothing to install.
Results go to `harness/results/`, or `$NETBENCH_DATA/harness/results/` when `NETBENCH_DATA` is set
(`--out-dir` overrides both).

## Running

```bash
cd tools/netbench/harness

./netbench list                                   # every variant after inheritance, with tags and axes
./netbench run --network wifi --dry-run --variant-ids nt-WEB_EMBED --videos _WB5hh7WOb4
                                                  # the exact request(s), no network at all

# Wi-Fi from this PC
./netbench run --network wifi --categories made_for_kids --variant-ids 'nt-*' --trials 2
./netbench report results/*.jsonl -o results/report.md

# LTE through the phone (the SOCKS5 proxy on the phone is built separately; start it first)
adb -s "$NETBENCH_SERIAL" forward tcp:18080 tcp:18080
./netbench run --network lte --proxy socks5h://127.0.0.1:18080 --categories made_for_kids
```

`--proxy` applies to **every** request (identity pages, /player, player JS, the solver lib, media).
Use `socks5h://` (DNS resolved through the phone); `socks5://` is refused. The proxy is TCP only, so
HTTP/3 is impossible through it; the variants use h2/h1.1 anyway.

Useful selectors: `--videos id,id` (ids not in the corpus run as category `adhoc`),
`--categories made_for_kids,age_restricted` (category or subcategory), `--include-controls`
(expected_signed_out=fail videos are skipped by default), `--variant-ids 'nt-*,ytdlp-web_embedded'`,
`--exclude 'nt-TV*'`, `--only-tags client=web_embedded` (commas OR, repeated flags AND),
`--axis identity=none,tv,watch,embed` (see Axes), `--targets hls,adaptive` (skip deliveries; live HLS
playlists and MPDs cost 0.3-1.4 MB each), `--trials 3` (fresh identity per trial),
`--probe-schedule 0,2,4,6,10,20,40`, `--target-order hls,adaptive,progressive,dash` or `rotate` (which
delivery goes first within a slot; `rotate` shifts it per trial), `--player-id 7460dd14` (pin the player;
cached JS is reused),
`--save-responses`, `--save-request-bodies`, `-v` (log every HTTP request).

Exit codes: 0 done, 2 usage/config error, 3 stopped (bot wall, 429, Ctrl-C), 4 request cap reached,
1 harness bug.

## What one attempt does

1. **Identity** (per variant: `identity.pages`): fetch the pages it needs (`embed`, `watch`, `home`,
   `mweb`, `tv`, `music`, `sw_js_data`), cached per identity *session* and trial (`scope: run`), per
   video (`scope: video`) or not at all (`scope: attempt`). Take the visitor (`VISITOR_DATA`), the
   `encryptedHostFlags` and optionally the page's whole `INNERTUBE_CONTEXT` from the pages the variant
   names.
2. **/player**: build the body (see below), POST it with the variant's headers and TLS fingerprint
   (curl_cffi `impersonate`), parse the answer: playabilityStatus (status, reason, subreason, error
   screen, reason code such as `152-18`), format counts (adaptive with `url` / `signatureCipher` /
   neither = SABR-only, progressive, HLS/DASH/SABR URLs present, DRM), PO-token hints, and the
   **pre-roll ad data** (every `instreamVideoAdRenderer` under adPlacements/adSlots with its JSON path,
   kind, duration, skip offset and ad ids; `ad_wait_s` = yt-dlp's own `available_at` estimate;
   `preroll_full_s`, `preroll_dedup_s`).
3. **Decipher**: signature and `n` for the chosen formats and manifest paths, solved by the yt-dlp
   checkout's EJS solver under deno.
4. **Media probes on a time axis** (t=0 = the /player answer): every delivery the answer offers is
   probed independently - adaptive video (best <=720p) + audio, progressive (itag 18), HLS, DASH - each
   on its own schedule (default 0,2,4,6,10,20,40 s, stop at the first success, retry the SAME URL; a
   manifest chain resumes at the failed step). Each probe records status, bytes, TTFB, Content-Range,
   Content-Length, a payload hash, and the actual send time. Availability is interval-censored:
   `(last refused send, first served send]`.
5. **Verdict**: PLAY / SABR / 403 / UNPLAYABLE / LOGIN / BOTWALL / ERROR / FAIL, plus per-delivery
   results and upload/download bytes for the attempt (HTTP-level; a TLS-handshake estimate per new
   connection is reported separately).

## Variant format

A variant file is `{defaults: {...}, variants: [...], axes: {...}}` (all optional) or a bare list.
`defaults` merge under every variant of that file that has no `extends`.

| field | meaning |
|---|---|
| `id` | unique; prefixes: `nt-` VoidTube, `ytdlp-` generated from yt-dlp, `x-` experiments. `_`-prefixed ids are abstract bases |
| `extends` | another variant id (any file); inheritance is a JSON merge patch - a `null` deletes the parent's key |
| `description`, `tags` | free text; tags drive `--only-tags` and report grouping. Taxonomy: `client`, `identity`, `capability`, `context`, `transport` (+ `cookies`, `ring`, anything) |
| `client` | the `context.client` object (clientName, clientVersion, userAgent, deviceMake/Model, osName/osVersion, androidSdkVersion, clientScreen, hl/gl or acceptLanguage/acceptRegion ...) |
| `client_overrides` | merge patch on `context.client` applied last (also over a page context) |
| `client_name_id` | numeric id for `X-YouTube-Client-Name` (`null` = header omitted) |
| `user_agent` | header UA when it should differ from `client.userAgent` |
| `endpoint` | `{kind: innertube\|watch_page, host, path, query, response_path}` - e.g. Reel: `youtubei.googleapis.com`, `/youtubei/v1/reel/reel_item_watch`, `response_path: playerResponse`; `watch_page` = the watch page's ytInitialPlayerResponse |
| `identity` | `pages` (fetched in order), `visitor` / `flags` / `context` (which page supplies each), `session`, `scope`, `cookies` (send page-set cookies on /player), `page_cookie_jar` (later pages send earlier pages' cookies), `visitor_in_body`, `visitor_header`, `user_agent_from_context`, `page_ua`, `page_cookies`, `pages_config: {kind: {ua, cookies, referer, headers}}` |
| `sts` | `player` (from the run's player JS), `player+001` (TVHTML5 form), `none`, or an integer |
| `po_token` | `none` - the host cannot mint PO tokens; hints that one is needed are recorded |
| `body` | merge patch onto the body `{context, videoId, [playbackContext.contentPlaybackContext.signatureTimestamp / encryptedHostFlags]}`; any path, e.g. `playbackContext.devicePlaybackCapabilities.supportXhr: false`, `racyCheckOk`, `params`, `context.thirdParty.embedUrl`, `serviceIntegrityDimensions` ... Dotted keys are expanded |
| `headers` | merge patch on `Content-Type, User-Agent, X-YouTube-Client-Name, X-YouTube-Client-Version, Origin, X-Goog-Visitor-Id, Accept-Encoding` (`null` removes one) |
| `transport` | `impersonate` (curl_cffi target: `chrome`, `safari184`, `firefox147`, `chrome131_android`, ... or `null` for plain curl TLS), `http_version` (`v1`/`v2`), `browser_headers`, `ja3`, `akamai` |
| `media` | googlevideo probes: `headers`, `range_mode` (`query` = `&range=`, yt-dlp; `header` = `Range:`, VoidTube), `impersonate`, `http_version`, `targets` |

Placeholders, expanded after merging (a string that is exactly one placeholder keeps the value's
type; a `null` value deletes the key): `${video_id}`, `${sts}`, `${visitor_data}`,
`${visitor_header}`, `${flags}`, `${cpn}` (16 random chars), `${t}` (12), `${user_agent}`,
`${client_name}`, `${client_version}`, `${client_name_id}`, `${embed_url}`. Unknown placeholders fail
`--dry-run`.

JSON key order may differ from the app's string template (VoidTube concatenates text); the server parses
JSON objects, and `--dry-run` shows exactly what is sent.

### Worked example 1: WEB_EMBED with the embed identity

What made WEB_EMBEDDED_PLAYER work again (issue5/findings.md): the embed page's
`encryptedHostFlags` **plus that same page's visitor**, sts, a Safari UA, `supportXhr=false`.
`nt-WEB_EMBED` is exactly this; `x-WEB_EMBED-identity` (variants/examples.yaml) spells it out:

```yaml
variants:
  - id: x-WEB_EMBED-identity
    extends: nt-WEB_EMBED                # client: WEB_EMBEDDED_PLAYER 2.20260708.00.00, id 56, Safari UA
    tags: {context: embed, identity: embed-page+flags}
    identity:
      session: x-embed                   # its own identity: one fresh embed page per trial
      pages: [embed]                     # GET https://www.youtube.com/embed/<id>?html5=1, Referer reddit
      visitor: embed                     # -> context.client.visitorData + X-Goog-Visitor-Id
      flags: embed                       # -> playbackContext.contentPlaybackContext.encryptedHostFlags
    sts: player
    body:
      context.thirdParty.embedUrl: https://www.reddit.com/
      playbackContext.devicePlaybackCapabilities.supportXhr: false
```

Swap one piece to test it: `identity: {pages: [tv, embed], visitor: tv, flags: embed}` is the
pre-fix mismatch (`nt-WEB_EMBED_HEAD`, expect `152-18`); `sts: none` drops the timestamp.

### Worked example 2 (hypothetical): MWEB with supportXhr=false

```yaml
variants:
  - id: x-MWEB-xhr-false
    extends: nt-MWEB
    description: VoidTube's MWEB request with devicePlaybackCapabilities.supportXhr=false
    tags: {capability: supportXhr=false}
    body:
      playbackContext.devicePlaybackCapabilities.supportXhr: false
```

Drop the file in `variants/` (or pass `--variants my.yaml`), check it with
`./netbench run --network wifi --dry-run --variant-ids x-MWEB-xhr-false`, then run it next to its
parent: `--variant-ids 'nt-MWEB,x-MWEB-xhr-false'`. To remove the whole block instead:
`body: {playbackContext.devicePlaybackCapabilities: null}`.

### Axes

`variants/axes.yaml` declares named patches that apply to ANY selected variant:

```bash
./netbench run --network wifi --variant-ids 'nt-WEB,nt-IOS,nt-VISIONOS' --axis identity=none,tv,watch,embed
./netbench run --network wifi --variant-ids 'nt-MWEB,nt-WEB_SAFARI' --axis xhr=true,false,absent
```

Derived ids read `nt-WEB~identity=watch`; `--axis` flags multiply; the value `base` keeps the
unmodified variant. Axes today: `identity` (none, tv, watch, embed, embed+flags, sw, home), `xhr`
(true, false, absent), `sts` (player, plus001, none), `screen` (WATCH, EMBED), `cookies` (send, drop),
`tls` (chrome, safari, firefox, android-chrome, plain-h1). A new axis is a YAML block, no code.

### Refreshing the yt-dlp set

```bash
python3 tools/gen_ytdlp_variants.py          # re-reads INNERTUBE_CLIENTS from $NETBENCH_YTDLP
```

`ytdlp-*` reproduce yt-dlp's anonymous flow: watch page first (its visitor is the header visitor),
the per-client config page (`home`, `mweb`, `music`, `embed`, `tv`) whose INNERTUBE_CONTEXT becomes the
request context, one cookie jar (SOCS=CAI, PREF), no devicePlaybackCapabilities, HTTP/1.1 and plain
TLS (yt-dlp runs on urllib here), `&range=` media requests. yt-dlp's real `web` path is the watch page's
ytInitialPlayerResponse: `ytdlp-web~initial_pr`.

## Sustain: does a PLAY cell keep serving?

A 4 KiB probe proves the first bytes, not playback: clients whose policy wants a GVS PO token are
reported to 403 some 30-60 s in, and SABR without attestation dies at ~60 s. `sustain` replays chosen
cells of earlier runs as a player would:

```bash
./netbench sustain --from results/sweep1-wifi.jsonl --only PLAY \
    --variant-ids 'nt-TV_SIMPLY,nt-WEB_MUSIC' --videos _WB5hh7WOb4 --network wifi --duration-s 150
./netbench sustain --from results/sweep1-lte.jsonl --only PLAY --categories made_for_kids \
    --network lte --proxy socks5h://127.0.0.1:18080
```

Per cell: a fresh /player with the cell's exact variant (the resolved definition stored in the source
run's header, same identity recipe; one identity per sustain run unless `--fresh-identity`), then:
- **startup** - wait out this answer's own pre-roll wait (`ad_wait_s`), then request each stream's first
  chunk, retrying the same URL on `--startup-schedule` until served. Playback t=0 = all first chunks served.
- **steady state** - keep `--prebuffer-s` (10) of media ahead of the playback clock. Adaptive video +
  audio are read in successive byte ranges of `--chunk-s` (5) seconds of media each, sized from the
  format's averageBitrate and clamped to contentLength; HLS reads whole segments of the chosen variant and
  its audio-group rendition (init map first), reloading the playlist when live.
- **seek** - at `--seek-at-s` (90) the buffer is dropped and every VOD stream jumps to `--seek-frac` (0.7).
- **end** - `--duration-s` (150) or end of file.
- `--delivery auto` takes what the source cell was served by (adaptive, else HLS, else progressive; HLS
  first for live); force one with `--delivery adaptive|hls|progressive`. Live/OTF adaptive is
  UNSUPPORTED (no byte ranges) - use HLS for live.

A refused chunk is retried once after 2 s; if the retry is refused too the cell ends. Verdicts:
`SUSTAIN-OK A 150s` / `SUSTAIN-OK A EOF@131s` (+ `n blip(s)` if a retry rescued a chunk),
`FAIL@42s (HTTP403) A:adaptive_video` (playback second of the FIRST refusal, stream, code),
`STARTUP-FAIL (HTTP403)`, `NO-MEDIA (SABR|UNPLAYABLE ...)` (the fresh answer did not offer the
delivery), `UNSUPPORTED`, `HARNESS_ERROR`. Every chunk is recorded (stream, playback and wall time,
range, status, bytes, TTFB, Content-Range, retry, after-seek). Same pacing floors, caps
(`--max-requests 100`, `--max-media-requests 3000`, `--max-cells 20`), bot-wall/429 stop and proxy.
`netbench report` renders sustain lines as their own table per network.

## Offline tests

```bash
cd harness && python3 -m unittest discover -s tests -v      # ~50 s, no network beyond 127.0.0.1
```

`tests/fake_yt.py` is a local stand-in for googlevideo and /player (byte ranges with scripted refusals:
from an offset, the first N requests, once at an offset; VOD and live HLS with init maps and audio
groups; any status such as 429). `tests/test_sustain.py` covers sustain end to end: pacing, seek,
EOF, mid-play refusal with retry, transient blip, startup retry and STARTUP-FAIL, the pre-roll wait,
SABR-only -> NO-MEDIA, 429 stop, HLS VOD and live, cell selection, the CLI run from a source file with
the real PlayerJS loaded from the disk cache, and the report's sustain and bot-wall sections. A guard
fails any test that tries a host other than 127.0.0.1.

## Results and report

`results/<run_id>.jsonl`:
- **header**: run id, network, proxy, harness version (release + code hash), curl_cffi, Python, deno,
  yt-dlp commit/date/dirty/version + EJS version, player id / URL / variant / sha / sts, corpus files
  and hashes, variant files and hashes, every resolved variant, schedule, pacing, caps, argv.
- **attempt**: ts, run_id, network, trial, video id / category / subcategory / made_for_kids, variant
  id + tags, identity (pages fetched now, visitor/flags fingerprints, cookies), cookie_policy, request
  summary (host, path, client, UA, sts, body sha), player (HTTP status, latency, TTFB, bytes up/down),
  playability, video details, formats, ads, po_token hints, solve, media (per target: every probe with
  slot, actual send time, status, bytes, TTFB, Content-Range, payload hash; availability interval),
  deliveries, verdict (+ label), bytes (per host class, TLS estimate).
- **footer**: why the run ended, totals per host class.

`./netbench report results/*.jsonl [-o file.md]` renders per network: the variant x category matrix
(rows grouped by `tags.client`), a variant x video detail matrix, the timing table (each delivery's
availability interval next to `ad_wait_s`), "who serves this" per category, latency/bytes per
variant, reason codes seen, and traffic. **BOTWALL** is its own cell class, and a "Bot walls" note per
run names the variant and video that hit it, whether in /player or an identity page, and after how many
YouTube requests and minutes. Sustain runs render as their own tables (see Sustain).

## Known gaps

- **PO tokens**: none. WEB/MWEB/WEB_SAFARI/GEO media 403s here are expected (the app mints BotGuard
  tokens); the attempt lists `po_token.hints`.
- **TLS**: curl_cffi 0.15 has no OkHttp/Android target; `nt-*` use `chrome` for /player (the app uses
  OkHttp over Conscrypt) and `chrome` for media (Cronet, close). `transport.ja3/akamai` can carry a
  fingerprint captured from the phone.
- **Visitor**: the app's persistent /tv visitor (up to 10 h old, plus its stored VISITOR_INFO1_LIVE
  cookie) is approximated by a fresh /tv page per trial.
- **SABR** is not streamed; SABR-only answers are classified, not played.
- **Locale**: `nt-*` send `en`/`US`/`"120"`.
- **Probe order**: within a slot the default order is adaptive video, audio, progressive, HLS, DASH,
  1 s apart; a delivery's time is its own actual send time, not the slot, but a delivery probed late is
  left-censored (`<=t`). Use `--target-order` / `rotate` to measure a specific delivery at t~0.
- Decode-proof, SABR, recovery lineage and state-machine replays (astra.md) are phone-stage or
  offline work, not in this harness.
