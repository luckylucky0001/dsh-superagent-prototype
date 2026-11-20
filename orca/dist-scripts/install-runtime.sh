#!/bin/sh
# Sets up this machine as an Orca runtime: checks Java and git, helps install and log in to Codex CLI,
# downloads the daemon from your Orca server, registers it, runs `orca-daemon doctor` and (optionally)
# installs a systemd user service.
#
#   curl -fsSL https://orca.example.com/downloads/install-runtime.sh | sh -s -- \
#       --server https://orca.example.com --code orca_r_...
#
# Options:
#   --server URL       the Orca server (required)
#   --code CODE        a one-time registration code from Runtimes > Add runtime (required unless already registered)
#   --name NAME        how the runtime is listed (default: this host's name)
#   --dir DIR          where to install the daemon (default: ~/.orca/bin)
#   --install-codex    install Codex CLI with npm when it is missing
#   --service          install and start a systemd user service (Linux)
#   --allow-network    let agents that are allowed network access reach it from their sandbox
#   --yes              do not ask questions
set -eu

SERVER=""; CODE=""; NAME=""; DIR="${HOME}/.orca/bin"; INSTALL_CODEX=0; SERVICE=0; NETWORK=""; YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="${2:-}"; shift 2 ;;
    --code) CODE="${2:-}"; shift 2 ;;
    --name) NAME="${2:-}"; shift 2 ;;
    --dir) DIR="${2:-}"; shift 2 ;;
    --install-codex) INSTALL_CODEX=1; shift ;;
    --service) SERVICE=1; shift ;;
    --allow-network) NETWORK="--allow-network"; shift ;;
    --yes|-y) YES=1; shift ;;
    -h|--help) sed -n '2,19p' "$0" 2>/dev/null || true; exit 0 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done

say() { printf '%s\n' "==> $*"; }
warn() { printf '%s\n' "  ! $*" >&2; }
die() { printf '%s\n' "  x $*" >&2; exit 1; }
ask() {
  # ask "question" -> 0 for yes; with --yes or no terminal the answer is no unless forced by a flag
  [ "$YES" = 1 ] && return 1
  [ -r /dev/tty ] || return 1
  printf '%s [y/N] ' "$1" > /dev/tty
  read -r reply < /dev/tty || return 1
  case "$reply" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

[ -n "$SERVER" ] || die "--server is required, e.g. --server https://orca.example.com"
SERVER=$(printf '%s' "$SERVER" | sed 's:/*$::')

say "Checking Java and git"
command -v java >/dev/null 2>&1 || die "Java 21 or newer is required: install a JDK 21 (e.g. Eclipse Temurin) and try again"
JV=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' | head -1)
case "$JV" in
  ''|1.*) die "could not read the Java version, or it is too old ($JV); Java 21 or newer is required" ;;
esac
[ "$JV" -ge 21 ] 2>/dev/null || die "Java $JV found; Java 21 or newer is required"
echo "    Java $JV"
command -v git >/dev/null 2>&1 || die "git is required: install it and try again"
echo "    $(git --version)"

say "Checking agent CLIs"
if command -v codex >/dev/null 2>&1; then
  echo "    Codex CLI $(codex --version 2>/dev/null | head -1)"
else
  if [ "$INSTALL_CODEX" = 1 ] || ask "Codex CLI is not installed. Install it with npm now?"; then
    command -v npm >/dev/null 2>&1 || die "npm is needed to install Codex CLI (install Node.js 20+), or install Codex another way"
    npm install -g @openai/codex
  else
    warn "Codex CLI is not installed; install it later with: npm install -g @openai/codex"
  fi
fi
if command -v codex >/dev/null 2>&1; then
  if codex login status >/dev/null 2>&1; then
    echo "    Codex CLI is logged in"
  elif ask "Codex CLI is not logged in. Log in now with a device code (works over SSH)?"; then
    codex login --device-auth < /dev/tty
  else
    warn "log in later with: codex login --device-auth   (or: printenv OPENAI_API_KEY | codex login --with-api-key)"
  fi
fi
if command -v traecli >/dev/null 2>&1; then
  echo "    TRAE CLI found; if it is not logged in, run: traecli login"
else
  echo "    TRAE CLI not found (optional): see the TRAE documentation to install TRAE CLI 2.x"
fi

say "Installing the daemon into $DIR"
mkdir -p "$DIR"
TMP="$DIR/.orca-daemon.jar.download"
curl -fsSL "$SERVER/downloads/orca-daemon.jar" -o "$TMP" || die "could not download $SERVER/downloads/orca-daemon.jar"
mv "$TMP" "$DIR/orca-daemon.jar"
cat > "$DIR/orca-daemon" <<'SH'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java ${ORCA_DAEMON_JAVA_OPTS:-} -jar "$DIR/orca-daemon.jar" "$@"
SH
chmod +x "$DIR/orca-daemon"
if curl -fsSL "$SERVER/downloads/orca-cli.jar" -o "$DIR/.orca-cli.jar.download" 2>/dev/null; then
  mv "$DIR/.orca-cli.jar.download" "$DIR/orca-cli.jar"
  cat > "$DIR/orca" <<'SH'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java ${ORCA_CLI_JAVA_OPTS:-} -jar "$DIR/orca-cli.jar" "$@"
SH
  chmod +x "$DIR/orca"
  echo "    also installed the orca command-line client"
else
  rm -f "$DIR/.orca-cli.jar.download"
fi

if [ -n "$CODE" ]; then
  say "Registering with $SERVER"
  if [ -n "$NAME" ]; then
    "$DIR/orca-daemon" register --server "$SERVER" --code "$CODE" --name "$NAME"
  else
    "$DIR/orca-daemon" register --server "$SERVER" --code "$CODE"
  fi
fi

say "Checking the setup"
"$DIR/orca-daemon" doctor $NETWORK || warn "fix the problems above, then run: $DIR/orca-daemon doctor"

if [ "$SERVICE" = 1 ] || { [ "$(uname -s)" = Linux ] && command -v systemctl >/dev/null 2>&1 \
    && ask "Install a systemd user service so the daemon starts automatically?"; }; then
  UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT_DIR/orca-daemon.service" <<UNIT
[Unit]
Description=Orca daemon
After=network-online.target

[Service]
Environment=PATH=$PATH
ExecStart=$DIR/orca-daemon start $NETWORK
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now orca-daemon
  echo "    started; logs: journalctl --user -u orca-daemon -f"
  echo "    to keep it running after you log out: sudo loginctl enable-linger $(id -un)"
else
  say "Start the daemon with: $DIR/orca-daemon start $NETWORK"
fi
case ":$PATH:" in *":$DIR:"*) ;; *) echo "    tip: add $DIR to your PATH" ;; esac
