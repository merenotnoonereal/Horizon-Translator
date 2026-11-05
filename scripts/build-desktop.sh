#!/usr/bin/env bash
# Builds the Windows app: dist/HorizonTranslator-Setup.exe (unsigned).
# Needs: JDK 21+ (with jpackage), Rust, Python 3, Inno Setup 6, and ./horizon-src (scripts/get-horizon.sh).
# Set SHADERPORT_EXE to use an already-built horizon-shaderport.exe instead of building it,
# and HORIZON_SRC to use Horizon's source from another folder.
set -euo pipefail
cd "$(dirname "$0")/.."
HORIZON_SRC="${HORIZON_SRC:-horizon-src}"
# The app's version: shown in the app, compared with GitHub's latest release tag, and the installer's version.
# Tag the GitHub release with the same number (e.g. 1.0.2 or v1.0.2).
export APP_VERSION="${APP_VERSION:-1.0.7}"
[ -f "$HORIZON_SRC/Cargo.toml" ] || bash scripts/get-horizon.sh
# Build folder: keep it out of OneDrive/Dropbox folders, which lock files while syncing.
B="${BUILD_DIR:-desktop/build}"
DIST="${DIST_DIR:-dist}"
rm -rf "$B" && mkdir -p "$B/input/mappings" "$B/classes"

echo "== mod converter"
bash mod-converter/build.sh
echo "== shader converter"
if [ -z "${SHADERPORT_EXE:-}" ]; then
  if [ -n "${CARGO_TARGET_DIR:-}" ] && [ -f "$CARGO_TARGET_DIR/release/horizon-shaderport.exe" ]; then
    SHADERPORT_EXE="$CARGO_TARGET_DIR/release/horizon-shaderport.exe"
  elif [ -f "shader-converter/target/release/horizon-shaderport.exe" ]; then
    SHADERPORT_EXE="shader-converter/target/release/horizon-shaderport.exe"
  else
    ( cd shader-converter && cargo build --release )
    SHADERPORT_EXE="${CARGO_TARGET_DIR:-shader-converter/target}/release/horizon-shaderport.exe"
  fi
fi
echo "== resourcepack converter"
if [ -z "${RESOURCEPACKPORT_EXE:-}" ]; then
  if [ -n "${CARGO_TARGET_DIR:-}" ] && [ -f "$CARGO_TARGET_DIR/release/horizon-resourcepackport.exe" ]; then
    RESOURCEPACKPORT_EXE="$CARGO_TARGET_DIR/release/horizon-resourcepackport.exe"
  elif [ -f "resourcepack-converter/target/release/horizon-resourcepackport.exe" ]; then
    RESOURCEPACKPORT_EXE="resourcepack-converter/target/release/horizon-resourcepackport.exe"
  else
    ( cd resourcepack-converter && cargo build --release )
    RESOURCEPACKPORT_EXE="${CARGO_TARGET_DIR:-resourcepack-converter/target}/release/horizon-resourcepackport.exe"
  fi
fi
echo "== app"
javac --release 21 -nowarn -cp mod-converter/build/horizon-port.jar -d "$B/classes" desktop/src/horizonport/*.java
echo "$APP_VERSION" > "$B/classes/horizonport/app-version.txt"
mkdir -p "$B/classes/horizonport/i18n"
cp desktop/src/horizonport/i18n/*.properties "$B/classes/horizonport/i18n/"
JAR_TOOL="$(dirname "$(command -v javac)")/jar"
"$JAR_TOOL" --create --file "$B/input/horizon-translator-app.jar" --main-class horizonport.TranslatorApp -C "$B/classes" .
cp mod-converter/build/horizon-port.jar mod-converter/tools/vineflower-*.jar "$B/input/"
cp mod-converter/mappings/horizon-mappings.json "$B/input/mappings/"
cp "$SHADERPORT_EXE" "$B/input/horizon-shaderport.exe"
cp "$RESOURCEPACKPORT_EXE" "$B/input/horizon-resourcepackport.exe"
cp web/public/logo.png web/public/donate.json web/public/making-mods.md "$B/input/"
if [ -d web/public/i18n ]; then
  mkdir -p "$B/input/i18n"
  cp -r web/public/i18n/making-mods.*.md "$B/input/i18n/" 2>/dev/null || true
fi
# The mod kit's examples (Make a mod > Start from).
cp -r mod-converter/kit-examples "$B/input/"
python3 scripts/mods-index.py --check 2>/dev/null || python scripts/mods-index.py --check
cp -r web/public/mods "$B/input/"
python desktop/make-horizon-zip.py "$HORIZON_SRC" "$B/input/horizon-src.zip"
python desktop/make-ico.py web/public/logo.png "$B/logo.ico"

echo "== app image (bundles Java)"
jpackage --type app-image --dest "$B/app-image" --name "Horizon Translator" \
  --input "$B/input" --main-jar horizon-translator-app.jar --main-class horizonport.TranslatorApp \
  --icon "$B/logo.ico" --app-version "$APP_VERSION" --vendor "Horizon Translator (community project)" \
  --copyright "Copyright (C) 2026 Horizon Translator contributors" \
  --description "Converts Fabric mods and shader packs for Horizon" \
  --add-modules java.base,java.desktop,jdk.zipfs \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-9"

echo "== installer"
ISCC="${ISCC:-}"
for c in "$ISCC" "$LOCALAPPDATA/Programs/Inno Setup 6/ISCC.exe" "/c/Program Files (x86)/Inno Setup 6/ISCC.exe" "/c/Program Files/Inno Setup 6/ISCC.exe"; do
  [ -n "$c" ] && [ -f "$c" ] && ISCC="$c" && break
done
[ -f "$ISCC" ] || { echo "Inno Setup 6 not found: install it or set ISCC" >&2; exit 1; }
mkdir -p "$DIST"
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
MSYS2_ARG_CONV_EXCL="*" "$ISCC" /Q "/DBuildDir=$(winpath "$(cd "$B" && pwd)")" "/DDistDir=$(winpath "$(cd "$DIST" && pwd)")" desktop/installer.iss
ls -la "$DIST/HorizonTranslator-Setup.exe"
echo "Upload $DIST/HorizonTranslator-Setup.exe to a GitHub Release (see README)."
