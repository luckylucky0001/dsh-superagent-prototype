#!/bin/sh
# Sets up this machine as an Fellowloop runtime: checks Java and git, helps install and log in to Codex CLI,
# downloads the daemon from your Fellowloop server, registers it, runs `fellowloop-daemon doctor` and (optionally)
# installs a systemd user service.
#
#   curl -fsSL https://fellowloop.example.com/downloads/install-runtime.sh | sh -s -- \
#       --server https://fellowloop.example.com --code fellowloop_r_...
#
# The whole script is one function called on the last line, so a download cut short runs nothing.
set -eu

usage() {
  cat <<'USAGE'
install-runtime.sh: set up this machine as an Fellowloop runtime

  --server URL        the Fellowloop server (required)
  --code CODE         a one-time registration code from Runtimes > Add runtime
                      (not needed when this machine is already registered)
  --name NAME         how the runtime is listed (default: this host's name)
  --dir DIR           where to install the daemon and the fellowloop CLI (default: ~/.fellowloop/bin)
  --install-codex     install Codex CLI with npm when it is missing
  --service           install and start a systemd user service (Linux)
  --allow-network     let agents that are allowed network access reach it from their sandbox
  --yes               answer yes to every question (install Codex, set up the service)
  --non-interactive   ask nothing and change nothing optional: only check, install the daemon and register
  -h, --help          show this help
USAGE
}

main() {
  SERVER=""; CODE=""; NAME=""; DIR="${HOME}/.fellowloop/bin"; INSTALL_CODEX=0; SERVICE=0; NETWORK=""; ANSWER=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --server) SERVER="${2:-}"; shift 2 ;;
      --code) CODE="${2:-}"; shift 2 ;;
      --name) NAME="${2:-}"; shift 2 ;;
      --dir) DIR="${2:-}"; shift 2 ;;
      --install-codex) INSTALL_CODEX=1; shift ;;
      --service) SERVICE=1; shift ;;
      --allow-network) NETWORK="--allow-network"; shift ;;
      --yes|-y) ANSWER=yes; shift ;;
      --non-interactive) ANSWER=no; shift ;;
      -h|--help) usage; return 0 ;;
      *) echo "unknown option: $1" >&2; usage >&2; return 2 ;;
    esac
  done

  [ -n "$SERVER" ] || die "--server is required, e.g. --server https://fellowloop.example.com"
  SERVER=$(printf '%s' "$SERVER" | sed 's:/*$::')
  case "$DIR" in /*) ;; *) DIR="$(pwd)/$DIR" ;; esac
  # the daemon's settings folder: say it explicitly, so the daemon, this script and the service agree
  FELLOWLOOP_HOME="${FELLOWLOOP_HOME:-$HOME/.fellowloop}"
  export FELLOWLOOP_HOME

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
  elif [ "$INSTALL_CODEX" = 1 ] || ask "Codex CLI is not installed. Install it with npm now?"; then
    command -v npm >/dev/null 2>&1 || die "npm is needed to install Codex CLI (install Node.js 20+), or install Codex another way"
    npm install -g @openai/codex || die "npm could not install Codex CLI (a global install may need sudo or an npm prefix you own)"
  else
    warn "Codex CLI is not installed; install it later with: npm install -g @openai/codex"
  fi
  if command -v codex >/dev/null 2>&1; then
    if codex login status >/dev/null 2>&1; then
      echo "    Codex CLI is logged in"
    elif have_tty && [ "$ANSWER" != no ] && ask "Codex CLI is not logged in. Log in now with a device code (works over SSH)?"; then
      # interactive even with --yes: it needs a person to enter the code in a browser
      codex login --device-auth < /dev/tty || warn "Codex login did not complete; run later: codex login --device-auth"
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
  TMP="$DIR/.fellowloop-daemon.jar.download"
  curl -fsSL "$SERVER/downloads/fellowloop-daemon.jar" -o "$TMP" || { rm -f "$TMP"; die "could not download $SERVER/downloads/fellowloop-daemon.jar"; }
  mv "$TMP" "$DIR/fellowloop-daemon.jar"
  cat > "$DIR/fellowloop-daemon" <<'SH'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java ${FELLOWLOOP_DAEMON_JAVA_OPTS:-} -jar "$DIR/fellowloop-daemon.jar" "$@"
SH
  chmod +x "$DIR/fellowloop-daemon"
  if curl -fsSL "$SERVER/downloads/fellowloop-cli.jar" -o "$DIR/.fellowloop-cli.jar.download" 2>/dev/null; then
    mv "$DIR/.fellowloop-cli.jar.download" "$DIR/fellowloop-cli.jar"
    cat > "$DIR/fellowloop" <<'SH'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java ${FELLOWLOOP_CLI_JAVA_OPTS:-} -jar "$DIR/fellowloop-cli.jar" "$@"
SH
    chmod +x "$DIR/fellowloop"
    echo "    also installed the fellowloop command-line client (sign in with your own token; keep it off shared build machines)"
  else
    rm -f "$DIR/.fellowloop-cli.jar.download"
  fi

  if [ -n "$CODE" ]; then
    say "Registering with $SERVER"
    if [ -n "$NAME" ]; then
      "$DIR/fellowloop-daemon" register --server "$SERVER" --code "$CODE" --name "$NAME"
    else
      "$DIR/fellowloop-daemon" register --server "$SERVER" --code "$CODE"
    fi
  fi

  say "Checking the setup"
  READY=1
  "$DIR/fellowloop-daemon" doctor $NETWORK || { READY=0; warn "fix the problems above, then run: $DIR/fellowloop-daemon doctor"; }

  CONFIG="$FELLOWLOOP_HOME/daemon.json"
  if [ "$SERVICE" = 1 ] || { [ "$(uname -s)" = Linux ] && command -v systemctl >/dev/null 2>&1 \
      && ask "Install a systemd user service so the daemon starts automatically?"; }; then
    if ! command -v systemctl >/dev/null 2>&1 || ! systemctl --user show-environment >/dev/null 2>&1; then
      # no systemd, or no user session bus (containers, some ssh sessions, WSL)
      warn "a systemd user session is not available here; start the daemon with: $DIR/fellowloop-daemon start $NETWORK"
    elif [ ! -s "$CONFIG" ]; then
      warn "this machine is not registered yet, so no service was installed; run again with --code"
    else
      install_service
    fi
  else
    say "Start the daemon with: $DIR/fellowloop-daemon start $NETWORK"
  fi
  case ":$PATH:" in *":$DIR:"*) ;; *) echo "    tip: add $DIR to your PATH" ;; esac
  [ "$READY" = 1 ] || return 1
}

install_service() {
  UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$UNIT_DIR"
  # systemd splits unquoted values on spaces and expands % specifiers: quote, and double every %
  SVC_PATH=$(printf '%s' "$PATH" | sed 's/%/%%/g; s/"/\\"/g')
  SVC_DIR=$(printf '%s' "$DIR" | sed 's/%/%%/g; s/"/\\"/g')
  SVC_HOME=$(printf '%s' "$FELLOWLOOP_HOME" | sed 's/%/%%/g; s/"/\\"/g')
  cat > "$UNIT_DIR/fellowloop-daemon.service" <<UNIT
[Unit]
Description=Fellowloop daemon
After=network-online.target

[Service]
Environment="PATH=$SVC_PATH"
Environment="FELLOWLOOP_HOME=$SVC_HOME"
ExecStart="$SVC_DIR/fellowloop-daemon" start $NETWORK
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now fellowloop-daemon
  echo "    started; logs: journalctl --user -u fellowloop-daemon -f"
  echo "    to keep it running after you log out: sudo loginctl enable-linger $(id -un)"
}

say() { printf '%s\n' "==> $*"; }
warn() { printf '%s\n' "  ! $*" >&2; }
die() { printf '%s\n' "  x $*" >&2; exit 1; }

# ask "question": --yes answers yes, --non-interactive (or no terminal) answers no, otherwise the person decides
# a terminal we can actually open: /dev/tty may exist and be readable but have no terminal behind it
# (CI, cron, ssh without -t), and then opening it fails
have_tty() {
  ( exec < /dev/tty ) 2>/dev/null
}

ask() {
  [ "$ANSWER" = yes ] && return 0
  [ "$ANSWER" = no ] && return 1
  have_tty || return 1
  printf '%s [y/N] ' "$1" > /dev/tty
  read -r reply < /dev/tty || return 1
  case "$reply" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

main "$@"
