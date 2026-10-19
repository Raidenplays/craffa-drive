"""Self-update: poll the git remote every few hours, fast-forward, restart.

Only activates when the code runs from a git checkout with an 'origin' remote
and a clean working tree. After a successful pull it exits so the process
supervisor (systemd with Restart=always) brings the new code up.
"""
import logging
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

log = logging.getLogger("craffa.updater")

REPO_DIR = Path(__file__).resolve().parent.parent


def _git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args], cwd=REPO_DIR, capture_output=True, text=True, timeout=120
    )


def _enabled() -> bool:
    return os.environ.get("AUTO_UPDATE_ENABLED", "true").lower() not in ("0", "false", "no")


def _interval_hours() -> float:
    try:
        return max(0.25, float(os.environ.get("AUTO_UPDATE_INTERVAL_HOURS", "4")))
    except ValueError:
        return 4.0


def _branch() -> str:
    return os.environ.get("AUTO_UPDATE_BRANCH", "main")


def remote_head_sha() -> str | None:
    """SHA of the remote branch HEAD, or None if unreachable / not a git repo."""
    if not (REPO_DIR / ".git").exists():
        return None
    proc = _git("ls-remote", "origin", _branch())
    if proc.returncode != 0 or not proc.stdout.strip():
        return None
    return proc.stdout.split()[0]


def local_head_sha() -> str | None:
    if not (REPO_DIR / ".git").exists():
        return None
    proc = _git("rev-parse", "HEAD")
    return proc.stdout.strip() if proc.returncode == 0 else None


def working_tree_clean() -> bool:
    proc = _git("status", "--porcelain")
    return proc.returncode == 0 and not proc.stdout.strip()


def check_for_update() -> dict:
    """Poll the remote. Returns {ok, update_available, local, remote, reason?}."""
    remote = remote_head_sha()
    if remote is None:
        return {"ok": False, "update_available": False,
                "reason": "no git remote reachable (push the code to GitHub first)"}
    local = local_head_sha()
    if local is None:
        return {"ok": False, "update_available": False, "reason": "no local HEAD"}
    return {"ok": True, "update_available": remote != local,
            "local": local, "remote": remote}


def apply_update() -> dict:
    """Fast-forward the checkout to the remote branch + reinstall deps."""
    if not working_tree_clean():
        return {"ok": False, "updated": False,
                "reason": "working tree not clean; skipping update"}
    proc = _git("pull", "--ff-only", "origin", _branch())
    if proc.returncode != 0:
        reason = proc.stderr.strip() or "git pull failed"
        log.error("auto-update: %s", reason)
        return {"ok": False, "updated": False, "reason": reason}
    req = REPO_DIR / "requirements.txt"
    if req.exists():
        pip = subprocess.run(
            [sys.executable, "-m", "pip", "install", "-q", "-r", str(req)],
            capture_output=True, text=True, timeout=600,
        )
        if pip.returncode != 0:
            reason = (pip.stderr.strip() or "pip install failed")[-500:]
            log.error("auto-update: %s", reason)
            return {"ok": False, "updated": False, "reason": reason}
    log.warning("auto-update: moved to %s", local_head_sha())
    return {"ok": True, "updated": True}


def check_and_maybe_update() -> dict:
    status = check_for_update()
    if not status.get("ok") or not status.get("update_available"):
        return status
    return {**status, **apply_update()}


def _loop() -> None:
    interval = _interval_hours()
    log.info("auto-update: polling origin/%s every %.2g h", _branch(), interval)
    while True:
        time.sleep(interval * 3600)
        try:
            result = check_and_maybe_update()
            if result.get("updated"):
                log.warning("auto-update: applied; exiting so the supervisor restarts new code")
                os._exit(0)  # systemd Restart=always brings the new code up
            elif not result.get("ok"):
                log.info("auto-update check: %s", result.get("reason"))
        except Exception:
            log.exception("auto-update check failed")


def start_background() -> None:
    if not _enabled():
        log.info("auto-update disabled (AUTO_UPDATE_ENABLED=false)")
        return
    if not (REPO_DIR / ".git").exists():
        log.info("auto-update idle: not running from a git checkout")
        return
    thread = threading.Thread(target=_loop, daemon=True, name="auto-updater")
    thread.start()
