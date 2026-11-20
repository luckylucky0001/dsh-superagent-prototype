#!/bin/sh
# Builds a self-contained daemon for this OS/architecture: a jlink'ed Java runtime + orca-daemon.jar + launcher.
# Output: dist/orca-daemon-<os>-<arch>.tar.gz. Build it once per target platform (or in CI on each runner).
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
OUT="$ROOT/dist/orca-daemon-$OS-$ARCH"
(cd "$ROOT" && mvn -q -B package -DskipTests -pl orca-daemon -am)
rm -rf "$OUT" && mkdir -p "$OUT/bin"
jlink --add-modules java.base,java.net.http,java.logging,java.naming,java.management,java.xml,java.sql,jdk.crypto.ec,jdk.unsupported,jdk.zipfs \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$OUT/runtime"
cp "$ROOT/orca-daemon/target/orca-daemon-exec.jar" "$OUT/orca-daemon.jar"
cat > "$OUT/bin/orca-daemon" <<'SH'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "$DIR/runtime/bin/java" ${ORCA_DAEMON_JAVA_OPTS:-} -jar "$DIR/orca-daemon.jar" "$@"
SH
chmod +x "$OUT/bin/orca-daemon"
cp "$ROOT/dist-scripts/orca-daemon.service" "$OUT/"
(cd "$ROOT/dist" && tar czf "orca-daemon-$OS-$ARCH.tar.gz" "orca-daemon-$OS-$ARCH")
echo "built $ROOT/dist/orca-daemon-$OS-$ARCH.tar.gz"
