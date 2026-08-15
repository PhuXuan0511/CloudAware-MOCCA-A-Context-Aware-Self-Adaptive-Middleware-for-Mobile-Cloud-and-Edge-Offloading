"""
Pytest bootstrap for the server-side test suite.

The source directories are named `edge-server/` and `cloud-server/`, but the
code imports them as `edge_server` / `cloud_server` — hyphens are not valid in
Python identifiers. In production the Dockerfiles paper over this by copying
`edge-server/ -> ./edge_server/` at build time:

    COPY edge-server/ ./edge_server/

Tests run against the working tree, not the image, so we reproduce that rename
in-process by registering package objects whose ``__path__`` points at the
hyphenated directory. This keeps the tests honest about the real module names
without duplicating the source layout.
"""
from __future__ import annotations

import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# `shared` is already importable under its real name once the repo root is on
# the path; the two servers need the hyphen-to-underscore alias.
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

for _package_name, _directory in (
    ("edge_server", "edge-server"),
    ("cloud_server", "cloud-server"),
):
    if _package_name in sys.modules:
        continue
    _path = ROOT / _directory
    if not _path.is_dir():
        continue
    _module = types.ModuleType(_package_name)
    _module.__path__ = [str(_path)]  # namespace-package style
    sys.modules[_package_name] = _module
