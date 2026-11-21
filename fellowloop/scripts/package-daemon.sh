#!/bin/sh
# Builds a self-contained daemon for this OS/architecture: a jlink'ed Java runtime + fellowloop-daemon.jar + launcher.
# Output: dist/fellowloop-daemon-<os>-<arch>.tar.gz. Build it once per target platform (or in CI on each runner).
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
OUT="$ROOT/dist/fellowloop-daemon-$OS-$ARCH"
(cd "$ROOT" && mvn -q -B package -DskipTests -pl fellowloop-daemon -am)
rm -rf "$OUT" && mkdir -p "$OUT/bin"
jlink --add-modules java.base,java.net.http,java.logging,java.naming,java.management,java.xml,java.sql,jdk.crypto.ec,jdk.unsupported,jdk.zipfs \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$OUT/runtime"
cp "$ROOT/fellowloop-daemon/target/fellowloop-daemon-exec.jar" "$OUT/fellowloop-daemon.jar"
cat > "$OUT/bin/fellowloop-daemon" <<'SH'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "$DIR/runtime/bin/java" ${FELLOWLOOP_DAEMON_JAVA_OPTS:-} -jar "$DIR/fellowloop-daemon.jar" "$@"
SH
chmod +x "$OUT/bin/fellowloop-daemon"
cp "$ROOT/dist-scripts/fellowloop-daemon.service" "$ROOT/LICENSE" "$ROOT/THIRD-PARTY-NOTICES.md" "$OUT/"
(cd "$ROOT/dist" && tar czf "fellowloop-daemon-$OS-$ARCH.tar.gz" "fellowloop-daemon-$OS-$ARCH")
echo "built $ROOT/dist/fellowloop-daemon-$OS-$ARCH.tar.gz"
