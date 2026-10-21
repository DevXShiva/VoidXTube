"""Declarative request variants.

A variant file (YAML or JSON) is either a list of variants or a mapping with optional
`defaults:` (merged under every variant of THAT file) and `variants:`. Each variant may
`extends:` another variant id from any loaded file; inheritance is JSON-merge-patch (a null in
the child deletes the parent's key). Ids starting with "_" are abstract bases (never run).

Field reference (see README.md for worked examples):

  id               unique id; convention: nt-* (NewTube engine), ytdlp-* (yt-dlp), x-* (experiments)
  description      free text
  tags             {client, identity, capability, context, transport, ...} - free-form taxonomy
                   used for --only-tags and to group report rows
  client           the context.client object (clientName, clientVersion, userAgent, ...)
  client_overrides merge-patch applied to context.client AFTER a page context replaced `client`
  client_name_id   numeric id for the X-YouTube-Client-Name header (null = header omitted)
  endpoint         {kind: innertube|watch_page, host, path, query, response_path, scheme (tests only)}
  identity         {session, scope, pages, visitor, flags, context, cookies, visitor_in_body,
                    visitor_header, user_agent_from_context, page_ua, page_cookies, page_referer}
  sts              player | player+001 | none | <int>
  po_token         none (the only value the host can honour; recorded)
  body             merge-patch onto the request body; ${placeholders} expanded afterwards
  headers          merge-patch onto the request headers (null deletes a default header)
  transport        {impersonate, http_version, browser_headers, ja3, akamai}
  media            {headers, range_mode: query|header, impersonate, http_version, targets}
"""

from __future__ import annotations

import copy
import fnmatch
import glob
import hashlib
import json
import pathlib

import yaml

from .util import expand_dotted, merge_patch

DEFAULT_HEADERS = {
    'Content-Type': 'application/json',
    'User-Agent': '${user_agent}',
    'X-YouTube-Client-Name': '${client_name_id}',
    'X-YouTube-Client-Version': '${client_version}',
    'Origin': 'https://www.youtube.com',
    'X-Goog-Visitor-Id': '${visitor_header}',
    'Accept-Encoding': 'gzip, deflate, br',
}

DEFAULT_VARIANT = {
    'description': '',
    'tags': {},
    'client': {},
    'client_overrides': {},
    'client_name_id': None,
    'endpoint': {
        'kind': 'innertube',
        'host': 'www.youtube.com',
        'path': '/youtubei/v1/player',
        'query': {'prettyPrint': 'false'},
        'response_path': None,
    },
    'identity': {
        'session': None,          # variants sharing a session share pages + cookie jar (per trial)
        'scope': 'run',           # run | video | attempt : how long a fetched identity is reused
        'pages': [],              # page kinds fetched in order: embed, watch, home, mweb, tv, music, sw_js_data
        'visitor': None,          # page kind whose VISITOR_DATA is the visitor (or null)
        'flags': None,            # page kind whose encryptedHostFlags are sent (or null)
        'context': None,          # page kind whose INNERTUBE_CONTEXT replaces `client` (yt-dlp style)
        'cookies': False,         # send the cookies those pages set (in-memory jar) on /player
        'visitor_in_body': True,  # context.client.visitorData = visitor
        'visitor_header': True,   # X-Goog-Visitor-Id = visitor
        'user_agent_from_context': False,  # User-Agent header = final context.client.userAgent
        'page_ua': None,          # UA for page fetches (default: the variant's user agent)
        'page_cookies': {},       # cookies sent on page fetches (e.g. SOCS consent)
        'page_referer': {},       # per page kind Referer override
        'page_cookie_jar': False, # later pages send the cookies earlier pages set (yt-dlp's jar)
        'pages_config': {},       # per page kind: {ua, cookies, referer, headers} overrides
    },
    'sts': 'player',
    'po_token': 'none',
    'body': {},
    'headers': {},
    'transport': {'impersonate': None, 'http_version': None, 'browser_headers': False, 'ja3': None, 'akamai': None},
    'media': {
        'headers': {'User-Agent': '${user_agent}'},
        'range_mode': 'query',
        'impersonate': 'chrome',
        'http_version': None,
        'targets': ['adaptive', 'progressive', 'hls', 'dash'],
    },
}

PAGE_KINDS = ('embed', 'watch', 'home', 'mweb', 'tv', 'music', 'sw_js_data')
STS_MODES = ('player', 'player+001', 'none')


class VariantError(ValueError):
    pass


def _load_file(path: pathlib.Path):
    text = path.read_text()
    data = json.loads(text) if path.suffix == '.json' else yaml.safe_load(text)
    if data is None:
        return {}, [], {}
    if isinstance(data, list):
        return {}, data, {}
    if not isinstance(data, dict):
        raise VariantError(f'{path}: expected a list or a mapping with `variants:` / `axes:`')
    return data.get('defaults') or {}, data.get('variants') or [], data.get('axes') or {}


