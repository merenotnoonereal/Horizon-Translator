#!/usr/bin/env bash
# Builds the converter and the fixture mod jar, runs analyze and match on it, and checks the results.
set -euo pipefail
cd "$(dirname "$0")/.."
. ./jdk.sh
bash build.sh
bash test/make-fixture.sh

F=test/fixture
B=test/build
rm -rf "$B"
mkdir -p "$B/stubs" "$B/mod"
javac --release 21 -d "$B/stubs" $(find $F/stubs -name '*.java')
javac --release 21 -cp "$B/stubs" -d "$B/mod" $(find $F/mod/src -name '*.java')
# The jar holds only the mod's own classes and resources, like a real mod jar.
"$JAR" --create --file "$B/sneakhud.jar" -C "$B/mod" . -C $F/mod/resources .

run() { java -jar build/horizon-port.jar "$@"; }
# Java class paths use ; on Windows and : elsewhere.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) PATHSEP=";" ;; *) PATHSEP=":" ;; esac
fail=0
expect() { # expect <file> <text>
  if grep -qF -- "$2" "$1"; then echo "  ok: $2"; else echo "  FAIL: expected '$2' in $1"; fail=1; fi
}

echo; echo "== analyze"
run analyze "$B/sneakhud.jar" --out "$B/out"
R="$B/out/report.json"
expect "$R" '"verdict": "Partly convertible"'
expect "$R" '"detail": "ClientTickEvents.END_CLIENT_TICK"'
expect "$R" '"detail": "HudRenderCallback.EVENT"'
expect "$R" '"detail": "KeyBindingHelper.registerKeyBinding"'
expect "$R" '"detail": "@Inject(HEAD) on Minecraft.tick"'
expect "$R" '"horizon": "HEAD on hook `tick`"'
expect "$R" '"detail": "@Inject(INVOKE) on LocalPlayer.aiStep at Lnet/minecraft/client/player/LocalPlayer;setSprinting(Z)V"'
expect "$R" '"detail": "@Redirect(INVOKE) on MouseHandler.onScroll'
expect "$R" '"detail": "extends Block"'
expect "$R" 'Overrides getLightEmission'
expect "$R" '"detail": "registers blocks or items in code"'
expect "$R" '"detail": "2 asset files, 2 data files"'

echo; echo "== match"
run match "$B/sneakhud.jar" $F/horizon-src --out "$B/out/suggested-mappings.json"
S="$B/out/suggested-mappings.json"
expect "$S" '"rust": "horizon_client::player::local_player::LocalPlayer::ai_step"'
expect "$S" '"rust": "horizon_client::player::local_player::LocalPlayer::set_sprinting"'
expect "$S" 'horizon_server::entity::mob::Mob::ai_step (score'
expect "$S" '"rust": "horizon_world::props::clear_fall"'
expect "$S" 'doc is LocalPlayer.resetFallDistance'
if grep -q 'strength_rejects_negative\|helper_in_tests' "$S"; then echo "  FAIL: unit tests were indexed"; fail=1; else echo "  ok: unit tests left out"; fi
# Mapped by the base table (helper calls), so match has nothing to suggest for them.
expect "$B/out/report.json" '"java": "net/minecraft/client/gui/GuiGraphics#drawString"'
expect "$B/out/report.md" 'self.native_text(batch, text, [x, y], rgb, shadow)'

echo; echo "== analyze with the suggestions"
run analyze "$B/sneakhud.jar" --mappings "$S" --out "$B/out2"
R2="$B/out2/report.json"
expect "$R2" '"horizon": "BEFORE_CALL on horizon_client::player::local_player::LocalPlayer::ai_step"'
grep -A3 '"detail": "@Inject(INVOKE) on LocalPlayer.aiStep' "$R2" | grep -q '"status": "auto"' \
  && echo "  ok: the aiStep mixin is auto once mapped" || { echo "  FAIL: aiStep mixin not auto"; fail=1; }

echo; echo "== generate"
run generate "$B/sneakhud.jar" --mappings test/fixture-mappings.json --horizon-src $F/horizon-src \
  --horizon-revision a46ce6898bd72016e061aaec1afc1375b384deac --out "$B/gen"
G="$B/gen/mixins/sneakhud"
for f in horizon-mixin.toml runtime.rs list.rs key.rs tick.rs hud.rs local-player-mixin-before-sprint.rs; do
  [ -f "$G/$f" ] && echo "  ok: wrote $f" || { echo "  FAIL: no $f"; fail=1; }
