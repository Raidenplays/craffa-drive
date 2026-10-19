"""Migration test: a DB created by the pre-feature schema gets new columns
and a working FTS index after init_db(). Run with:
    cd server && PYTHONPATH=. .venv/bin/python tests/test_migration.py
"""
import os
import sqlite3
import tempfile

d = tempfile.mkdtemp(prefix="craffa-mig-")
os.environ["NOTES_DATA_DIR"] = d

# Build an old-schema DB by hand (pre-reminder_at/pinned/archived, no FTS).
conn = sqlite3.connect(os.path.join(d, "notes.db"))
conn.execute("""CREATE TABLE notes (
    id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL,
    plaintext TEXT NOT NULL DEFAULT '', folder_id TEXT,
    tags TEXT NOT NULL DEFAULT '[]', favorite INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT)""")
conn.execute("""CREATE TABLE revisions (
    note_id TEXT NOT NULL, revision INTEGER NOT NULL, body TEXT NOT NULL,
    updated_at TEXT NOT NULL, PRIMARY KEY (note_id, revision))""")
conn.execute("""CREATE TABLE folders (
    id TEXT PRIMARY KEY, name TEXT NOT NULL,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
conn.execute("""CREATE TABLE attachments (
    id TEXT PRIMARY KEY, note_id TEXT NOT NULL, filename TEXT NOT NULL,
    mime TEXT NOT NULL DEFAULT 'application/octet-stream',
    size INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)""")
conn.execute("INSERT INTO notes (id, title, body, plaintext, created_at, updated_at)"
             " VALUES ('old1', 'legacy note', '{}', 'some legacy words here', 't', 't')")
conn.commit()
conn.close()

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402
from app.auth import API_TOKEN  # noqa: E402

h = {"Authorization": f"Bearer {API_TOKEN}"}

with TestClient(app) as c:
    n = c.get("/api/v1/notes/old1", headers=h).json()
    assert n["pinned"] is False, n
    assert n["archived"] is False, n
    assert n["reminder_at"] is None, n
    print("  ok: old row readable with new defaults")

    got = c.get("/api/v1/notes", params={"q": "legacy"}, headers=h).json()["notes"]
    assert any(x["id"] == "old1" for x in got), f"FTS rebuild failed: {got}"
    print("  ok: FTS index rebuilt for pre-existing notes")

    # new writes still index via triggers
    r = c.post("/api/v1/notes",
               json={"body": {"blocks": [{"type": "paragraph",
                                          "spans": [{"text": "brand new words"}]}]}},
               headers=h)
    assert r.status_code == 201, r.text
    got = c.get("/api/v1/notes", params={"q": "brand"}, headers=h).json()["notes"]
    assert any(x["id"] == r.json()["id"] for x in got)
    print("  ok: trigger indexing works after migration")

print("\nMIGRATION TEST OK")
