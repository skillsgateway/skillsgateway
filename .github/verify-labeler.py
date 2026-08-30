#!/usr/bin/env python3
"""Verify .github/labeler.yml: every glob matches at least one tracked file.

  python3 .github/verify-labeler.py    check; exit 1 on any finding

A glob that matches nothing is not an error to actions/labeler -- the rule just
never fires. That silence is the whole reason for this check: the Java package
move left nineteen dead globs, and every subsystem label plus the trust-boundary
warning stopped applying without a single red build (#193).

Stdlib only, and the file is read with a purpose-built reader for exactly the
structure it uses, so the check runs anywhere Python runs -- the same bargain
docs/diagrams/verify.py makes.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LABELER = ROOT / ".github" / "labeler.yml"

# The reader below understands only this file's shape; if it ever returns far
# fewer globs than the file holds, it has silently stopped seeing them.
MIN_GLOBS = 20

GLOB_KEY = re.compile(r"^(\s*)- any-glob-to-any-file:\s*(.*)$")
LIST_ITEM = re.compile(r"^(\s*)- '([^']+)'\s*$")


def globs(text):
    """Every glob under an any-glob-to-any-file key, inline or as a list."""
    found, indent = [], None
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        key = GLOB_KEY.match(line)
        if key:
            rest = key.group(2).strip()
            if rest:
                found.append(rest.strip("'"))
                indent = None
            else:
                indent = len(key.group(1))
            continue
        if indent is None:
            continue
        item = LIST_ITEM.match(line)
        if item and len(item.group(1)) > indent:
            found.append(item.group(2))
        elif line.strip():
            indent = None
    return found


def to_regex(glob):
    """Minimatch subset: ** spans directories, * and ? stay inside a segment."""
    out, i = [], 0
    while i < len(glob):
        c = glob[i]
        if glob.startswith("**/", i):
            out.append(r"(?:.*/)?")
            i += 3
        elif glob.startswith("/**", i) and i + 3 == len(glob):
            out.append(r"(?:/.*)?")
            i += 3
        elif glob.startswith("**", i):
            out.append(r".*")
            i += 2
        elif c == "*":
            out.append(r"[^/]*")
            i += 1
        elif c == "?":
            out.append(r"[^/]")
            i += 1
        else:
            out.append(re.escape(c))
            i += 1
    return re.compile("^" + "".join(out) + "$")


def main():
    tracked = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout.splitlines()

    patterns = globs(LABELER.read_text(encoding="utf-8"))
    if len(patterns) < MIN_GLOBS:
        print(f"{LABELER.name}: read only {len(patterns)} globs; the reader is out of step "
              f"with the file's structure")
        return 1

    dead = [g for g in patterns if not any(to_regex(g).match(f) for f in tracked)]
    for g in dead:
        print(f"{LABELER.name}: glob matches no tracked file: {g}")
    if dead:
        print(f"\n{len(dead)} of {len(patterns)} globs are dead. A label whose globs match "
              f"nothing never applies, and nothing else reports it.")
        return 1

    print(f"{LABELER.name}: {len(patterns)} globs, all matching tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
