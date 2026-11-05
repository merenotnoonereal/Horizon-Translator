#!/usr/bin/env python3
"""Publishes the shared mods: for each mods/<id>/ with a packed <id>.hnmod and a mod.json, copies the .hnmod to
web/public/mods/ and writes web/public/mods/index.json, the list the app's "Get mods" tab and the website read.

    python scripts/mods-index.py          # after packing a mod (horizon-port pack mods/<id> ...)
    python scripts/mods-index.py --check  # fails when web/public/mods is out of date

mod.json holds what the manifest doesn't: name, author, description, needs (which Horizon, in words).
The id, version and hooks API come from the .hnmod's own horizon-native.json.
"""
import hashlib
import json
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODS = ROOT / "mods"
OUT = ROOT / "web" / "public" / "mods"


def entries():
    out = []
    for d in sorted(p for p in MODS.iterdir() if p.is_dir()):
        hnmod, info = d / f"{d.name}.hnmod", d / "mod.json"
        if not hnmod.is_file() or not info.is_file():
            print(f"skipped {d.name}: needs {hnmod.name} and mod.json", file=sys.stderr)
            continue
        with zipfile.ZipFile(hnmod) as z:
            manifest = json.loads(z.read("horizon-native.json"))
        if manifest.get("id") != d.name:
            sys.exit(f"{hnmod}: its id is {manifest.get('id')!r}, not {d.name!r}")
        meta = json.loads(info.read_text(encoding="utf-8"))
        data = hnmod.read_bytes()
        out.append((hnmod, {
            "id": d.name,
            "name": meta.get("name", d.name),
            "version": manifest.get("version", ""),
            "author": meta.get("author", ""),
            "description": meta.get("description", ""),
            "needs": meta.get("needs", ""),
            "hooks": manifest.get("hooks", 1),
            "horizon": manifest.get("horizon", ""),
            "file": hnmod.name,
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        }))
    return out


def main():
    check = "--check" in sys.argv
    found = entries()
    index = json.dumps({"schema": 1, "mods": [e for _, e in found]}, indent=2) + "\n"
    if check:
        stale = [f.name for f, _ in found if not (OUT / f.name).is_file() or (OUT / f.name).read_bytes() != f.read_bytes()]
        current = (OUT / "index.json").read_text(encoding="utf-8") if (OUT / "index.json").is_file() else ""
        if stale or current != index:
            sys.exit("web/public/mods is out of date: run python scripts/mods-index.py")
        print(f"web/public/mods is up to date ({len(found)} mods)")
        return
    OUT.mkdir(parents=True, exist_ok=True)
    for f, _ in found:
        shutil.copyfile(f, OUT / f.name)
    (OUT / "index.json").write_text(index, encoding="utf-8", newline="\n")
    print(f"wrote {OUT / 'index.json'} ({len(found)} mods)")


if __name__ == "__main__":
    main()
