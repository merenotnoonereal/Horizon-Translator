#!/usr/bin/env bash
# Starts the website on http://localhost:8080 (set PORT to change it). Run scripts/build.sh first.
set -euo pipefail
cd "$(dirname "$0")/.."
exec java web/server/TranslatorServer.java
