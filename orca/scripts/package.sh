#!/bin/sh
# Builds a release: dist/orca-<version>/{orca-server.jar (with the web UI), daemon/, cli/, downloads/, deploy/}.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)
OUT="$ROOT/dist/orca-$VERSION"
(cd "$ROOT/web" && npm ci --no-audit --no-fund && npm run build)
(cd "$ROOT" && mvn -q -B package -DskipTests)
rm -rf "$OUT" && mkdir -p "$OUT/daemon" "$OUT/cli" "$OUT/downloads"
cp "$ROOT/orca-server/target/orca-server.jar" "$OUT/"
cp "$ROOT/orca-daemon/target/orca-daemon-exec.jar" "$OUT/daemon/orca-daemon.jar"
cp "$ROOT/dist-scripts/orca-daemon" "$ROOT/dist-scripts/orca-daemon.cmd" "$ROOT/dist-scripts/orca-daemon.service" \
  "$ROOT/dist-scripts/install-runtime.sh" "$OUT/daemon/"
cp "$ROOT/orca-cli/target/orca-cli-exec.jar" "$OUT/cli/orca-cli.jar"
cp "$ROOT/dist-scripts/orca" "$ROOT/dist-scripts/orca.cmd" "$OUT/cli/"
# what the server hands out at /downloads/ (set ORCA_DOWNLOADS_DIR to this folder)
cp "$OUT/daemon/orca-daemon.jar" "$OUT/cli/orca-cli.jar" "$ROOT/dist-scripts/install-runtime.sh" "$OUT/downloads/"
cp -r "$ROOT/deploy" "$OUT/deploy"
cp "$ROOT/README.md" "$ROOT/THIRD-PARTY-NOTICES.md" "$OUT/"
(cd "$ROOT/dist" && tar czf "orca-$VERSION.tar.gz" "orca-$VERSION")
echo "built $ROOT/dist/orca-$VERSION.tar.gz"