done
T="$G/horizon-mixin.toml"
expect "$T" 'target = "horizon_client::game::native_hooks::Game<'"'"'_>::native_tick"'
expect "$T" 'kind = "BEFORE_CALL"'
expect "$T" 'target = "horizon_client::player::local_player::LocalPlayer::ai_step"'
expect "$T" 'selector = "set_sprinting"'
expect "$T" 'require = 1'
expect "$T" '{ name = "this", ty = "&mut LocalPlayer", value = "&mut *self" },'
expect "$T" 'horizon_revision = "a46ce6898bd72016e061aaec1afc1375b384deac"'
expect "$T" '# Off: @Redirect(INVOKE) on MouseHandler.onScroll'
expect "$G/key.rs" 'game.native_mod_key(m::ID, "toggle", "key.keyboard.unknown")'
expect "$G/runtime.rs" '["toggle", "Toggle", "key.keyboard.unknown"],'
expect "$G/tick.rs" 'Mixin @Inject(HEAD) on Minecraft.tick'
expect "$G/local-player-mixin-before-sprint.rs" 'fn sneakhud_before_sprint(this: &mut LocalPlayer)'
expect "$B/gen/PORTING.md" '| hook `hud` | on |'
expect "$B/gen/Cargo.toml" 'path = "mixins/sneakhud/runtime.rs"'

echo; echo "== generate with the mod's Java source"
run generate "$B/sneakhud.jar" --mappings test/fixture-mappings.json --horizon-src $F/horizon-src \
  --java-src $F/mod/src --out "$B/genj"
J="$B/genj/mixins/sneakhud"
expect "$J/tick.rs" '// Original Java, com/example/sneakhud/SneakHud.java:23:'
expect "$J/tick.rs" '// | ClientTickEvents.END_CLIENT_TICK.register(client -> {'
expect "$J/tick.rs" '// |     if (client.player != null) client.player.resetFallDistance();'
expect "$J/tick.rs" '// | private void sneakhud$onTick(CallbackInfo ci) {}'
expect "$J/hud.rs" 'graphics.drawString(mc.font, "Sneaking", 4, 4, 0xFFFFFF);'
expect "$J/local-player-mixin-before-sprint.rs" '// | @Inject(method = "aiStep", at = @At(value = "INVOKE"'
expect "$J/runtime.rs" '["toggle", "Toggle", "key.keyboard.h"],'
expect "$J/game_side.rs" 'const KEY_NAMES: [(&str, &str); 1] = [("toggle", "key.keyboard.h"), ];'

expect "$B/genj/todo/mouse-handler-mixin-scroll.rs" '// | private boolean sneakhud$scroll(Object player) {'
expect "$J/runtime.rs" 'pub mod java {'
expect "$J/hud.rs" 'crate::game::port_sneakhud_game::on_hud_0(game, batch);'
expect "$J/tick.rs" 'crate::game::port_sneakhud_game::on_tick_0(game);'
expect "$J/key.rs" 'crate::game::port_sneakhud_game::key_event(game, key, pressed);'
expect "$J/game_side.rs" 'while key_consume("toggle") {'
expect "$J/horizon-mixin.toml" 'name = "port_sneakhud_game"'
expect "$B/genj/report/translation.md" '# Java translated to Rust'
expect "$B/genj/PORTING.md" 'Notes: `todo/mouse-handler-mixin-scroll.rs`.'
echo; echo "== licence check, decompile and port"
javac -cp build/classes -d "$B/test" test/LicenseTest.java
java -cp "build/classes$PATHSEP$B/test" LicenseTest || fail=1
javac -cp build/classes -d "$B/test" test/JavaSourceTest.java
java -cp "build/classes$PATHSEP$B/test" JavaSourceTest || fail=1
# The test mod says MIT: it may be decompiled and ported.
run port "$B/sneakhud.jar" --mappings test/fixture-mappings.json --horizon-src $F/horizon-src --out "$B/port" > "$B/port.log"
expect "$B/port.log" 'Open licence: decompiling and porting are allowed'
[ -f "out/sneakhud-src/com/example/sneakhud/SneakHud.java" ] && echo "  ok: decompiled SneakHud.java" || { echo "  FAIL: no decompiled source"; fail=1; }
expect "$B/port/mixins/sneakhud/tick.rs" '// Decompiled Java, com/example/sneakhud/SneakHud.java:'
expect "$B/port/mixins/sneakhud/tick.rs" 'consumeClick()'
expect "$B/port/mixins/sneakhud/runtime.rs" '"key.keyboard.h"'
expect "$B/port/PORTING.md" '## Licence'
[ ! -e "$B/port/com" ] && echo "  ok: no decompiled code inside the bundle" || { echo "  FAIL: decompiled code in the bundle"; fail=1; }
rm -rf out/sneakhud-src
# The same mod relabelled All Rights Reserved: refused unless its author says so.
mkdir -p "$B/arr" && cp "$B/sneakhud.jar" "$B/arr/sneakhud-arr.jar"
( cd "$B/arr" && sed 's/"license": "MIT"/"license": "ARR", "authors": ["merenotnoone"]/' ../../fixture/mod/resources/fabric.mod.json > fabric.mod.json && "$JAR" --update --file sneakhud-arr.jar fabric.mod.json )
if run decompile "$B/arr/sneakhud-arr.jar" --out "$B/arr/src" > "$B/arr/no.log"; then echo "  FAIL: an ARR mod was decompiled"; fail=1; else echo "  ok: ARR mod refused"; fi
expect "$B/arr/no.log" 'pass --i-am-the-author with your name as the jar lists it (merenotnoone)'
if run decompile "$B/arr/sneakhud-arr.jar" --i-am-the-author someone-else --out "$B/arr/src" > "$B/arr/wrong.log"; then echo "  FAIL: a wrong author was accepted"; fail=1; else echo "  ok: wrong author refused"; fi
run decompile "$B/arr/sneakhud-arr.jar" --i-am-the-author MereNotNoOne --out "$B/arr/src" > "$B/arr/yes.log" && echo "  ok: author accepted" || { echo "  FAIL: author refused"; fail=1; }
expect "$B/arr/src/LICENSE-CHECK.md" 'you stated you are merenotnoone'

