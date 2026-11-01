#!/usr/bin/env bash
# Builds the shader converter as WebAssembly for the website (web/public/horizon-shaderport.wasm),
# so shader packs convert in the visitor's browser. Needs Rust + ./horizon-src (scripts/get-horizon.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f horizon-src/Cargo.toml ] || bash scripts/get-horizon.sh
rustup target add wasm32-wasip1
( cd shader-converter && cargo build --release --target wasm32-wasip1 )
cp shader-converter/target/wasm32-wasip1/release/horizon-shaderport.wasm web/public/horizon-shaderport.wasm
echo "Built web/public/horizon-shaderport.wasm"
