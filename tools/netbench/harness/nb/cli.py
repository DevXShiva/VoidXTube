"""Command line: `netbench run ...`, `netbench report ...`, `netbench list ...`."""

from __future__ import annotations

import argparse
import sys

from . import HARNESS_DIR, RESULTS_DIR, harness_version


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog='netbench', description='InnerTube /player request-type benchmark (signed-out).')
    ap.add_argument('--version', action='version', version=f'netbench {harness_version()}')
    sub = ap.add_subparsers(dest='cmd', required=True)

    r = sub.add_parser('run', help='run the matrix')
    r.add_argument('--corpus', action='append',
                   help='corpus JSON, repeatable, merged by id (default: ../corpus.json + corpus/pixel-2026-09-28.json; '
                        'corpus/seed.json if ../corpus.json is missing)')
    r.add_argument('--variants', action='append', default=None,
                   help='variant files / globs / dirs (repeatable, comma-separated); default variants/')
    r.add_argument('--variant-ids', action='append', help='only these variant ids (globs, comma-separated)')
    r.add_argument('--exclude', action='append', help='skip these variant ids (globs, comma-separated)')
    r.add_argument('--only-tags', action='append',
                   help='tag filter: "client=web_embedded" or bare value; commas OR, repeated flags AND')
    r.add_argument('--axis', action='append',
                   help='expand every selected variant along a declared axis: NAME=v1,v2 (or NAME for all values; '
                        '"base" keeps the unmodified variant); repeatable = cartesian product')
    r.add_argument('--network', required=True, help='label for the network path: wifi, lte, ...')
    r.add_argument('--proxy', help='e.g. socks5h://127.0.0.1:18080 (remote DNS); applies to EVERY request')
    r.add_argument('--videos', action='append', help='only these video ids (comma-separated; unknown ids = adhoc)')
    r.add_argument('--categories', action='append', help='only these corpus categories/subcategories (comma-separated)')
    r.add_argument('--include-controls', action='store_true',
                   help='also run expected_signed_out=fail videos (skipped by default unless named in --videos)')
    r.add_argument('--trials', type=int, default=1, help='repeat every cell N times, fresh identity per trial')
    r.add_argument('--max-requests', type=int, default=400, help='hard cap on youtube.com/youtubei requests (400)')
    r.add_argument('--max-media-requests', type=int, default=1500, help='hard cap on googlevideo requests (1500)')
    r.add_argument('--delay-s', type=float, default=3.0, help='min gap between YouTube requests (>= 3)')
    r.add_argument('--media-delay-s', type=float, default=1.0, help='min gap between googlevideo requests (>= 1)')
    r.add_argument('--probe-schedule', default='0,2,4,6,10,20,40',
                   help='re-probe slots in s after the /player answer (stop at first success)')
    r.add_argument('--targets', action='append',
                   help='only probe these deliveries: adaptive,progressive,hls,dash (default: all the answer offers; '
                        'live HLS playlists / MPDs cost ~0.3-1.4 MB each)')
    r.add_argument('--target-order',
                   help='probe order within a slot, e.g. "hls,adaptive,progressive,dash", or "rotate" (shift by trial); '
                        'default adaptive video, audio, progressive, HLS, DASH')
    r.add_argument('--max-wait-s', type=float, default=90.0,
                   help='append one slot after a pre-roll ad wait beyond the schedule, up to this')
    r.add_argument('--player-id', help='pin the player JS id (skips iframe_api); cached under .cache/player')
    r.add_argument('--sts', type=int, help='override signatureTimestamp')
    r.add_argument('--run-id', help='default: <timestamp>-<network>')
    r.add_argument('--out-dir', help=f'default: {RESULTS_DIR} ($NETBENCH_DATA/harness/results when set)')
    r.add_argument('--save-responses', action='store_true', help='also store redacted /player JSON per attempt')
    r.add_argument('--save-request-bodies', action='store_true', help='include the (redacted) request body per attempt')
    r.add_argument('--dry-run', action='store_true', help='print the requests that would be sent; no network')
    r.add_argument('--show-all', action='store_true', help='with --dry-run: every video, not just the first')
    r.add_argument('-v', '--verbose', action='store_true', help='log every HTTP request')

    su = sub.add_parser('sustain', help='replay chosen cells as a real player for --duration-s')
    su.add_argument('--from', dest='source', action='append', required=True,
                    help='source run file(s) / globs; cells and their resolved variants come from these')
    su.add_argument('--only', action='append', help='verdict codes to take (default PLAY; comma-separated)')
    su.add_argument('--variant-ids', action='append', help='only these variant ids (globs, comma-separated)')
    su.add_argument('--videos', action='append', help='only these video ids (comma-separated)')
    su.add_argument('--categories', action='append', help='only these categories/subcategories')
    su.add_argument('--network', required=True)
    su.add_argument('--proxy', help='socks5h://127.0.0.1:18080 for the phone; applies to every request')
    su.add_argument('--delivery', default='auto', choices=['auto', 'adaptive', 'hls', 'progressive'],
                    help='auto = what the source cell was served by: adaptive, else HLS, else progressive '
                         '(HLS first for live)')
    su.add_argument('--duration-s', type=float, default=150.0, help='playback seconds per cell (150)')
    su.add_argument('--chunk-s', type=float, default=5.0, help='media seconds per adaptive range request (5)')
    su.add_argument('--prebuffer-s', type=float, default=10.0, help='media kept ahead of the playback clock (10)')
    su.add_argument('--seek-at-s', type=float, default=90.0, help='playback second of the seek (90; <0 = no seek)')
    su.add_argument('--seek-frac', type=float, default=0.7, help='seek target as a fraction of the file (0.7)')
    su.add_argument('--startup-schedule', default='0,2,4,6,10,20,40',
                    help='first-chunk retry slots, s after the /player answer + its ad wait')
    su.add_argument('--fresh-identity', action='store_true', help='fetch the identity pages again for every cell')
    su.add_argument('--max-cells', type=int, default=20)
    su.add_argument('--max-requests', type=int, default=100, help='cap on youtube.com/youtubei requests (100)')
    su.add_argument('--max-media-requests', type=int, default=3000, help='cap on googlevideo requests (3000)')
    su.add_argument('--delay-s', type=float, default=3.0, help='min gap between YouTube requests (>= 3)')
    su.add_argument('--media-delay-s', type=float, default=1.0, help='min gap between googlevideo requests (>= 1)')
    su.add_argument('--player-id', help='default: the source run\'s player')
    su.add_argument('--run-id')
    su.add_argument('--out-dir', help=f'default: {RESULTS_DIR} ($NETBENCH_DATA/harness/results when set)')
    su.add_argument('--dry-run', action='store_true', help='list the chosen cells; no network')
    su.add_argument('-v', '--verbose', action='store_true')

    rep = sub.add_parser('report', help='render markdown from results/*.jsonl')
    rep.add_argument('results', nargs='+')
    rep.add_argument('-o', '--output')

    ls = sub.add_parser('list', help='list variants (after inheritance) with tags')
    ls.add_argument('--variants', action='append', default=None)
    ls.add_argument('--only-tags', action='append')
    ls.add_argument('--variant-ids', action='append')
    ls.add_argument('--axis', action='append')

    args = ap.parse_args(argv)
    if args.cmd == 'run':
        from . import runner
        args.variants = args.variants or [str(HARNESS_DIR / 'variants')]
        if args.trials < 1:
            ap.error('--trials must be >= 1')
        return runner.run(args)
    if args.cmd == 'sustain':
        from . import sustain
        if args.seek_at_s is not None and args.seek_at_s < 0:
            args.seek_at_s = None
        return sustain.run(args)
    if args.cmd == 'report':
        from . import report
        return report.main(args)
    if args.cmd == 'list':
        from .variants import LOADED_AXES, apply_axes, load_variants, runnable, select
        variants, files = load_variants(args.variants or [str(HARNESS_DIR / 'variants')])
        sel = apply_axes(select(runnable(variants), args.variant_ids, None), args.axis)
        sel = select(sel, None, args.only_tags)
        for f in files:
            print(f'# {f["path"]} ({f["variants"]} entries, axes {f["axes"]}, sha {f["sha256"]})')
        for name, ax in LOADED_AXES.items():
            print(f'# axis {name}: {", ".join(ax["values"])}')
        for v in sel:
            tags = ' '.join(f'{k}={val}' for k, val in (v.get('tags') or {}).items())
            print(f'{v["id"]:<34} {tags}')
        print(f'{len(sel)} runnable variant(s)', file=sys.stderr)
        return 0
    return 2