SEED_FIRST = ('newtube.yaml', 'ytdlp.yaml')


def _dir_files(d: pathlib.Path):
    files = list(d.glob('*.y*ml')) + list(d.glob('*.json'))
    return sorted(files, key=lambda p: (SEED_FIRST.index(p.name) if p.name in SEED_FIRST else 9, p.name))


def expand_paths(specs: list[str]) -> list[pathlib.Path]:
    out = []
    for spec in specs:
        for part in spec.split(','):
            part = part.strip()
            if not part:
                continue
            matches = sorted(glob.glob(part, recursive=True))
            p = pathlib.Path(part)
            if not matches and p.is_dir():
                matches = [str(x) for x in _dir_files(p)]
            if not matches:
                raise VariantError(f'no variant file matches {part!r}')
            for m in matches:
                mp = pathlib.Path(m)
                if mp.is_dir():
                    out.extend(_dir_files(mp))
                else:
                    out.append(mp)
    seen, uniq = set(), []
    for p in out:
        rp = p.resolve()
        if rp not in seen:
            seen.add(rp)
            uniq.append(p)
    return uniq


LOADED_AXES: dict = {}


def load_variants(specs: list[str]) -> tuple[dict, list[dict]]:
    """Returns ({id: resolved variant}, [file info]) with every variant fully resolved.
    Axes found in the files are collected into LOADED_AXES (see apply_axes)."""
    raw = {}
    files = []
    LOADED_AXES.clear()
    for path in expand_paths(specs):
        defaults, items, axes = _load_file(path)
        files.append({'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()[:12],
                      'variants': len(items), 'axes': sorted(axes)})
        for name, values in axes.items():
            if name in LOADED_AXES:
                raise VariantError(f'axis {name!r} defined twice ({path})')
            if not isinstance(values, dict) or not values:
                raise VariantError(f'{path}: axis {name!r} must map value names to patches')
            LOADED_AXES[name] = {'_file': str(path), 'values': {str(k): expand_dotted(v or {}) for k, v in values.items()}}
        defaults = expand_dotted(defaults)
        for item in items:
            if not isinstance(item, dict) or 'id' not in item:
                raise VariantError(f'{path}: every variant needs an `id`')
            vid = item['id']
            if vid in raw:
                raise VariantError(f'duplicate variant id {vid!r} ({path} and {raw[vid]["_file"]})')
            raw[vid] = {'_file': str(path), '_defaults': defaults, '_raw': expand_dotted(item)}

    resolved = {}

    def resolve(vid, stack=()):
        if vid in resolved:
            return resolved[vid]
        if vid not in raw:
            raise VariantError(f'unknown variant {vid!r} in extends chain {" -> ".join(stack)}')
        if vid in stack:
            raise VariantError(f'extends cycle: {" -> ".join(stack + (vid,))}')
        entry = raw[vid]
        item = entry['_raw']
        parent = item.get('extends')
        if parent:
            base = resolve(parent, stack + (vid,))
            base = {k: v for k, v in base.items() if not k.startswith('_') and k not in ('id', 'extends')}
            merged = merge_patch(base, item, keep_nulls=True)
        else:
            merged = merge_patch(merge_patch(DEFAULT_VARIANT, entry['_defaults'], keep_nulls=True), item, keep_nulls=True)
        merged['_file'] = entry['_file']
        merged['_extends_chain'] = _chain(vid, raw)
        resolved[vid] = merged
        return merged

    for vid in raw:
        v = resolve(vid)
        validate(v)
    # file order (not inheritance-resolution order), so runs and reports follow the files
    return {vid: resolved[vid] for vid in raw}, files


def _chain(vid, raw):
    chain, cur = [], raw[vid]['_raw'].get('extends')
    while cur and cur in raw and cur not in chain:
        chain.append(cur)
        cur = raw[cur]['_raw'].get('extends')
    return chain


