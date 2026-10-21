"""netbench: which InnerTube /player request shapes actually yield playable media.

Host-side harness for NewTube (see README.md). Signed-out only, no cookies from disk, no
yt-dlp config: every request it sends is built from the declarative variant files.
"""

import hashlib
import os
import pathlib

BASE_VERSION = '0.2.0'
HARNESS_DIR = pathlib.Path(__file__).resolve().parent.parent
# Default results dir: <NETBENCH_DATA>/harness/results, else harness/results (--out-dir overrides).
RESULTS_DIR = (pathlib.Path(os.environ['NETBENCH_DATA']) / 'harness' / 'results'
               if os.environ.get('NETBENCH_DATA') else HARNESS_DIR / 'results')


def harness_version() -> str:
    """Git-less version string: the release number plus a hash of the harness's own code."""
    h = hashlib.sha256()
    for path in sorted((HARNESS_DIR / 'nb').glob('*.py')):
        h.update(path.name.encode())
        h.update(path.read_bytes())
    return f'{BASE_VERSION}+{h.hexdigest()[:10]}'
