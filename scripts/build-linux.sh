#!/usr/bin/env bash
# Builds the Linux app: dist/HorizonTranslator-linux-x64.tar.gz (Java included, no install needed).
# Run it on Linux (or WSL). Needs: JDK 21+ (with jpackage), Rust, Python 3, and ./horizon-src (scripts/get-horizon.sh).
# Set SHADERPORT_BIN to use an already-built Linux horizon-shaderport instead of building it,
# HORIZON_SRC to use Horizon's source from another folder, and APP_VERSION for the version (as build-desktop.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
HORIZON_SRC="${HORIZON_SRC:-horizon-src}"
export APP_VERSION="${APP_VERSION:-1.0.7}"
[ -f "$HORIZON_SRC/Cargo.toml" ] || bash scripts/get-horizon.sh
B="${BUILD_DIR:-desktop/build-linux}"
DIST="${DIST_DIR:-dist}"
NAME=HorizonTranslator
rm -rf "$B" && mkdir -p "$B/input/mappings" "$B/classes"

echo "== mod converter"
bash mod-converter/build.sh
echo "== shader converter"
if [ -z "${SHADERPORT_BIN:-}" ]; then
  ( cd shader-converter && cargo build --release )
  SHADERPORT_BIN=shader-converter/target/release/horizon-shaderport
fi
echo "== app"
javac --release 21 -nowarn -cp mod-converter/build/horizon-port.jar -d "$B/classes" desktop/src/horizonport/*.java
echo "$APP_VERSION" > "$B/classes/horizonport/app-version.txt"
mkdir -p "$B/classes/horizonport/i18n"
cp desktop/src/horizonport/i18n/*.properties "$B/classes/horizonport/i18n/"
jar --create --file "$B/input/horizon-translator-app.jar" --main-class horizonport.TranslatorApp -C "$B/classes" .
cp mod-converter/build/horizon-port.jar mod-converter/tools/vineflower-*.jar "$B/input/"
cp mod-converter/mappings/horizon-mappings.json "$B/input/mappings/"
cp "$SHADERPORT_BIN" "$B/input/horizon-shaderport"
chmod +x "$B/input/horizon-shaderport"
cp web/public/logo.png web/public/donate.json web/public/making-mods.md "$B/input/"
if [ -d web/public/i18n ]; then
  mkdir -p "$B/input/i18n"
  cp -r web/public/i18n/making-mods.*.md "$B/input/i18n/" 2>/dev/null || true
fi
# The mod kit's examples (Make a mod > Start from).
cp -r mod-converter/kit-examples "$B/input/"
python3 scripts/mods-index.py --check 2>/dev/null || python scripts/mods-index.py --check
cp -r web/public/mods "$B/input/"
python3 desktop/make-horizon-zip.py "$HORIZON_SRC" "$B/input/horizon-src.zip"

echo "== app image (bundles Java)"
jpackage --type app-image --dest "$B/app-image" --name "$NAME" \
  --input "$B/input" --main-jar horizon-translator-app.jar --main-class horizonport.TranslatorApp \
  --icon web/public/logo.png --app-version "$APP_VERSION" --vendor "Horizon Translator (community project)" \
  --copyright "Copyright (C) 2026 Horizon Translator contributors" \
  --description "Converts Fabric mods and shader packs for Horizon" \
  --add-modules java.base,java.desktop,jdk.zipfs \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-9"

# jpackage keeps the input files' modes: make sure the converter can run.
chmod +x "$B/app-image/$NAME/lib/app/horizon-shaderport"
cp desktop/installer-notice.txt "$B/app-image/$NAME/NOTICE.txt"
cat > "$B/app-image/$NAME/README.txt" <<EOF
Horizon Translator $APP_VERSION for Linux (x86-64)

Run it:          ./bin/$NAME
Add it to your app menu (optional, for your user only):  ./install-menu-entry.sh

Java is included: nothing else to install. Read NOTICE.txt first.
Source and help: https://github.com/merenotnoonereal/Horizon-Translator
EOF
cat > "$B/app-image/$NAME/install-menu-entry.sh" <<'EOF'
#!/usr/bin/env sh
# Adds Horizon Translator to your desktop's app menu (for your user only; no root needed).
here="$(cd "$(dirname "$0")" && pwd)"
apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$apps"
cat > "$apps/horizon-translator.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Horizon Translator
Comment=Converts Fabric mods and shader packs for Horizon
Exec="$here/bin/HorizonTranslator"
Icon=$here/lib/HorizonTranslator.png
Terminal=false
Categories=Game;Utility;
DESKTOP
echo "Added: $apps/horizon-translator.desktop"
EOF
chmod +x "$B/app-image/$NAME/install-menu-entry.sh"

echo "== archive"
mkdir -p "$DIST"
OUT="$DIST/$NAME-linux-x64.tar.gz"
tar -C "$B/app-image" -czf "$OUT" "$NAME"
ls -la "$OUT"
echo "Upload $OUT to the same GitHub Release as the Windows installer (docs/MAINTAINING.md)."
