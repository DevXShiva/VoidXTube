#!/usr/bin/env python3
"""Generate variants/ytdlp.yaml from the local yt-dlp checkout's INNERTUBE_CLIENTS.

    python3 tools/gen_ytdlp_variants.py [--ytdlp PATH] [-o variants/ytdlp.yaml]
    (PATH defaults to $NETBENCH_YTDLP, else ~/projects/yt-dlp)

Re-run after pulling yt-dlp; the header records the commit it was generated from.

What a ytdlp-* variant reproduces (anonymous, default extractor args, c7fb478 semantics):
  * webpage: yt-dlp first downloads the watch page (web client, random Chrome UA, SOCS=CAI and
    PREF=hl=en&tz=UTC cookies) - its VISITOR_DATA becomes X-Goog-Visitor-Id for every client.
  * per-client config page (_download_ytcfg): web/web_safari -> www.youtube.com, mweb ->
    m.youtube.com, web_music -> music.youtube.com, web_embedded -> /embed/<id>?html5=1 (Referer =
    thirdParty.embedUrl), tv -> /tv. When fetched, THAT page's INNERTUBE_CONTEXT is the request
    context (hl/timeZone/utcOffsetMinutes forced to en/UTC/0) and its version/name/UA feed the headers;
    for web_embedded its encryptedHostFlags go into contentPlaybackContext.
  * one cookie jar for the whole "process" (session ytdlp): later pages and /player carry the
    cookies earlier pages set.
  * body: context, videoId, playbackContext.contentPlaybackContext{html5Preference, signatureTimestamp,
    [encryptedHostFlags]}, contentCheckOk, racyCheckOk. No devicePlaybackCapabilities.
  * transport: yt-dlp here runs on urllib (HTTP/1.1, Python OpenSSL TLS) - curl_cffi cannot mimic
    that; variants use HTTP/1.1 and curl's plain (non-browser) TLS.
Not reproduced: PO-token providers (none installed / anonymous), the web client's use of the watch
page's ytInitialPlayerResponse instead of a /player call (see ytdlp-web~initial_pr), per-video page
refetches (scope=run reuses one identity per trial, as a persistent cookie jar would).
"""

import argparse
import datetime
import os
import pathlib
import subprocess
import sys

import yaml

HERE = pathlib.Path(__file__).resolve().parent.parent
CONFIG_PAGES = {'web': 'home', 'web_safari': 'home', 'mweb': 'mweb', 'web_music': 'music',
                'web_embedded': 'embed', 'tv': 'tv'}
