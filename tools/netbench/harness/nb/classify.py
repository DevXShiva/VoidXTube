"""Turn one attempt's facts into per-delivery results and a single cell verdict.

Delivery results: PLAY (bytes received; with the time of the first success), SABR (formats
exist but none has a url/signatureCipher), NONE (the answer offers no such delivery), 403,
NSIG/SIG (our n/signature solve failed - a harness problem, not YouTube's), HTTPxxx, NETERR.

Cell verdicts: PLAY, SABR, 403, UNPLAYABLE, LOGIN, BOTWALL, ERROR, FAIL, or another
playabilityStatus.status verbatim (LIVE_STREAM_OFFLINE, AGE_CHECK_REQUIRED, ...).
"""

from __future__ import annotations

LETTER = {'adaptive': 'A', 'progressive': 'P', 'hls': 'H', 'dash': 'D'}


def _target(targets: dict, name: str) -> dict | None:
    return targets.get(name)


def deliveries(analysis: dict, targets: dict, probed: set | None = None) -> dict:
    """probed = the delivery types this run chose to probe (--targets / variant media.targets);
    an offered delivery outside it is SKIPPED, not a failure."""
    out = _deliveries(analysis, targets)
    if probed is not None:
        for k, d in out.items():
            if k not in probed and d.get('result') not in ('NONE', 'SABR'):
                out[k] = {'result': 'SKIPPED'}
    return out


def _deliveries(analysis: dict, targets: dict) -> dict:
    f = analysis['formats']
    out = {}

    # adaptive: needs video AND audio (when the answer has audio formats with URLs)
    v, a = _target(targets, 'adaptive_video'), _target(targets, 'adaptive_audio')
    if f['adaptive_total'] == 0:
        out['adaptive'] = {'result': 'NONE'}
    elif f['adaptive_url'] + f['adaptive_cipher'] == 0:
        out['adaptive'] = {'result': 'SABR'}
    elif not v and not a:
        out['adaptive'] = {'result': 'NOURL'}
    else:
        parts = [t for t in (v, a) if t]
        results = [t['result'] for t in parts]
        if all(r == 'PLAY' for r in results):
            los = [t['availability']['lo_s'] for t in parts if t['availability']['lo_s'] is not None]
            out['adaptive'] = {'result': 'PLAY',
                               'first_ok_after_s': max(t['first_ok_after_s'] for t in parts),
                               'first_ok_slot_s': max(t['first_ok_slot_s'] for t in parts),
                               'availability': {'lo_s': max(los) if los else None,
                                                'hi_s': max(t['first_ok_after_s'] for t in parts),
                                                'censoring': 'interval' if los else 'left'}}
        else:
            bad = next(r for r in results if r != 'PLAY')
            partial = any(r == 'PLAY' for r in results)
            out['adaptive'] = {'result': bad, 'partial': partial,
                               'availability': {'lo_s': max([t['availability']['lo_s'] or 0 for t in parts]),
                                                'hi_s': None, 'censoring': 'right'},
                               'detail': {t_name: t['result'] for t_name, t in (('video', v), ('audio', a)) if t}}

    p = _target(targets, 'progressive')
    if f['progressive_total'] == 0:
        out['progressive'] = {'result': 'NONE'}
    elif f['progressive_url'] + f['progressive_cipher'] == 0:
        out['progressive'] = {'result': 'SABR'}
    else:
        out['progressive'] = _single(p)

    for name, flag in (('hls', 'hls'), ('dash', 'dash')):
        if not f[flag]:
            out[name] = {'result': 'NONE'}
        else:
            out[name] = _single(_target(targets, name))
    return out


def _single(t):
    if not t:
        return {'result': 'NOURL'}
    d = {'result': t['result'], 'availability': t.get('availability')}
    if t['result'] == 'PLAY':
        d['first_ok_after_s'] = t['first_ok_after_s']
        d['first_ok_slot_s'] = t['first_ok_slot_s']
    return d


def verdict(*, http_status, net_error, parse_error, analysis, dels) -> dict:
    if net_error:
        return {'code': 'ERROR', 'reason_code': 'net', 'detail': net_error[:120]}
    if http_status != 200:
        return {'code': 'ERROR', 'reason_code': f'http-{http_status}'}
    if parse_error or analysis is None:
        return {'code': 'ERROR', 'reason_code': 'parse', 'detail': parse_error}
    ps = analysis['playability']
    status = ps['status']
    rc = ps['reason_code']
    if ps['bot_wall']:
        return {'code': 'BOTWALL', 'reason_code': rc, 'status': status}
    if status == 'OK':
        played = {k: v for k, v in dels.items() if v['result'] == 'PLAY'}
        if played:
            first = min(v['first_ok_after_s'] for v in played.values())
            slot = min(v['first_ok_slot_s'] for v in played.values())
            return {'code': 'PLAY', 'first_ok_after_s': first, 'first_ok_slot_s': slot,
                    'played': sorted(played, key=lambda k: 'aphd'.index(k[0]))}
        results = {k: v['result'] for k, v in dels.items()}
        offered = {k: r for k, r in results.items() if r not in ('NONE', 'SKIPPED')}
        if not offered:
            return {'code': 'FAIL', 'reason_code': 'no-formats'}
        if all(r in ('SABR', 'NOURL') for r in offered.values()):
            return {'code': 'SABR', 'reason_code': None}
        if any(r == '403' for r in offered.values()):
            return {'code': '403', 'reason_code': None}
        bad = next((r for r in offered.values() if r not in ('SABR', 'NOURL')), 'FAIL')
        return {'code': 'FAIL', 'reason_code': bad}
    if status == 'LOGIN_REQUIRED':
        return {'code': 'LOGIN', 'reason_code': rc}
    if status in ('UNPLAYABLE', 'ERROR'):
        return {'code': status, 'reason_code': rc}
    return {'code': status or 'NOSTATUS', 'reason_code': rc}


def cell_label(v: dict, dels: dict | None) -> str:
    """Short text for one attempt, e.g. 'PLAY H@3s A@8s P@9s', 'PLAY A@0s P@1s (H:403)',
    'PLAY H@2s (A:SABR)', '403 A,P', 'SABR', 'ERROR 152-18'. Times = seconds from the /player answer
    to the first successful media request of that delivery."""
    code = v.get('code')
    dels = dels or {}
    if code == 'PLAY':
        played = sorted(((k, d) for k, d in dels.items() if d.get('result') == 'PLAY'),
                        key=lambda kd: (kd[1].get('first_ok_after_s') or 0, 'aphd'.index(kd[0][0])))
        parts = [f'{LETTER[k]}@{d.get("first_ok_after_s", 0):.0f}s' for k, d in played]
        failed = [f'{LETTER[k]}:{d["result"]}' for k, d in dels.items()
                  if d.get('result') not in ('PLAY', 'NONE', 'SKIPPED', None)]
        return 'PLAY ' + ' '.join(parts) + (f' ({" ".join(failed)})' if failed else '')
    if code == '403':
        which = [LETTER[k] for k, d in dels.items() if d.get('result') == '403']
        return '403 ' + ','.join(which)
    if code == 'SABR':
        return 'SABR'
    if code == 'BOTWALL':
        return 'BOTWALL'
    rc = v.get('reason_code')
    return f'{code} {rc}' if rc else code
