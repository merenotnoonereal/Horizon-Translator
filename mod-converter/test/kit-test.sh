#!/usr/bin/env bash
# Tests the mod kit (ModKit.java, the app's "Make a mod" tab): makes every example in kit-examples/ plus one mod that
# uses every feature, then for each runs the mod's own tests (cargo test), `check` (its Rust against Horizon's
# helpers, and horizon-mixins' plan when that tool is installed) and looks inside the .hnmod.
# Needs: build/horizon-port.jar (./build.sh), cargo, and Horizon's source: HORIZON_SRC (default ../horizon-src).
#   bash test/kit-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="${HORIZON_SRC:-../horizon-src}"
[ -f "$SRC/crates/horizon-client/src/game/native_hooks.rs" ] || { echo "No Horizon source at $SRC: set HORIZON_SRC" >&2; exit 1; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
port() { java -cp build/horizon-port.jar horizonport.Main "$@"; }

cat > "$OUT/everything.json" <<'EOF'
{
  "name": "Kit \"Test\" Everything",
  "version": "2.1",
  "description": "Every feature. TODO in text must survive.",
  "hud": {"corner": "bottom_right", "lines": ["{x} {y} {z}", "{facing} {dimension} {fps} {unknown}"], "color": "#ffaa00", "background": false, "key": "h"},
  "zoom": {"key": "key.mouse.middle", "factor": 6.5, "hold": false, "smooth": false, "scroll": true},
  "keys": [
    {"label": "Say hi", "key": "key.keyboard.g", "action": "say", "text": "Hi\nwith a \\ backslash and \"quotes\""},
    {"label": "Toggle box", "key": "key.keyboard.left.alt", "action": "toggle_hud"},
    {"label": "Zoom too", "key": "key.keyboard.z", "action": "toggle_zoom"}
  ],
  "commands": [
    {"name": "/Hello", "action": "say", "text": ["Hello {bx}"]},
    {"name": "box", "action": "toggle_hud"},
    {"name": "zz", "action": "toggle_zoom"}
  ],
  "welcome": ["Hi!", "Two lines"],
  "timers": [{"every": 60, "text": "every minute"}, {"every": 7, "text": ["seven", "x2"]}]
}
EOF

fail=0
for spec in kit-examples/*.json "$OUT/everything.json"; do
  n="$(basename "$spec" .json)"
  echo "== $n"
  port new "$spec" --out "$OUT/$n" --horizon-src "$SRC" >/dev/null
  hnmod="$(ls "$OUT/$n"/*.hnmod)"
  grep -q '"hooks": 1' <<<"$(unzip -p "$hnmod" horizon-native.json)" || { echo "FAIL: $n's .hnmod doesn't say hooks 1"; fail=1; }
  if grep -rq "TODO" "$OUT/$n/mixins"; then echo "FAIL: TODO in $n's code"; fail=1; fi
  [ -s "$OUT/$n/AGENTS.md" ] || { echo "FAIL: no AGENTS.md in $n"; fail=1; }
  grep -q '### `native_key`' "$OUT/$n/HOOKS.md" 2>/dev/null || { echo "FAIL: no HOOKS.md (with native_key) in $n"; fail=1; }
  tests="$(cd "$OUT/$n" && cargo test --quiet 2>&1 || true)"
  grep -q "test result: ok. 7 passed" <<<"$tests" || { echo "FAIL: $n's own tests"; echo "$tests" | tail -20; fail=1; }
  port check "$OUT/$n" --horizon-src "$SRC" | grep -E "^ok|rejects|doesn't compile|not checked" || true
  port check "$OUT/$n" --horizon-src "$SRC" >/dev/null || { echo "FAIL: check $n"; fail=1; }
done

echo "== a spec with mistakes is refused, with every problem named"
echo '{"name":"","id":"Bad Id","commands":[{"name":"a b"},{"name":"x","action":"say"}]}' > "$OUT/bad.json"
if port new "$OUT/bad.json" --out "$OUT/bad" 2>"$OUT/bad.txt"; then echo "FAIL: bad spec accepted"; fail=1; fi
[ "$(grep -c '^- ' "$OUT/bad.txt")" = 4 ] || { echo "FAIL: expected 4 problems:"; cat "$OUT/bad.txt"; fail=1; }

echo "== pack: an edited mod is checked and packed again"
sed -i 's/^pub const WELCOME: &\[&str\] = &\["Welcome back!/pub const WELCOME: \&[\&str] = \&["Edited: welcome back!/' "$OUT/reminders/mixins/break_reminder/runtime.rs"
rm "$OUT/reminders/break_reminder.hnmod"
port pack "$OUT/reminders" --horizon-src "$SRC" --install-to "$OUT/home" | tail -2
grep -q "Edited: welcome back" <<<"$(unzip -p "$OUT/home/horizon-native-mods/break_reminder.hnmod" mixins/break_reminder/runtime.rs)" || { echo "FAIL: pack"; fail=1; }
grep -q "mod.break_reminder:true" "$OUT/home/horizon-mods.txt" || { echo "FAIL: install"; fail=1; }

echo "== hooks: the list, as JSON, has the hook points the kit uses, each with its injection's bindings"
port hooks --horizon-src "$SRC" --json --out "$OUT/hooks.json" | tail -1
python - "$OUT/hooks.json" <<'EOF' || fail=1
import json, sys
d = json.load(open(sys.argv[1]))
h = {x["name"]: x for x in d["hooks"]}
for name in ("native_key", "native_hud", "native_command", "native_tick"):
    assert h[name]["kind"] == "hook" and h[name]["bindings"][0]["name"] == "game", name
    assert h[name]["target"].endswith("::" + name), name
assert h["native_chat"]["kind"] == "helper"
print("ok:", len(h), "entries, hooks API", d["hooks_api"])
EOF

# The real compile needs Horizon's dependencies built once (a few GB): only when asked.
if [ -n "${KIT_TEST_COMPILE:-}" ]; then
  echo "== compile: the reminders example builds inside Horizon"
  port compile "$OUT/reminders" --horizon-src "$SRC" ${KIT_TEST_WORK:+--work "$KIT_TEST_WORK"} | tail -3
  [ "${PIPESTATUS[0]}" = 0 ] || { echo "FAIL: compile"; fail=1; }
fi

[ "$fail" = 0 ] && echo "kit-test: all passed" || { echo "kit-test: FAILED"; exit 1; }
