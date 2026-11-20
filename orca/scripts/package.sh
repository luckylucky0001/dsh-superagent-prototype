#!/bin/sh
# Builds a release: dist/orca-<version>/{orca-server.jar (with the web UI), orca-daemon.jar, scripts, deploy files}.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)
OUT="$ROOT/dist/orca-$VERSION"
(cd "$ROOT/web" && npm ci --no-audit --no-fund && npm run build)
(cd "$ROOT" && mvn -q -B package -DskipTests)
rm -rf "$OUT" && mkdir -p "$OUT/daemon"
cp "$ROOT/orca-server/target/orca-server.jar" "$OUT/"
cp "$ROOT/orca-daemon/target/orca-daemon-exec.jar" "$OUT/daemon/orca-daemon.jar"
cp "$ROOT/dist-scripts/"* "$OUT/daemon/"
cp -r "$ROOT/deploy" "$OUT/deploy"
cp "$ROOT/README.md" "$OUT/"
(cd "$ROOT/dist" && tar czf "orca-$VERSION.tar.gz" "orca-$VERSION")
echo "built $ROOT/dist/orca-$VERSION.tar.gz"
