"""SQLite storage for the single-user MVP. One shared connection + a thread lock."""
import os
import sqlite3
import threading
from pathlib import Path

DATA_DIR = Path(os.environ.get("NOTES_DATA_DIR", Path(__file__).resolve().parent.parent / "data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
ATTACH_DIR = DATA_DIR / "attachments"
ATTACH_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = DATA_DIR / "notes.db"

lock = threading.Lock()
_conn: sqlite3.Connection | None = None

SCHEMA = """
CREATE TABLE IF NOT EXISTS folders (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS notes (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    plaintext TEXT NOT NULL DEFAULT '',
    folder_id TEXT,
    tags TEXT NOT NULL DEFAULT '[]',
    favorite INTEGER NOT NULL DEFAULT 0,
    pinned INTEGER NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    deleted_at TEXT,
    reminder_at TEXT
);
CREATE TABLE IF NOT EXISTS attachments (
    id TEXT PRIMARY KEY,
    note_id TEXT,
    filename TEXT NOT NULL,
    mime TEXT NOT NULL DEFAULT 'application/octet-stream',
    size INTEGER NOT NULL DEFAULT 0,
    sha256 TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS revisions (
    note_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    body TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (note_id, revision)
);
-- Full-text search index over notes (kept in sync by triggers below).
CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(title, plaintext, content='notes', content_rowid='rowid');
CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
  INSERT INTO notes_fts(rowid, title, plaintext) VALUES (new.rowid, new.title, new.plaintext);
END;
CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE ON notes BEGIN
  INSERT INTO notes_fts(notes_fts, rowid, title, plaintext) VALUES ('delete', old.rowid, old.title, old.plaintext);
  INSERT INTO notes_fts(rowid, title, plaintext) VALUES (new.rowid, new.title, new.plaintext);
END;
CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
  INSERT INTO notes_fts(notes_fts, rowid, title, plaintext) VALUES ('delete', old.rowid, old.title, old.plaintext);
END;
"""


def get_conn() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        _conn = sqlite3.connect(DB_PATH, check_same_thread=False)
        _conn.row_factory = sqlite3.Row
    return _conn


def init_db() -> None:
    conn = get_conn()
    with lock:
        had_fts = conn.execute(
            "SELECT name FROM sqlite_master WHERE name='notes_fts'"
        ).fetchone()
        conn.executescript(SCHEMA)
        # lightweight migrations for existing DBs
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(notes)").fetchall()}
        for col, ddl in (("reminder_at", "TEXT"),
                         ("pinned", "INTEGER NOT NULL DEFAULT 0"),
                         ("archived", "INTEGER NOT NULL DEFAULT 0")):
            if col not in cols:
                conn.execute(f"ALTER TABLE notes ADD COLUMN {col} {ddl}")
        if not had_fts:
            # FTS table is new: index any notes that pre-date it.
            # (COUNT(*) on an external-content FTS table mirrors the content
            # table, so it can't be used to detect an empty index.)
            conn.execute("INSERT INTO notes_fts(notes_fts) VALUES('rebuild')")
        conn.commit()
