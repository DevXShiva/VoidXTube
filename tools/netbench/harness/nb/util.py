"""Small helpers: JSON merge patch, placeholder expansion, redaction, fingerprints."""

from __future__ import annotations

import copy
import hashlib
import json
import random
import re
import string
import time
import urllib.parse

_PLACEHOLDER = re.compile(r'\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}')
_DELETE = object()


def expand_dotted(obj):
    """{'a.b': 1} -> {'a': {'b': 1}} (recursively). Lets YAML patch deep paths in one line."""
    if not isinstance(obj, dict):
        return obj
    out = {}
    for key, value in obj.items():
        value = expand_dotted(value)
        if isinstance(key, str) and '.' in key and not key.startswith('.'):
            head, *rest = key.split('.')
            node = out.setdefault(head, {})
            for part in rest[:-1]:
                node = node.setdefault(part, {})
            last = rest[-1]
            if isinstance(node.get(last), dict) and isinstance(value, dict):
                node[last] = merge_patch(node[last], value, keep_nulls=True)
            else:
                node[last] = value
        else:
            if isinstance(out.get(key), dict) and isinstance(value, dict):
                out[key] = merge_patch(out[key], value, keep_nulls=True)
            else:
                out[key] = value
    return out


def merge_patch(target, patch, keep_nulls=False):
    """RFC 7386 JSON merge patch: dicts merge recursively, null deletes, anything else replaces.

    keep_nulls=True is used when combining two PATCHES (inheritance): a null in the child must
    survive so it can still delete the key from the final document.
    """
    if not isinstance(patch, dict):
        return copy.deepcopy(patch)
    result = dict(target) if isinstance(target, dict) else {}
    for key, value in patch.items():
        if value is None:
            if keep_nulls:
                result[key] = None
            else:
                result.pop(key, None)
        elif isinstance(value, dict):
            result[key] = merge_patch(result.get(key), value, keep_nulls=keep_nulls)
        else:
            result[key] = copy.deepcopy(value)
    return result


def strip_nulls(obj):
    if isinstance(obj, dict):
        return {k: strip_nulls(v) for k, v in obj.items() if v is not None}
    if isinstance(obj, list):
        return [strip_nulls(v) for v in obj]
    return obj


def expand_placeholders(obj, variables: dict):
    """Replace ${name} in every string. A string that is exactly one placeholder takes the
    variable's type (so "${sts}" becomes an int); if that variable is None the key is dropped.
    Unknown placeholders raise, so a typo in a variant file fails loudly in --dry-run."""
    def sub(value):
        if isinstance(value, dict):
            out = {}
            for k, v in value.items():
                nv = sub(v)
                if nv is _DELETE:
                    continue
                out[k] = nv
            return out
        if isinstance(value, list):
            return [x for x in (sub(v) for v in value) if x is not _DELETE]
        if isinstance(value, str):
            whole = _PLACEHOLDER.fullmatch(value)
            if whole:
                name = whole.group(1)
                if name not in variables:
                    raise KeyError(f'unknown placeholder ${{{name}}}')
                v = variables[name]
                return _DELETE if v is None else v

            def repl(m):
                name = m.group(1)
                if name not in variables:
                    raise KeyError(f'unknown placeholder ${{{name}}}')
                v = variables[name]
                return '' if v is None else str(v)
            return _PLACEHOLDER.sub(repl, value)
        return value

    result = sub(obj)
    return {} if result is _DELETE else result


def set_path(obj: dict, path: str, value):
    node = obj
    parts = path.split('.')
    for part in parts[:-1]:
        nxt = node.get(part)
        if not isinstance(nxt, dict):
            nxt = {}
            node[part] = nxt
        node = nxt
    node[parts[-1]] = value


def get_path(obj, path: str, default=None):
    node = obj
    for part in path.split('.'):
        if isinstance(node, dict) and part in node:
            node = node[part]
        elif isinstance(node, list) and part.isdigit() and int(part) < len(node):
            node = node[int(part)]
        else:
            return default
    return node


def fingerprint(value) -> str | None:
    """Short stable id for an identifier we must not store raw (visitorData, flags)."""
    if not value:
        return None
    return hashlib.sha256(str(value).encode()).hexdigest()[:10]


_IP_QUERY = re.compile(r'([?&])(ip|ipbits)=[^&]*')
_IP_PATH = re.compile(r'/(ip|ipbits)/[^/]+')


def redact_url(url: str) -> str:
    """googlevideo URLs embed the caller's public IP (ip=...). Never let one reach a results file."""
    if not url:
        return url
    return _IP_PATH.sub(r'/\1/REDACTED', _IP_QUERY.sub(r'\1\2=REDACTED', url))


def redact_obj(obj):
    if isinstance(obj, dict):
        return {k: redact_obj(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [redact_obj(v) for v in obj]
    if isinstance(obj, str) and ('googlevideo.com' in obj or 'ip=' in obj or '/ip/' in obj):
        return redact_url(obj)
    return obj


def url_summary(url: str) -> dict:
    """What a results line may say about a media URL: host and a few non-identifying params."""
    if not url:
        return {}
    parsed = urllib.parse.urlparse(url)
    qs = urllib.parse.parse_qs(parsed.query)
    out = {'host': parsed.hostname}
    for key in ('itag', 'c', 'cver', 'expire', 'mime', 'sabr', 'source', 'svpuc', 'xpc', 'rqh', 'gir', 'n', 'pot'):
        if key in qs:
            v = qs[key][0]
            out[key] = (v if key not in ('n', 'pot') else f'len{len(v)}')
    for key in ('pot', 'spc', 'sig', 'lsig', 'sparams'):
        out[f'has_{key}'] = key in qs
    return out


def rand_str(n: int, alphabet: str = string.ascii_letters + string.digits + '-_') -> str:
    return ''.join(random.choice(alphabet) for _ in range(n))


def now_iso() -> str:
    return time.strftime('%Y-%m-%dT%H:%M:%S%z')


def jdump(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':'), default=str)


def median(values):
    vals = sorted(v for v in values if v is not None)
    if not vals:
        return None
    mid = len(vals) // 2
    return vals[mid] if len(vals) % 2 else (vals[mid - 1] + vals[mid]) / 2
