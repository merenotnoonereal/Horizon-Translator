#!/usr/bin/env bash
# Builds the macOS app: dist/HorizonTranslator-mac-<arm64|x64>.dmg (Java included). Run it on a Mac (the GitHub
# workflow .github/workflows/mac.yml does, on GitHub's Mac machines). Needs: JDK 21+ (with jpackage), Rust,
# Python 3, and ./horizon-src (scripts/get-horizon.sh downloads it).
# APP_VERSION sets the version (as build-desktop.sh); SHADERPORT_BIN uses an already-built horizon-shaderport.
# The app isn't signed by Apple: macOS asks the player to allow it once (README-mac.txt in the .dmg says how).
set -euo pipefail
cd "$(dirname "$0")/.."
HORIZON_SRC="${HORIZON_SRC:-horizon-src}"
export APP_VERSION="${APP_VERSION:-1.0.7}"
[ -f "$HORIZON_SRC/Cargo.toml" ] || bash scripts/get-horizon.sh
B="${BUILD_DIR:-desktop/build-mac}"
DIST="${DIST_DIR:-dist}"
NAME="Horizon Translator"
ARCH="$(uname -m)"; [ "$ARCH" = x86_64 ] && ARCH=x64
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
jar --create --file "$B/input/horizon-translator-app.jar" --main-class horizonport.TranslatorApp -C "$B/classes" .
cp mod-converter/build/horizon-port.jar mod-converter/tools/vineflower-*.jar "$B/input/"
cp mod-converter/mappings/horizon-mappings.json "$B/input/mappings/"
cp "$SHADERPORT_BIN" "$B/input/horizon-shaderport"
chmod +x "$B/input/horizon-shaderport"
cp web/public/logo.png web/public/donate.json web/public/making-mods.md "$B/input/"
cp -r mod-converter/kit-examples "$B/input/"
python3 scripts/mods-index.py --check
cp -r web/public/mods "$B/input/"
python3 desktop/make-horizon-zip.py "$HORIZON_SRC" "$B/input/horizon-src.zip"

echo "== icon"
ICONSET="$B/logo.iconset"
mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z $s $s web/public/logo.png --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s * 2)) $((s * 2)) web/public/logo.png --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$B/logo.icns"

echo "== app (bundles Java)"
jpackage --type app-image --dest "$B/app-image" --name "$NAME" \
  --input "$B/input" --main-jar horizon-translator-app.jar --main-class horizonport.TranslatorApp \
  --icon "$B/logo.icns" --app-version "$APP_VERSION" --vendor "Horizon Translator (community project)" \
  --copyright "Copyright (C) 2026 Horizon Translator contributors" \
  --description "Converts Fabric mods and shader packs for Horizon" \
  --mac-package-identifier io.github.merenotnoonereal.horizontranslator \
  --add-modules java.base,java.desktop,jdk.zipfs \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-9"
APP="$B/app-image/$NAME.app"
chmod +x "$APP/Contents/app/horizon-shaderport"
# No Apple certificate: an ad-hoc signature, which Apple Silicon needs for the app to start at all.
codesign --force --deep --sign - "$APP"

echo "== disk image"
STAGE="$B/dmg"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
cp desktop/installer-notice.txt "$STAGE/NOTICE.txt"
cat > "$STAGE/README-mac.txt" <<EOF
Horizon Translator $APP_VERSION for macOS ($ARCH)

1. Drag "Horizon Translator" into Applications.
2. Open it. The first time, macOS says it can't check the app (it isn't signed with an Apple certificate):
   open System Settings > Privacy & Security, scroll down and press "Open Anyway" next to Horizon Translator.
   (Or, in Terminal: xattr -dr com.apple.quarantine "/Applications/Horizon Translator.app")

Java is included: nothing else to install. Read NOTICE.txt first.
Horizon's folder on a Mac is ~/Library/Application Support/horizon (mods and shader packs go there).
Source and help: https://github.com/merenotnoonereal/Horizon-Translator
EOF
mkdir -p "$DIST"
OUT="$DIST/HorizonTranslator-mac-$ARCH.dmg"
rm -f "$OUT"
hdiutil create -volname "Horizon Translator" -srcfolder "$STAGE" -ov -format UDZO "$OUT"
ls -la "$OUT"
echo "Upload $OUT to the same GitHub Release as the Windows installer."
