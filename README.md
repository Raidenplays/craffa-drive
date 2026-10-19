# Craffa Drive

Your own self-hosted drive for the homelab — notes, photos and files, no cloud.
FastAPI + SQLite backend. Implements [`craffa-notes-api-spec.md`](../files/craffa-notes-api-spec.md) v0.1.

Python 3.10+.

## Install (one paste, any Linux distro)

```bash
curl -fsSL https://raw.githubusercontent.com/Raidenplays/craffa-drive/main/install.sh | bash
```

Needs only `git` and `python3` (>= 3.10) — no apt/dnf/pacman, so it works on any
distro. Installs to `/opt/craffa` (or `~/craffa` if `/opt` isn't
writable), creates a virtualenv, installs dependencies, and registers a systemd
service when systemd is present. Safe to re-run: it updates the checkout and deps.

## Manual install

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8471
```

On first run it prints your API token and saves it to `data/token.txt`
(override with `NOTES_TOKEN`, data dir with `NOTES_DATA_DIR`).

Talk to it (from any Tailscale device):

```bash
curl -H "Authorization: Bearer <token>" http://100.104.161.86:8471/api/v1/notes
```

Interactive docs: `http://100.104.161.86:8471/docs`

## Layout

- `app/main.py` — routes (notes, trash, folders, attachments)
- `app/database.py` — SQLite schema + connection
- `app/auth.py` — bearer-token auth, auto-generated on first run
- `app/schemas.py` — request bodies
- `app/doctext.py` — rich-text JSON → plaintext / title
- `data/` — sqlite db, attachments, token (created at runtime, not in git)

## Run on boot (homelab, systemd)

```ini
[Unit]
Description=Craffa
After=network.target

[Service]
User=raiden
WorkingDirectory=/opt/craffa
Environment=NOTES_DATA_DIR=/opt/craffa/data
ExecStart=/opt/craffa/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8471
Restart=always

[Install]
WantedBy=multi-user.target
```

## Auto-update

The server polls its git remote every few hours (default 4 — `AUTO_UPDATE_INTERVAL_HOURS`)
and fast-forwards when the remote branch (`AUTO_UPDATE_BRANCH`, default `main`) moves.
After applying an update it reinstalls dependencies and exits, so the supervisor
restarts it with the new code — that's why the systemd unit uses `Restart=always`.

- Only clean checkouts are updated; local changes block the pull.
- Disable with `AUTO_UPDATE_ENABLED=false`.
- Force a check anytime: `POST /api/v1/admin/update` (bearer token required).
- Does nothing until the code is pushed to GitHub (no remote = idle).

## What's next

- Android client (Kotlin + Compose, Material 3 Expressive) against this API
- Then: iPad (SwiftUI + Liquid Glass), Windows (WinUI 3 + Fluent), Linux (GTK4)
- Photos and files slices after notes proves out
