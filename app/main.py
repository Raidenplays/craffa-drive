"""Notes API — FastAPI server for the home-server notes app (spec v0.1)."""
import hashlib
import json
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, File, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from . import updater
from .auth import require_auth
from .database import ATTACH_DIR, get_conn, init_db, lock
from .doctext import derive_title, extract_plaintext, validate_doc
from .schemas import FolderCreate, FolderUpdate, NoteCreate, NotePatch, NoteUpdate

VERSION = "0.1.0"


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    updater.start_background()
    yield


app = FastAPI(title="Craffa", version=VERSION, lifespan=lifespan)

# The box is only reachable over Tailscale, so permissive CORS is fine here.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

CODE_FOR_STATUS = {
    400: "bad_request",
    401: "unauthorized",
    404: "not_found",
    409: "revision_conflict",
    413: "too_large",
    422: "invalid_body",
}


@app.exception_handler(HTTPException)
async def http_error_handler(request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": {
            "code": CODE_FOR_STATUS.get(exc.status_code, "error"),
            "message": str(exc.detail),
        }},
    )


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def note_to_dict(row) -> dict:
    return {
        "id": row["id"],
        "title": row["title"],
        "body": json.loads(row["body"]),
        "plaintext": row["plaintext"],
        "folder_id": row["folder_id"],
        "tags": json.loads(row["tags"]),
        "favorite": bool(row["favorite"]),
        "revision": row["revision"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "deleted_at": row["deleted_at"],
    }


def check_body(body: dict) -> None:
    err = validate_doc(body)
    if err:
        raise HTTPException(422, err)


def _like_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _conflict(nid: str, row, base_revision: int):
    return JSONResponse(status_code=409, content={
        "error": {"code": "revision_conflict",
                  "message": f"base_revision {base_revision}"
                             f" does not match server revision {row['revision']}"},
        "server_copy": note_to_dict(row),
    })


def _get_live_note(nid: str):
    row = get_conn().execute("SELECT * FROM notes WHERE id=?", (nid,)).fetchone()
    if row is None or row["deleted_at"] is not None:
        raise HTTPException(404, "note not found")
    return row


# ---------------------------------------------------------------- health

@app.get("/health")
def health():
    return {"ok": True, "version": VERSION, "api_version": "0.1"}


@app.post("/api/v1/admin/update", dependencies=[Depends(require_auth)])
def admin_update():
    """Check the git remote now; pull and report if an update is available.

    When run under a supervisor (systemd Restart=always), restart the service
    after an update to run the new code.
    """
    return updater.check_and_maybe_update()


# ---------------------------------------------------------------- notes

@app.get("/api/v1/notes", dependencies=[Depends(require_auth)])
def list_notes(
    since: str | None = Query(default=None),
    folder_id: str | None = Query(default=None),
    q: str | None = Query(default=None),
    favorite: bool | None = Query(default=None),
    limit: int = Query(default=50, le=200),
    cursor: int = Query(default=0, ge=0),
):
    sql = "SELECT * FROM notes WHERE deleted_at IS NULL"
    args: list = []
    if since:
        sql += " AND updated_at > ?"
        args.append(since)
    if folder_id:
        sql += " AND folder_id = ?"
        args.append(folder_id)
    if q:
        like = f"%{_like_escape(q)}%"
        sql += " AND (title LIKE ? ESCAPE '\\' OR plaintext LIKE ? ESCAPE '\\')"
        args += [like, like]
    if favorite is not None:
        sql += " AND favorite = ?"
        args.append(1 if favorite else 0)
    sql += " ORDER BY updated_at DESC LIMIT ? OFFSET ?"
    args += [limit + 1, cursor]
    with lock:
        rows = get_conn().execute(sql, args).fetchall()
    notes = [note_to_dict(r) for r in rows[:limit]]
    next_cursor = cursor + limit if len(rows) > limit else None
    return {"notes": notes, "next_cursor": next_cursor}


@app.get("/api/v1/notes/trash", dependencies=[Depends(require_auth)])
def trash(
    since: str | None = Query(default=None),
    limit: int = Query(default=50, le=200),
    cursor: int = Query(default=0, ge=0),
):
    sql = "SELECT * FROM notes WHERE deleted_at IS NOT NULL"
    args: list = []
    if since:
        sql += " AND deleted_at > ?"
        args.append(since)
    sql += " ORDER BY deleted_at DESC LIMIT ? OFFSET ?"
    args += [limit + 1, cursor]
    with lock:
        rows = get_conn().execute(sql, args).fetchall()
    notes = [note_to_dict(r) for r in rows[:limit]]
    next_cursor = cursor + limit if len(rows) > limit else None
    return {"notes": notes, "next_cursor": next_cursor}


@app.post("/api/v1/notes", dependencies=[Depends(require_auth)], status_code=201)
def create_note(payload: NoteCreate):
    check_body(payload.body)
    nid = uuid.uuid4().hex
    now = utcnow()
    title = payload.title or derive_title(payload.body)
    with lock:
        conn = get_conn()
        conn.execute(
            "INSERT INTO notes (id, title, body, plaintext, folder_id, tags,"
            " favorite, revision, created_at, updated_at, deleted_at)"
            " VALUES (?, ?, ?, ?, ?, ?, 0, 1, ?, ?, NULL)",
            (nid, title, json.dumps(payload.body), extract_plaintext(payload.body),
             payload.folder_id, json.dumps(payload.tags), now, now),
        )
        conn.execute(
            "INSERT INTO revisions (note_id, revision, body, updated_at) VALUES (?, 1, ?, ?)",
            (nid, json.dumps(payload.body), now),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM notes WHERE id=?", (nid,)).fetchone()
    return note_to_dict(row)


@app.get("/api/v1/notes/{nid}", dependencies=[Depends(require_auth)])
def get_note(nid: str):
    with lock:
        return note_to_dict(_get_live_note(nid))


@app.put("/api/v1/notes/{nid}", dependencies=[Depends(require_auth)])
def update_note(nid: str, payload: NoteUpdate):
    check_body(payload.body)
    now = utcnow()
    with lock:
        conn = get_conn()
        row = _get_live_note(nid)
        if row["revision"] != payload.base_revision:
            return _conflict(nid, row, payload.base_revision)
        # Omitted metadata fields keep their current values (never wiped).
        fs = payload.model_fields_set
        title = payload.title if "title" in fs else row["title"]
        if title is None:
            title = derive_title(payload.body)
        folder_id = payload.folder_id if "folder_id" in fs else row["folder_id"]
        tags = payload.tags if "tags" in fs else json.loads(row["tags"])
        favorite = payload.favorite if "favorite" in fs else bool(row["favorite"])
        new_rev = row["revision"] + 1
        conn.execute(
            "UPDATE notes SET title=?, body=?, plaintext=?, folder_id=?, tags=?,"
            " favorite=?, revision=?, updated_at=? WHERE id=?",
            (title, json.dumps(payload.body), extract_plaintext(payload.body),
             folder_id, json.dumps(tags),
             1 if favorite else 0, new_rev, now, nid),
        )
        conn.execute(
            "INSERT INTO revisions (note_id, revision, body, updated_at) VALUES (?, ?, ?, ?)",
            (nid, new_rev, json.dumps(payload.body), now),
        )
        conn.execute(
            "DELETE FROM revisions WHERE note_id=? AND revision <="
            " (SELECT COALESCE(MAX(revision), 0) - 50 FROM revisions WHERE note_id=?)",
            (nid, nid),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM notes WHERE id=?", (nid,)).fetchone()
    return note_to_dict(row)


@app.patch("/api/v1/notes/{nid}", dependencies=[Depends(require_auth)])
def patch_note(nid: str, payload: NotePatch):
    now = utcnow()
    with lock:
        conn = get_conn()
        row = _get_live_note(nid)
        if payload.base_revision is not None and row["revision"] != payload.base_revision:
            return _conflict(nid, row, payload.base_revision)
        fs = payload.model_fields_set
        fields: list[str] = []
        args: list = []
        if "title" in fs and payload.title is not None:
            fields.append("title=?")
            args.append(payload.title)
        if "folder_id" in fs:
            # explicit null clears the folder; omitted keeps it
            fields.append("folder_id=?")
            args.append(payload.folder_id)
        if "tags" in fs and payload.tags is not None:
            fields.append("tags=?")
            args.append(json.dumps(payload.tags))
        if "favorite" in fs and payload.favorite is not None:
            fields.append("favorite=?")
            args.append(1 if payload.favorite else 0)
        if not fields:
            raise HTTPException(400, "nothing to update")
        new_rev = row["revision"] + 1
        fields += ["revision=?", "updated_at=?"]
        args += [new_rev, now, nid]
        conn.execute(f"UPDATE notes SET {', '.join(fields)} WHERE id=?", args)
        conn.commit()
        row = conn.execute("SELECT * FROM notes WHERE id=?", (nid,)).fetchone()
    return note_to_dict(row)


@app.delete("/api/v1/notes/{nid}", dependencies=[Depends(require_auth)])
def delete_note(nid: str, hard: bool = Query(default=False)):
    now = utcnow()
    with lock:
        conn = get_conn()
        row = conn.execute("SELECT * FROM notes WHERE id=?", (nid,)).fetchone()
        if row is None:
            raise HTTPException(404, "note not found")
        if hard:
            for att in conn.execute("SELECT * FROM attachments WHERE note_id=?", (nid,)):
                (ATTACH_DIR / f"{att['id']}_{att['filename']}").unlink(missing_ok=True)
            conn.execute("DELETE FROM attachments WHERE note_id=?", (nid,))
            conn.execute("DELETE FROM revisions WHERE note_id=?", (nid,))
            conn.execute("DELETE FROM notes WHERE id=?", (nid,))
        elif row["deleted_at"] is None:
            conn.execute(
                "UPDATE notes SET deleted_at=?, updated_at=?, revision=revision+1 WHERE id=?",
                (now, now, nid),
            )
        conn.commit()
    return {"ok": True}


@app.post("/api/v1/notes/{nid}/restore", dependencies=[Depends(require_auth)])
def restore_note(nid: str):
    now = utcnow()
    with lock:
        conn = get_conn()
        row = conn.execute("SELECT * FROM notes WHERE id=?", (nid,)).fetchone()
        if row is None or row["deleted_at"] is None:
            raise HTTPException(404, "note not in trash")
        conn.execute(
            "UPDATE notes SET deleted_at=NULL, updated_at=?, revision=revision+1 WHERE id=?",
            (now, nid),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM notes WHERE id=?", (nid,)).fetchone()
    return note_to_dict(row)


# ---------------------------------------------------------------- folders

@app.get("/api/v1/folders", dependencies=[Depends(require_auth)])
def list_folders():
    with lock:
        rows = get_conn().execute("SELECT * FROM folders ORDER BY name").fetchall()
    return {"folders": [
        {"id": r["id"], "name": r["name"], "parent_id": r["parent_id"],
         "created_at": r["created_at"], "updated_at": r["updated_at"]} for r in rows
    ]}


@app.post("/api/v1/folders", dependencies=[Depends(require_auth)], status_code=201)
def create_folder(payload: FolderCreate):
    fid, now = uuid.uuid4().hex, utcnow()
    with lock:
        conn = get_conn()
        conn.execute(
            "INSERT INTO folders (id, name, parent_id, created_at, updated_at)"
            " VALUES (?, ?, ?, ?, ?)",
            (fid, payload.name, payload.parent_id, now, now),
        )
        conn.commit()
    return {"id": fid, "name": payload.name, "parent_id": payload.parent_id,
            "created_at": now, "updated_at": now}


@app.delete("/api/v1/folders/{fid}", dependencies=[Depends(require_auth)])
def delete_folder(fid: str):
    now = utcnow()
    with lock:
        conn = get_conn()
        if conn.execute("SELECT id FROM folders WHERE id=?", (fid,)).fetchone() is None:
            raise HTTPException(404, "folder not found")
        # Notes keep working: unfile them AND bump their revision so a stale
        # client PUT can't silently restore them into the deleted folder.
        conn.execute(
            "UPDATE notes SET folder_id=NULL, updated_at=?, revision=revision+1"
            " WHERE folder_id=?", (now, fid))
        conn.execute("UPDATE folders SET parent_id=NULL WHERE parent_id=?", (fid,))
        conn.execute("DELETE FROM folders WHERE id=?", (fid,))
        conn.commit()
    return {"ok": True}


@app.put("/api/v1/folders/{fid}", dependencies=[Depends(require_auth)])
def rename_folder(fid: str, payload: FolderUpdate):
    now = utcnow()
    with lock:
        conn = get_conn()
        row = conn.execute("SELECT * FROM folders WHERE id=?", (fid,)).fetchone()
        if row is None:
            raise HTTPException(404, "folder not found")
        conn.execute("UPDATE folders SET name=?, updated_at=? WHERE id=?",
                     (payload.name, now, fid))
        conn.commit()
        row = conn.execute("SELECT * FROM folders WHERE id=?", (fid,)).fetchone()
    return {"id": row["id"], "name": row["name"], "parent_id": row["parent_id"],
            "created_at": row["created_at"], "updated_at": row["updated_at"]}


# ---------------------------------------------------------------- attachments

@app.post("/api/v1/attachments", dependencies=[Depends(require_auth)], status_code=201)
async def upload_attachment(
    file: UploadFile = File(...),
    note_id: str | None = Query(default=None),
):
    aid = uuid.uuid4().hex
    safe_name = Path(file.filename or "upload").name
    dest = ATTACH_DIR / f"{aid}_{safe_name}"
    sha = hashlib.sha256()
    size = 0
    with dest.open("wb") as f:
        while chunk := await file.read(1024 * 1024):
            sha.update(chunk)
            size += len(chunk)
            f.write(chunk)
    now = utcnow()
    with lock:
        conn = get_conn()
        conn.execute(
            "INSERT INTO attachments (id, note_id, filename, mime, size, sha256, created_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?)",
            (aid, note_id, safe_name, file.content_type or "application/octet-stream",
             size, sha.hexdigest(), now),
        )
        conn.commit()
    return {"id": aid, "url": f"/api/v1/attachments/{aid}/file",
            "filename": safe_name, "size": size}


@app.get("/api/v1/attachments/{aid}/file", dependencies=[Depends(require_auth)])
def download_attachment(aid: str):
    with lock:
        row = get_conn().execute("SELECT * FROM attachments WHERE id=?", (aid,)).fetchone()
    if row is None:
        raise HTTPException(404, "attachment not found")
    path = ATTACH_DIR / f"{aid}_{row['filename']}"
    if not path.exists():
        raise HTTPException(404, "file missing on disk")
    return FileResponse(path, media_type=row["mime"], filename=row["filename"])


@app.delete("/api/v1/attachments/{aid}", dependencies=[Depends(require_auth)])
def delete_attachment(aid: str):
    with lock:
        conn = get_conn()
        row = conn.execute("SELECT * FROM attachments WHERE id=?", (aid,)).fetchone()
        if row is None:
            raise HTTPException(404, "attachment not found")
        (ATTACH_DIR / f"{aid}_{row['filename']}").unlink(missing_ok=True)
        conn.execute("DELETE FROM attachments WHERE id=?", (aid,))
        conn.commit()
    return {"ok": True}
