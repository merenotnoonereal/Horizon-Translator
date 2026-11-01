#!/usr/bin/env bash
# Builds both converters. Needs: JDK 21+, Rust 1.85+ (rustup), and ./horizon-src (scripts/get-horizon.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f horizon-src/Cargo.toml ] || bash scripts/get-horizon.sh
echo "== mod converter (Java)"
bash mod-converter/build.sh
echo "== shader converter (Rust)"
( cd shader-converter && cargo build --release )
echo "Done. Start the website with: bash scripts/run-local.sh"