echo; echo "== full port or not (.hnmod)"
# Sneak HUD keeps TODOs, a commented-out mixin, a block and data: not a full port, so no .hnmod.
expect "$B/gen/PORTING.md" "**No: this isn't a full port**"
expect "$B/gen/PORTING.md" '1 mixins are written commented out'
expect "$B/gen/PORTING.md" 'data files (recipes, loot, tags, worldgen…) need a content mod (.hmod)'
[ ! -e "$B/gen/sneakhud.hnmod" ] && echo "  ok: no .hnmod for a partial port" || { echo "  FAIL: a partial port got a .hnmod"; fail=1; }
# Tally: an initializer and plain logic, nothing else: a full port.
TL="$B/tally"
mkdir -p "$TL/src/com/example/tally" "$TL/res" "$TL/cls" "$TL/hsrc/launcher/src"
cp -r $F/horizon-src/. "$TL/hsrc/"
printf 'pub const MIXINS_REV: &str = "3f99a5c60dfa311cc365524c44ae2fd799bfa1de";\n' > "$TL/hsrc/launcher/src/native_mods.rs"
echo a46ce6898bd72016e061aaec1afc1375b384deac > "$TL/hsrc/.horizon-revision"
cat > "$TL/src/com/example/tally/Tally.java" <<'EOF'
package com.example.tally;
import net.fabricmc.api.ClientModInitializer;
public class Tally implements ClientModInitializer {
    static int total;
    public void onInitializeClient() { total = Maths.sum(new int[] {1, 2, 3}); }
}
EOF
cat > "$TL/src/com/example/tally/Maths.java" <<'EOF'
package com.example.tally;
public final class Maths {
    private Maths() {}
    public static int sum(int[] xs) { int t = 0; for (int x : xs) t += x; return t; }
}
EOF
cat > "$TL/res/fabric.mod.json" <<'EOF'
{ "schemaVersion": 1, "id": "tally", "version": "1.0.0", "name": "Tally", "license": "MIT",
  "authors": ["someone"], "entrypoints": { "client": ["com.example.tally.Tally"] } }
EOF
mkdir -p "$TL/res/assets/tally/lang" && echo '{}' > "$TL/res/assets/tally/lang/en_us.json"
javac --release 21 -cp "$B/stubs" -d "$TL/cls" $(find "$TL/src" -name '*.java')
"$JAR" --create --file "$TL/tally.jar" -C "$TL/cls" . -C "$TL/res" .
java -Dhorizonport.srcdir="$TL/dec" -jar build/horizon-port.jar port "$TL/tally.jar" --mappings test/fixture-mappings.json \
  --horizon-src "$TL/hsrc" --out "$TL/out" --install-to "$TL/game" > "$TL/port.log"