def validate(v: dict):
    vid = v['id']
    ident = v.get('identity') or {}
    for key in ('visitor', 'flags', 'context'):
        kind = ident.get(key)
        if kind and kind not in PAGE_KINDS:
            raise VariantError(f'{vid}: identity.{key}={kind!r} is not a page kind {PAGE_KINDS}')
        if kind and kind not in (ident.get('pages') or []):
            raise VariantError(f'{vid}: identity.{key}={kind!r} but {kind!r} is not in identity.pages')
    for kind in ident.get('pages') or []:
        if kind not in PAGE_KINDS:
            raise VariantError(f'{vid}: unknown page kind {kind!r} (known: {PAGE_KINDS})')
    if ident.get('scope', 'run') not in ('run', 'video', 'attempt'):
        raise VariantError(f'{vid}: identity.scope must be run|video|attempt')
    sts = v.get('sts')
    if sts is not None and not isinstance(sts, int) and sts not in STS_MODES:
        raise VariantError(f'{vid}: sts must be one of {STS_MODES} or an integer')
    if v.get('po_token', 'none') not in ('none', None):
        raise VariantError(f'{vid}: po_token {v.get("po_token")!r} - the host cannot mint PO tokens; only `none`')
    ep = v.get('endpoint') or {}
    if ep.get('kind', 'innertube') not in ('innertube', 'watch_page'):
        raise VariantError(f'{vid}: endpoint.kind must be innertube|watch_page')
    if ep.get('kind', 'innertube') == 'innertube' and not (v.get('client') or ident.get('context')):
        if not vid.startswith('_'):
            raise VariantError(f'{vid}: needs `client` (or identity.context)')
    media = v.get('media') or {}
    if media.get('range_mode', 'query') not in ('query', 'header'):
        raise VariantError(f'{vid}: media.range_mode must be query|header')


def runnable(variants: dict) -> list[dict]:
    return [v for vid, v in variants.items() if not vid.startswith('_') and not v.get('abstract')]


def _tag_match(v: dict, cond: str) -> bool:
    tags = v.get('tags') or {}
    if '=' in cond:
        key, val = cond.split('=', 1)
        tv = tags.get(key)
        vals = tv if isinstance(tv, list) else [tv]
        return any(fnmatch.fnmatch(str(x), val) for x in vals if x is not None)
    for tv in tags.values():
        vals = tv if isinstance(tv, list) else [tv]
        if any(fnmatch.fnmatch(str(x), cond) for x in vals if x is not None):
            return True
    return False


def select(variants: list[dict], only_ids: list[str] | None, only_tags: list[str] | None,
           exclude_ids: list[str] | None = None) -> list[dict]:
    """--variant-ids a,b* (OR); --only-tags: each flag is AND-ed, commas inside one flag are OR-ed."""
    out = []
    for v in variants:
        if only_ids and not any(fnmatch.fnmatch(v['id'], pat) for spec in only_ids for pat in spec.split(',') if pat):
            continue
        if exclude_ids and any(fnmatch.fnmatch(v['id'], pat) for spec in exclude_ids for pat in spec.split(',') if pat):
            continue
        if only_tags:
            ok = all(any(_tag_match(v, c) for c in group.split(',') if c) for group in only_tags)
            if not ok:
                continue
        out.append(v)
    return out


def public_view(v: dict) -> dict:
    """The resolved variant as recorded in results (no private keys)."""
    return {k: copy.deepcopy(val) for k, val in v.items() if not k.startswith('_')}


def apply_axes(variants: list[dict], axis_specs: list[str] | None) -> list[dict]:
    """--axis NAME=v1,v2 (repeatable): replace every selected variant by one derived variant per
    value (cartesian product across axes). A value's patch is merged onto the variant like an
    `extends` child; its `tags` are merged too, and tags.axes records the choice. The value
    `base` means "unchanged". Derived ids: <id>~<axis>=<value>."""
    if not axis_specs:
        return variants
    plan = []
    for spec in axis_specs:
        name, _, vals = spec.partition('=')
        name = name.strip()
        if name not in LOADED_AXES:
            raise VariantError(f'unknown axis {name!r}; loaded axes: {sorted(LOADED_AXES)}')
        known = LOADED_AXES[name]['values']
        values = [v.strip() for v in vals.split(',') if v.strip()] if vals else list(known)
        for v in values:
            if v != 'base' and v not in known:
                raise VariantError(f'axis {name!r} has no value {v!r}; values: {sorted(known)}')
        plan.append((name, values))
    out = variants
    for name, values in plan:
        nxt = []
        for base in out:
            for value in values:
                if value == 'base':
                    nxt.append(base)
                    continue
                patch = LOADED_AXES[name]['values'][value]
                derived = merge_patch({k: v for k, v in base.items() if k not in ('_file',)}, patch, keep_nulls=True)
                derived['id'] = f'{base["id"]}~{name}={value}'
                tags = dict(base.get('tags') or {})
                tags.update(patch.get('tags') or {})
                axes_tag = dict((base.get('tags') or {}).get('axes') or {})
                axes_tag[name] = value
                tags['axes'] = axes_tag
                derived['tags'] = tags
                derived['_file'] = base.get('_file')
                derived['_extends_chain'] = [base['id']] + list(base.get('_extends_chain') or [])
                validate(derived)
                nxt.append(derived)
        out = nxt
    return out
