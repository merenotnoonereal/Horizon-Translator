#!/usr/bin/env bash
# Downloads the Horizon source both converters build against into ./horizon-src.
# Pinned to one commit: the converters read Horizon's internals, which change between nightlies.
#   bash scripts/get-horizon.sh                      download from GitHub
#   bash scripts/get-horizon.sh path/to/source.tar.gz  use a tarball you already have
set -euo pipefail
cd "$(dirname "$0")/.."
REV="${HORIZON_REV:-a46ce6898bd72016e061aaec1afc1375b384deac}"
DEST=horizon-src

if [ -f "$DEST/Cargo.toml" ]; then
  echo "horizon-src already exists (delete it to download again)"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if [ $# -ge 1 ]; then
  cp "$1" "$TMP/horizon.tar.gz"
else
  URL="https://github.com/horizon-rust/horizon/archive/$REV.tar.gz"
  echo "Downloading $URL"
  curl -fL --retry 3 -o "$TMP/horizon.tar.gz" "$URL"
fi
mkdir "$TMP/x"
tar -xzf "$TMP/horizon.tar.gz" -C "$TMP/x"
TOP="$(find "$TMP/x" -mindepth 1 -maxdepth 1 -type d | head -1)"
[ -f "$TOP/Cargo.toml" ] || { echo "That archive doesn't look like Horizon's source." >&2; exit 1; }
rm -rf "$DEST"
mv "$TOP" "$DEST"
# The commit, as Horizon's own source archives carry it: the mod converter names it in the .hnmod files it makes.
[ -f "$DEST/.horizon-revision" ] || [ $# -ge 1 ] || echo "$REV" > "$DEST/.horizon-revision"
echo "Horizon source ready in $DEST"