expect "$TL/port.log" 'FULL PORT: tally.hnmod is ready to install'
expect "$TL/out/PORTING.md" '**Yes: this is a full port.**'
expect "$TL/out/mixins/tally/tick.rs" 'port_tally_game::ensure_init(game);'
if grep -rq TODO "$TL/out/mixins/tally"; then echo "  FAIL: TODO in a full port"; fail=1; else echo "  ok: no TODO in the full port"; fi
mkdir -p "$TL/unz" && ( cd "$TL/unz" && "$JAR" xf ../out/tally.hnmod )
expect "$TL/unz/horizon-native.json" '"horizon": "a46ce6898bd72016e061aaec1afc1375b384deac"'
expect "$TL/unz/horizon-native.json" '"mixins": "3f99a5c60dfa311cc365524c44ae2fd799bfa1de"'
expect "$TL/unz/horizon-native.json" '"id": "tally"'
expect "$TL/unz/mixins/tally/horizon-mixin.toml" 'horizon_revision = "a46ce6898bd72016e061aaec1afc1375b384deac"'
extra=$(cd "$TL/unz" && find . -type f | grep -v '^./horizon-native.json$' | grep -Ev '^./mixins/tally/[^/]+\.(rs|toml)$' || true)
[ -z "$extra" ] && echo "  ok: only Horizon's allowed files in the .hnmod" || { echo "  FAIL: unexpected files: $extra"; fail=1; }
[ -f "$TL/game/horizon-native-mods/tally.hnmod" ] && echo "  ok: installed into horizon-native-mods" || { echo "  FAIL: not installed"; fail=1; }
expect "$TL/game/horizon-mods.txt" 'mod.tally:true'

echo; echo "== body checks"
javac -cp build/classes -d "$B/test" test/BodyCheckTest.java
java -cp "build/classes$(printf %s "${PATHSEP:-;}")$B/test" BodyCheckTest || fail=1

echo; echo "== Java to Rust"
javac -cp build/classes -d "$B/test" test/TranslatorTest.java
java -Drust.out="$B/rust" -cp "build/classes$(printf %s "${PATHSEP:-;}")$B/test" TranslatorTest || fail=1

# With Rust installed, the translated code must compile, and the fixture bundle's tests must pass.
RUSTC=$(command -v rustc || ls "$HOME/.cargo/bin/rustc.exe" "$HOME/.cargo/bin/rustc" 2>/dev/null | head -1)
CARGO=$(command -v cargo || ls "$HOME/.cargo/bin/cargo.exe" "$HOME/.cargo/bin/cargo" 2>/dev/null | head -1)
if [ -n "$RUSTC" ] && [ -n "$CARGO" ]; then
  for f in "$B"/rust/case*.rs; do
    if "$RUSTC" --edition 2024 --crate-type lib -A warnings -o "$B/rust/$(basename "$f" .rs).rlib" "$f" > "$B/rust/rustc.log" 2>&1; then
      echo "  ok: $(basename "$f") compiles"
    else
      echo "  FAIL: $(basename "$f") doesn't compile"; head -20 "$B/rust/rustc.log"; fail=1
    fi
  done
  # game_side.rs and the hook helpers, against the native_* helpers of a real Horizon source when there is one.
  HSRC=""; [ -f ../horizon-src/Cargo.toml ] && HSRC=../horizon-src
  if [ -n "$HSRC" ]; then
    HORIZON_MIXINS=none run check "$B/genj" --horizon-src "$HSRC" > "$B/check.log" 2>&1       && echo "  ok: the fixture bundle compiles against $HSRC's helpers" || { echo "  FAIL: check"; tail -20 "$B/check.log"; fail=1; }
  else
    echo "  (no Horizon source in ../horizon-src (scripts/get-horizon.sh): game_side.rs isn't compiled here)"
  fi
  (cd "$B/genj" && CARGO_TARGET_DIR="../cargo-target" "$CARGO" test --quiet > ../cargo.log 2>&1)     && echo "  ok: the fixture bundle's cargo test passes" || { echo "  FAIL: cargo test in the fixture bundle"; tail -20 "$B/cargo.log"; fail=1; }
else
  echo "  (no Rust installed: the Rust it writes isn't compiled here)"
fi

echo
[ $fail = 0 ] && echo "All checks passed." || { echo "Some checks failed."; exit 1; }
