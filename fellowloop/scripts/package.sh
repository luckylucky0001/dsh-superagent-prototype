#!/bin/sh
# Builds a release: dist/fellowloop-<version>/{fellowloop-server.jar (with the web UI), daemon/, cli/, downloads/, deploy/}.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)
OUT="$ROOT/dist/fellowloop-$VERSION"
(cd "$ROOT/web" && npm ci --no-audit --no-fund && npm run build)
(cd "$ROOT" && mvn -q -B package -DskipTests)
rm -rf "$OUT" && mkdir -p "$OUT/daemon" "$OUT/cli" "$OUT/downloads"
cp "$ROOT/fellowloop-server/target/fellowloop-server.jar" "$OUT/"
cp "$ROOT/fellowloop-daemon/target/fellowloop-daemon-exec.jar" "$OUT/daemon/fellowloop-daemon.jar"
cp "$ROOT/dist-scripts/fellowloop-daemon" "$ROOT/dist-scripts/fellowloop-daemon.cmd" "$ROOT/dist-scripts/fellowloop-daemon.service" \
  "$ROOT/dist-scripts/install-runtime.sh" "$OUT/daemon/"
cp "$ROOT/fellowloop-cli/target/fellowloop-cli-exec.jar" "$OUT/cli/fellowloop-cli.jar"
cp "$ROOT/dist-scripts/fellowloop" "$ROOT/dist-scripts/fellowloop.cmd" "$OUT/cli/"
# what the server hands out at /downloads/ (set FELLOWLOOP_DOWNLOADS_DIR to this folder)
cp "$OUT/daemon/fellowloop-daemon.jar" "$OUT/cli/fellowloop-cli.jar" "$ROOT/dist-scripts/install-runtime.sh" "$OUT/downloads/"
cp -r "$ROOT/deploy" "$OUT/deploy"
cp "$ROOT/README.md" "$ROOT/LICENSE" "$ROOT/THIRD-PARTY-NOTICES.md" "$OUT/"
(cd "$ROOT/dist" && tar czf "fellowloop-$VERSION.tar.gz" "fellowloop-$VERSION")
echo "built $ROOT/dist/fellowloop-$VERSION.tar.gz"