YTDLP_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
            'Chrome/148.0.0.0 Safari/537.36')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ytdlp', default=os.environ.get('NETBENCH_YTDLP', str(pathlib.Path.home() / 'projects' / 'yt-dlp')))
    ap.add_argument('-o', '--output', default=str(HERE / 'variants' / 'ytdlp.yaml'))
    args = ap.parse_args()
    os.environ['YTDLP_NO_PLUGINS'] = '1'
    sys.path.insert(0, args.ytdlp)
    from yt_dlp.extractor.youtube._base import INNERTUBE_CLIENTS, StreamingProtocol, _split_innertube_client
    commit = subprocess.run(['git', '-C', args.ytdlp, 'log', '-1', '--format=%h %cI %s'],
                            capture_output=True, text=True).stdout.strip()

    def policy(ytcfg):
        gvs = ytcfg['GVS_PO_TOKEN_POLICY']
        parts = []
        for proto in StreamingProtocol:
            p = gvs[proto]
            if p.required:
                flag = 'req'
                if p.not_required_with_player_token:
                    flag += '-unless-player-pot'
                if p.not_required_for_premium:
                    flag += '-unless-premium'
                parts.append(f'{proto.value}:{flag}')
        pp = ytcfg['PLAYER_PO_TOKEN_POLICY']
        if pp.required or pp.recommended:
            parts.append('player-pot:' + ('req' if pp.required else 'rec'))
        return ','.join(parts) or 'none'

    variants = []
    for name, ytcfg in INNERTUBE_CLIENTS.items():
        if name.startswith('_'):
            continue
        _, base, variant_suffix = _split_innertube_client(name)
        client = dict(ytcfg['INNERTUBE_CONTEXT']['client'])
        ctx_extra = {k: v for k, v in ytcfg['INNERTUBE_CONTEXT'].items() if k != 'client'}
        cfg_page = CONFIG_PAGES.get(name)
        if ytcfg['REQUIRE_AUTH']:
            cfg_page = None  # _download_ytcfg returns {} when auth is required and we are anonymous
        pages = ['watch'] + ([cfg_page] if cfg_page and cfg_page != 'watch' else [])
        pages_config = {'watch': {'ua': YTDLP_UA}}
        if cfg_page:
            pages_config[cfg_page] = {'ua': client.get('userAgent') or YTDLP_UA}
            if cfg_page == 'embed':
                pages_config['embed']['referer'] = (ctx_extra.get('thirdParty') or {}).get('embedUrl')
            else:
                pages_config[cfg_page]['referer'] = None
        host = ytcfg['INNERTUBE_HOST']
        v = {
            'id': f'ytdlp-{name}',
            'description': (f"yt-dlp INNERTUBE_CLIENTS['{name}'] (priority {ytcfg['priority']}, "
                            f"REQUIRE_JS_PLAYER={ytcfg['REQUIRE_JS_PLAYER']}, SUPPORTS_COOKIES={ytcfg['SUPPORTS_COOKIES']}, "
                            f"REQUIRE_AUTH={ytcfg['REQUIRE_AUTH']}, GVS/player PO-token policy: {policy(ytcfg)})"),
            'tags': {
                'client': name,
                'identity': 'ytdlp-watch' + (f'+{cfg_page}-context' if cfg_page else ''),
                'capability': 'supportXhr=absent',
                'context': 'embed' if variant_suffix == 'embedded' else ('music' if 'music' in name else 'watch'),
                'transport': 'plain-tls-h1',
                'pot_policy': policy(ytcfg),
                'require_js': ytcfg['REQUIRE_JS_PLAYER'],
                'require_auth': ytcfg['REQUIRE_AUTH'],
                'cookies': 'pages+player: yt-dlp jar (SOCS,PREF + page-set)',
            },
            'client_name_id': ytcfg['INNERTUBE_CONTEXT_CLIENT_NAME'],
            'client': client,
            'client_overrides': {'hl': 'en', 'timeZone': 'UTC', 'utcOffsetMinutes': 0},
            'user_agent': client.get('userAgent') or YTDLP_UA,
            'endpoint': {'host': host, 'path': '/youtubei/v1/player', 'query': {'prettyPrint': 'false'}},
            'identity': {
                'session': 'ytdlp', 'scope': 'run', 'pages': pages, 'visitor': 'watch',
                'flags': 'embed' if variant_suffix == 'embedded' and cfg_page == 'embed' else None,
                'context': cfg_page, 'cookies': True, 'page_cookie_jar': True,
                'visitor_in_body': False, 'visitor_header': True, 'user_agent_from_context': True,
                'page_cookies': {'SOCS': 'CAI', 'PREF': 'hl=en&tz=UTC'},
                'pages_config': pages_config,
            },
            'sts': 'player',
            'headers': {
                'Origin': f'https://{host}',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'en-us,en;q=0.5',
                'Sec-Fetch-Mode': 'navigate',
                'Content-Type': 'application/json',
            },
            'body': {
                **({'context': ctx_extra} if ctx_extra else {}),
                'playbackContext': {'contentPlaybackContext': {'html5Preference': 'HTML5_PREF_WANTS'}},
                **({'params': ytcfg['PLAYER_PARAMS']} if ytcfg.get('PLAYER_PARAMS') else {}),
                'contentCheckOk': True,
                'racyCheckOk': True,
            },
            'transport': {'impersonate': None, 'http_version': 'v1'},
            'media': {
                'headers': {'User-Agent': '${user_agent}',
                            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                            'Accept-Language': 'en-us,en;q=0.5', 'Sec-Fetch-Mode': 'navigate'},
                'range_mode': 'query', 'impersonate': None, 'http_version': 'v1',
            },
        }
        variants.append(v)
    # The web client's real yt-dlp path: the watch page's ytInitialPlayerResponse (no /player call)
    variants.append({
        'id': 'ytdlp-web~initial_pr', 'extends': 'ytdlp-web',
        'description': "yt-dlp's actual default path for 'web': formats from the watch page's ytInitialPlayerResponse",
        'tags': {'context': 'watch-page', 'identity': 'watch-page-itself'},
        'endpoint': {'kind': 'watch_page'},
        'identity': {'pages': [], 'visitor': None, 'context': None, 'cookies': True},
        'headers': {'Origin': None, 'Content-Type': None},
    })
    header = (
        f'# GENERATED by tools/gen_ytdlp_variants.py - do not edit by hand; re-run the generator.\n'
        f'# source: yt-dlp @ {commit}\n'
        f'# generated: {datetime.datetime.now().astimezone().isoformat(timespec="seconds")}\n'
        f'# {len(variants)} variants (one per INNERTUBE_CLIENTS entry + ytdlp-web~initial_pr).\n'
        f'# yt-dlp randomises its Chrome UA (145-151); pinned here to Chrome 148 for reproducibility.\n'
    )
    text = header + yaml.safe_dump({'variants': variants}, sort_keys=False, width=140, allow_unicode=True)
    pathlib.Path(args.output).write_text(text)
    print(f'wrote {args.output}: {len(variants)} variants from yt-dlp {commit[:7]}')


if __name__ == '__main__':
    main()
