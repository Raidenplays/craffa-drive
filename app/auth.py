"""Bearer-token auth. Single user: one long-lived token, generated on first run."""
import os
import secrets

from fastapi import Header, HTTPException

from .database import DATA_DIR

TOKEN_FILE = DATA_DIR / "token.txt"


def _load_or_create_token() -> str:
    env_token = os.environ.get("NOTES_TOKEN")
    if env_token:
        return env_token.strip()
    if TOKEN_FILE.exists():
        return TOKEN_FILE.read_text().strip()
    token = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(token)
    os.chmod(TOKEN_FILE, 0o600)
    print(f"[craffa] generated API token (also saved to {TOKEN_FILE}):\n{token}")
    return token


API_TOKEN = _load_or_create_token()


async def require_auth(authorization: str | None = Header(default=None)) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing bearer token")
    if authorization.removeprefix("Bearer ").strip() != API_TOKEN:
        raise HTTPException(401, "invalid token")
