# Packs the parts of Horizon's source the mod converter reads (.rs files, minus tests and build output)
# into one zip the desktop app ships, with .horizon-revision (the commit, which a native mod's .hnmod names).
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
skip = ("/target/", "/tests/", "/examples/", "/benches/", "/third_party/")
n = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    rev = os.path.join(src, ".horizon-revision")
    if os.path.isfile(rev):
        z.write(rev, ".horizon-revision")
    else:
        print("warning: no .horizon-revision in the source: the app won't be able to make .hnmod files", file=sys.stderr)
    for dirpath, _, files in os.walk(src):
        for f in files:
            if not f.endswith(".rs"):
                continue
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, src).replace("\\", "/")
            r = "/" + rel
            if any(s in r for s in skip) or r.endswith(("_test.rs", "_tests.rs", "/tests.rs")):
                continue
            z.write(full, rel)
            n += 1
print(f"{n} files -> {out}")
