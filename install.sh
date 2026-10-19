#!/usr/bin/env bash
#
# One-paste installer for the Notes API server.
# Distro-agnostic: only needs git and python3 (>= 3.10). No apt/dnf/pacman.
#
#   curl -fsSL https://raw.githubusercontent.com/Raidenplays/craffa-drive/main/install.sh | bash
#
# Re-running it updates the checkout and dependencies.
set -euo pipefail

REPO_URL="https://github.com/Raidenplays/craffa-drive.git"
BRANCH="${BRANCH:-main}"
PORT="${PORT:-8471}"
DEST="${NOTES_DEST:-/opt/craffa}"

log() { printf '\033[1m[craffa]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[craffa] error:\033[0m %s\n' "$*" >&2; exit 1; }

# --- prerequisites (git + python3 only; everything else is pip/venv) ------------
command -v git >/dev/null 2>&1 \
  || die "git not found. Install it with your distro's package manager, then re-run this."
command -v python3 >/dev/null 2>&1 \
  || die "python3 not found. Install Python 3.10+ with your distro's package manager, then re-run this."
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null \
  || die "python3 is too old — need 3.10 or newer."
python3 -m venv --help >/dev/null 2>&1 \
  || die "python3 venv module missing. Install your distro's python3-venv package, then re-run this."

# --- install dir ----------------------------------------------------------------
if [ ! -d "$DEST" ]; then
  if mkdir -p "$DEST" 2>/dev/null; then
    :
  elif command -v sudo >/dev/null 2>&1; then
    log "$DEST not writable, using sudo for setup"
    sudo mkdir -p "$DEST"
    sudo chown -R "$(id -un)" "$DEST"
  else
    DEST="$HOME/craffa"
    log "/opt not writable and no sudo; installing to $DEST"
    mkdir -p "$DEST"
  fi
fi
[ -w "$DEST" ] || die "$DEST is not writable."

# --- code -----------------------------------------------------------------------
if [ -d "$DEST/.git" ]; then
  log "updating existing checkout in $DEST"
  git -C "$DEST" fetch -q origin
  git -C "$DEST" checkout -q "$BRANCH"
  git -C "$DEST" pull -q --ff-only origin "$BRANCH" \
    || die "git pull failed (local changes? commit or stash them first)"
else
  log "cloning into $DEST"
  git clone -q --branch "$BRANCH" "$REPO_URL" "$DEST" || die "git clone failed"
fi

# --- venv + dependencies ----------------------------------------------------------
if [ ! -x "$DEST/.venv/bin/python" ]; then
  log "creating virtualenv"
  python3 -m venv "$DEST/.venv"
fi
log "installing dependencies"
"$DEST/.venv/bin/pip" install -q -r "$DEST/requirements.txt"

# --- systemd service (only if systemd is present) ----------------------------------
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  if [ -w /etc/systemd/system ]; then
    SUDO=""
  elif command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
  else
    SUDO="unwritable"
  fi
  if [ "$SUDO" != "unwritable" ]; then
    log "installing systemd service"
    $SUDO tee /etc/systemd/system/craffa.service >/dev/null <<EOF
[Unit]
Description=Craffa (home-server notes, photos and files)
After=network.target

[Service]
User=$(id -un)
WorkingDirectory=$DEST
Environment=NOTES_DATA_DIR=$DEST/data
Environment=PORT=$PORT
ExecStart=$DEST/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port $PORT
Restart=always

[Install]
WantedBy=multi-user.target
EOF
    $SUDO systemctl daemon-reload
    $SUDO systemctl enable -q --now craffa
  else
    log "systemd found but /etc/systemd/system not writable; skipping service install"
  fi
fi

# --- done -------------------------------------------------------------------------
echo
log "installed to $DEST"
if systemctl is-active -q craffa 2>/dev/null; then
  IP=$(hostname -I 2>/dev/null | awk '{print $1}')
  log "service craffa is running: http://${IP:-<this-host>}:$PORT"
  log "your API token is in $DEST/data/token.txt (printed on first run)"
else
  log "start it manually:"
  log "  $DEST/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port $PORT"
  log "first run prints your API token (also saved to $DEST/data/token.txt)"
fi
log "auto-update is ON: the server polls GitHub every 4h (AUTO_UPDATE_INTERVAL_HOURS)"
