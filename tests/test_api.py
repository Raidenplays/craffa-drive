"""Comprehensive API test for the Craffa notes server. Run with:
    cd server && PYTHONPATH=. .venv/bin/python tests/test_api.py
Covers: CRUD, revisions guards, trash, folders, attachments, search (FTS5),
reminders, checklists, pinned/archived, revision history.
"""
import os
import tempfile

d = tempfile.mkdtemp(prefix="craffa-test-")
os.environ["NOTES_DATA_DIR"] = d

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402
from app.auth import API_TOKEN  # noqa: E402

h = {"Authorization": f"Bearer {API_TOKEN}"}
noauth = {}
P = lambda t: {"blocks": [{"type": "paragraph", "spans": [{"text": t}]}]}  # noqa: E731


def check(name, cond, extra=""):
    assert cond, f"FAIL [{name}] {extra}"
    print(f"  ok: {name}")


with TestClient(app) as c:
    print("== auth ==")
    check("401 without token", c.get("/api/v1/notes").status_code == 401)
    check("health has api_version",
          c.get("/health", headers=h).json()["api_version"] == "0.1")

    print("== notes CRUD ==")
    r = c.post("/api/v1/notes", json={"body": P("hello world")}, headers=h)
    check("create 201", r.status_code == 201, r.text)
    n = r.json()
    check("title derived", n["title"] == "hello world", n)
    check("revision starts at 1", n["revision"] == 1)
    nid = n["id"]

    r = c.get(f"/api/v1/notes/{nid}", headers=h)
    check("get note", r.status_code == 200 and r.json()["id"] == nid)
    check("404 unknown", c.get("/api/v1/notes/nope", headers=h).status_code == 404)

    r = c.put(f"/api/v1/notes/{nid}",
              json={"body": P("v2 text"), "base_revision": 1,
                    "favorite": True, "tags": ["a"]}, headers=h)
    check("put ok", r.status_code == 200 and r.json()["revision"] == 2, r.text)
    check("put sets fields", r.json()["favorite"] is True)

    r = c.put(f"/api/v1/notes/{nid}",
              json={"body": P("stale"), "base_revision": 1}, headers=h)
    check("put 409 on stale revision", r.status_code == 409, r.text)
    check("409 body has server_copy",
          r.json().get("server_copy", {}).get("id") == nid
          and r.json()["error"]["code"] == "revision_conflict", r.text)

    # PUT with omitted metadata keeps current values (never wiped)
    r = c.put(f"/api/v1/notes/{nid}",
              json={"body": P("v3 text"), "base_revision": 2}, headers=h)
    b = r.json()
    check("put keeps favorite", b["favorite"] is True, b)
    check("put keeps tags", b["tags"] == ["a"], b)

    print("== patch ==")
    r = c.patch(f"/api/v1/notes/{nid}", json={"title": "renamed"}, headers=h)
    check("patch title", r.json()["title"] == "renamed")
    check("patch keeps body", r.json()["plaintext"] == "v3 text")
    r = c.patch(f"/api/v1/notes/{nid}", json={}, headers=h)
    check("patch empty -> 400", r.status_code == 400)
    cur = c.get(f"/api/v1/notes/{nid}", headers=h).json()["revision"]
    r = c.patch(f"/api/v1/notes/{nid}",
                json={"favorite": False, "base_revision": cur - 1}, headers=h)
    check("patch 409 on stale base_revision", r.status_code == 409)

    print("== folders ==")
    f = c.post("/api/v1/folders", json={"name": "work"}, headers=h).json()
    fid = f["id"]
    check("folder created", f["name"] == "work")
    r = c.put(f"/api/v1/folders/{fid}", json={"name": "work2"}, headers=h)
    check("folder rename", r.json()["name"] == "work2", r.text)
    c.patch(f"/api/v1/notes/{nid}", json={"folder_id": fid}, headers=h)
    cur = c.get(f"/api/v1/notes/{nid}", headers=h).json()["revision"]
    # deleting a folder clears it from notes and bumps their revisions
    c.delete(f"/api/v1/folders/{fid}", headers=h)
    nn = c.get(f"/api/v1/notes/{nid}", headers=h).json()
    check("folder delete clears folder_id", nn["folder_id"] is None, nn)
    check("folder delete bumps revision", nn["revision"] == cur + 1, nn)
    # explicit null clears folder
    f2 = c.post("/api/v1/folders", json={"name": "tmp"}, headers=h).json()
    c.patch(f"/api/v1/notes/{nid}", json={"folder_id": f2["id"]}, headers=h)
    r = c.patch(f"/api/v1/notes/{nid}", json={"folder_id": None}, headers=h)
    check("patch null clears folder", r.json()["folder_id"] is None)

    print("== doc validation ==")
    bad1 = {"blocks": [{"type": "nonsense", "spans": [{"text": "x"}]}]}
    check("bad block type 422",
          c.post("/api/v1/notes", json={"body": bad1}, headers=h).status_code == 422)
    bad2 = {"blocks": [{"type": "paragraph", "spans": [{"text": "x", "marks": ["bogus"]}]}]}
    check("bad mark 422",
          c.post("/api/v1/notes", json={"body": bad2}, headers=h).status_code == 422)
    bad3 = {"blocks": [{"type": "bullet_list", "items": []}]}
    check("empty list 422",
          c.post("/api/v1/notes", json={"body": bad3}, headers=h).status_code == 422)
    bad4 = {"blocks": [{"type": "check_list", "items": [{"spans": [{"text": "x"}], "checked": "yes"}]}]}
    check("non-bool checked 422",
          c.post("/api/v1/notes", json={"body": bad4}, headers=h).status_code == 422)
    bad5 = {"nope": 1}
    check("missing blocks 422",
          c.post("/api/v1/notes", json={"body": bad5}, headers=h).status_code == 422)

    print("== checklists ==")
    body = {"blocks": [{"type": "check_list", "items": [
        {"type": "list_item", "spans": [{"text": "milk"}], "checked": True},
        {"type": "list_item", "spans": [{"text": "eggs"}]}]}]}
    r = c.post("/api/v1/notes", json={"body": body}, headers=h)
    check("check_list created", r.status_code == 201, r.text)
    check("check_list plaintext", r.json()["plaintext"] == "[x] milk\n[ ] eggs")

    print("== reminders ==")
    r = c.post("/api/v1/notes", json={"body": P("call mom"),
                                      "reminder_at": "2026-10-20T09:00:00Z"}, headers=h)
    check("reminder created", r.json()["reminder_at"] == "2026-10-20T09:00:00Z")
    rid = r.json()["id"]
    r = c.post("/api/v1/notes", json={"body": P("x"), "reminder_at": "tomorrow"}, headers=h)
    check("bad reminder 422", r.status_code == 422)
    got = c.get("/api/v1/notes", params={"has_reminder": "true"}, headers=h).json()["notes"]
    check("has_reminder filter", any(x["id"] == rid for x in got))
    got = c.get("/api/v1/notes", params={"reminder_before": "2026-10-21T00:00:00Z"},
                headers=h).json()["notes"]
    check("reminder_before filter", any(x["id"] == rid for x in got))
    r = c.patch(f"/api/v1/notes/{rid}", json={"reminder_at": None}, headers=h)
    check("patch null clears reminder", r.json()["reminder_at"] is None)
    c.patch(f"/api/v1/notes/{rid}", json={"reminder_at": "2026-10-21T10:00:00Z"}, headers=h)
    r = c.patch(f"/api/v1/notes/{rid}", json={"favorite": True}, headers=h)
    check("patch omitted keeps reminder",
          r.json()["reminder_at"] == "2026-10-21T10:00:00Z")

    print("== pinned / archived ==")
    a = c.post("/api/v1/notes", json={"body": P("pinned note"), "pinned": True}, headers=h).json()
    b = c.post("/api/v1/notes", json={"body": P("plain note")}, headers=h).json()
    check("pinned flag", a["pinned"] is True and b["pinned"] is False)
    notes = c.get("/api/v1/notes", headers=h).json()["notes"]
    check("pinned sorts first", notes[0]["id"] == a["id"])
    got = c.get("/api/v1/notes", params={"pinned": "true"}, headers=h).json()["notes"]
    check("pinned filter", len(got) == 1 and got[0]["id"] == a["id"])
    n3 = c.post("/api/v1/notes", json={"body": P("archived note"), "archived": True},
                headers=h).json()
    ids = [x["id"] for x in c.get("/api/v1/notes", headers=h).json()["notes"]]
    check("archived hidden by default", n3["id"] not in ids)
    got = c.get("/api/v1/notes", params={"archived": "true"}, headers=h).json()["notes"]
    check("archived filter shows it", any(x["id"] == n3["id"] for x in got))
    cur = c.get(f"/api/v1/notes/{n3['id']}", headers=h).json()["revision"]
    r = c.put(f"/api/v1/notes/{n3['id']}",
              json={"body": P("archived note"), "base_revision": cur, "archived": False},
              headers=h)
    check("unarchive via put", r.json()["archived"] is False)

    print("== revision history ==")
    nb = c.post("/api/v1/notes", json={"title": "rev", "body": P("v one")}, headers=h).json()
    rnid = nb["id"]
    for t in ("v two", "v three"):
        cur = c.get(f"/api/v1/notes/{rnid}", headers=h).json()["revision"]
        c.put(f"/api/v1/notes/{rnid}", json={"body": P(t), "base_revision": cur}, headers=h)
    revs = c.get(f"/api/v1/notes/{rnid}/revisions", headers=h).json()["revisions"]
    check("3 revisions listed", len(revs) == 3, revs)
    r1 = c.get(f"/api/v1/notes/{rnid}/revisions/1", headers=h).json()
    check("revision 1 body", r1["body"]["blocks"][0]["spans"][0]["text"] == "v one")
    check("revision 404", c.get(f"/api/v1/notes/{rnid}/revisions/99", headers=h).status_code == 404)
    r = c.post(f"/api/v1/notes/{rnid}/revisions/1/restore", headers=h)
    check("restore creates new revision",
          r.status_code == 200 and r.json()["revision"] == 4
          and r.json()["plaintext"] == "v one", r.text)

    print("== search (FTS5) ==")
    c.post("/api/v1/notes", json={"title": "groceries", "body": P("buy kumquats and durian")},
           headers=h)
    c.post("/api/v1/notes", json={"title": "other", "body": P("unrelated zebra")}, headers=h)
    got = c.get("/api/v1/notes", params={"q": "kumquats durian"}, headers=h).json()["notes"]
    check("multi-word search", len(got) == 1 and got[0]["title"] == "groceries", got)
    got = c.get("/api/v1/notes", params={"q": "zebra"}, headers=h).json()["notes"]
    check("title search", len(got) == 1 and got[0]["title"] == "other")
    got = c.get("/api/v1/notes", params={"q": 'kumquats %_\\"'}, headers=h).json()["notes"]
    check("special chars safe", len(got) == 1)
    got = c.get("/api/v1/notes", params={"q": "zzzznothing"}, headers=h).json()["notes"]
    check("no matches", got == [])

    print("== trash ==")
    t1 = c.post("/api/v1/notes", json={"body": P("trash me")}, headers=h).json()["id"]
    c.delete(f"/api/v1/notes/{t1}", headers=h)
    check("trashed not in list",
          all(x["id"] != t1 for x in c.get("/api/v1/notes", headers=h).json()["notes"]))
    trash = c.get("/api/v1/notes/trash", headers=h).json()
    check("trash lists it", any(x["id"] == t1 for x in trash["notes"]))
    check("trash pagination key", "next_cursor" in trash)
    c.post(f"/api/v1/notes/{t1}/restore", headers=h)
    check("restored back in list",
          any(x["id"] == t1 for x in c.get("/api/v1/notes", headers=h).json()["notes"]))
    c.delete(f"/api/v1/notes/{t1}", headers=h)
    c.delete(f"/api/v1/notes/{t1}?hard=true", headers=h)
    check("hard delete gone",
          c.get(f"/api/v1/notes/{t1}", headers=h).status_code == 404)

    print("== attachments ==")
    import io
    files = {"file": ("hello.txt", io.BytesIO(b"hi"), "text/plain")}
    r = c.post("/api/v1/attachments", params={"note_id": nid}, files=files, headers=h)
    check("upload 201", r.status_code == 201, r.text)
    aid = r.json()["id"]
    check("size recorded", r.json()["size"] == 2)
    r = c.get(f"/api/v1/attachments/{aid}/file", headers=h)
    check("download bytes", r.status_code == 200 and r.content == b"hi")
    c.delete(f"/api/v1/attachments/{aid}", headers=h)
    check("attachment deleted",
          c.get(f"/api/v1/attachments/{aid}/file", headers=h).status_code == 404)

print("\nALL API TESTS OK")
