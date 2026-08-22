#!/usr/bin/env python3
"""Verify docs/diagrams/manifest.yml: every Mermaid source, its generated SVG
pair, and the pages that embed it exist and are in sync.

  python3 docs/diagrams/verify.py            check; exit 1 on any finding
  python3 docs/diagrams/verify.py --update   rewrite stale source-sha256 values

Checked per manifest entry:
  - source .mmd, both output SVGs, and every embedding page exist
  - source-sha256 matches the .mmd (drift = .mmd changed without regeneration)
  - each embedding page actually snippets both outputs

Stdlib only — the manifest is parsed with a purpose-built reader for exactly
the structure this file uses, so the check runs anywhere Python runs.
"""

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
MANIFEST = ROOT / "docs" / "diagrams" / "manifest.yml"


def parse_manifest(text):
    entries, entry, in_embedded = [], None, False
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        if re.match(r"^  - name:", line):
            entry = {"name": line.split(":", 1)[1].strip(), "embedded-in": []}
            entries.append(entry)
            in_embedded = False
        elif entry is not None and re.match(r"^      - ", line) and in_embedded:
            entry["embedded-in"].append(line.split("- ", 1)[1].strip())
        elif entry is not None and ":" in line:
            key, _, val = line.strip().partition(":")
            val = val.strip()
            in_embedded = key == "embedded-in"
            if key in ("source", "source-sha256", "light", "dark"):
                entry[key] = val
    return entries


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    update = "--update" in sys.argv[1:]
    text = MANIFEST.read_text()
    findings = []

    for e in parse_manifest(text):
        name = e["name"]
        src = ROOT / e["source"]
        if not src.is_file():
            findings.append(f"{name}: source missing: {e['source']}")
            continue
        actual = sha256(src)
        if actual != e.get("source-sha256"):
            if update:
                text = text.replace(e["source-sha256"], actual)
                print(f"{name}: source-sha256 refreshed")
            else:
                findings.append(
                    f"{name}: {e['source']} changed since the SVGs were generated"
                    " — regenerate the pair (see .claude/skills/docs-diagrams),"
                    " then run verify.py --update"
                )
        for variant in ("light", "dark"):
            out = ROOT / e[variant]
            if not out.is_file():
                findings.append(f"{name}: {variant} output missing: {e[variant]}")
        for page in e["embedded-in"]:
            p = ROOT / page
            if not p.is_file():
                findings.append(f"{name}: embedding page missing: {page}")
                continue
            content = p.read_text()
            for variant in ("light", "dark"):
                if e[variant] not in content:
                    findings.append(
                        f"{name}: {page} does not snippet {e[variant]}"
                    )

    if update:
        MANIFEST.write_text(text)

    if findings:
        print("\n".join(findings), file=sys.stderr)
        return 1
    print("diagrams manifest: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
