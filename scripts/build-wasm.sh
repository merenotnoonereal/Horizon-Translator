#!/usr/bin/env bash
# Builds the shader and resource pack converters as WebAssembly for the website,
# so packs convert in the visitor's browser. Needs Rust + ./horizon-src (scripts/get-horizon.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
HORIZON_SRC="${HORIZON_SRC:-horizon-src}"
[ -f "$HORIZON_SRC/Cargo.toml" ] || [ -f horizon-src/Cargo.toml ] || bash scripts/get-horizon.sh
rustup target add wasm32-wasip1
( cd shader-converter && cargo build --release --target wasm32-wasip1 )
( cd resourcepack-converter && cargo build --release --target wasm32-wasip1 )

if [ -n "${CARGO_TARGET_DIR:-}" ]; then
  cp "$CARGO_TARGET_DIR/wasm32-wasip1/release/horizon-shaderport.wasm" web/public/horizon-shaderport.wasm
  cp "$CARGO_TARGET_DIR/wasm32-wasip1/release/horizon-resourcepackport.wasm" web/public/horizon-resourcepackport.wasm
else
  cp shader-converter/target/wasm32-wasip1/release/horizon-shaderport.wasm web/public/horizon-shaderport.wasm
  cp resourcepack-converter/target/wasm32-wasip1/release/horizon-resourcepackport.wasm web/public/horizon-resourcepackport.wasm
fi

echo "Built web/public/horizon-shaderport.wasm and web/public/horizon-resourcepackport.wasm"
