#!/usr/bin/env bash
# Builds build/horizon-port.jar. Needs a JDK 21 or newer.
set -euo pipefail
cd "$(dirname "$0")"
. ./jdk.sh

rm -rf build/classes
mkdir -p build/classes
javac --release 21 -Xlint:all -d build/classes src/horizonport/*.java
# Rust the translator puts into bundles.
cp src/horizonport/*.rs build/classes/horizonport/
"$JAR" --create --file build/horizon-port.jar --main-class horizonport.Main -C build/classes .
echo "Built build/horizon-port.jar"
