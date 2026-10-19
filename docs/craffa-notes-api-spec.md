# Craffa notes API spec — v0.1 (MVP)

Date: 2026-10-19 · Status: draft, revised after Claude's review
Decided: notes are Word-style rich text (bold, italic, underline, headings, lists, alignment, links).

## 1. Principles

- The homelab API is the source of truth; the four native clients are thin renderers.
- Rich text is stored as **structured JSON** (blocks + inline marks), never HTML — every client renders from the same model with native text components, no parsing quirks.
- The server **validates** document structure on write (unknown block types, bad
  marks, malformed spans → 422). Whatever clients write becomes the de-facto
  spec for future clients, so the canonical format below is normative.
- A plaintext copy of each note is stored alongside for search.
- MVP is single-user (Raiden). Auth is a bearer token; the network is already restricted to Tailscale.

## 2. Document model (normative)

A note body is a JSON document: `{ "blocks": [ … ] }`.

**Top-level block types:** `paragraph`, `heading` (level 1–3), `bullet_list`,
`ordered_list`, `check_list`, `quote`, `code`, `image`, `divider`.
(`list_item` is NOT a top-level block — it only appears inside list `items`
arrays, as `{"type": "list_item", "spans": [...]}`; the `type` field there is
informational.)

**Block attrs:** `align` (`left` / `center` / `right`, default `left`).

**Inline marks** on text spans: plain strings — `bold`, `italic`, `underline`,
`strikethrough`, `code` — except links, which are objects:
`{"name": "link", "href": "https://…"}`.

**Lists:** `bullet_list` / `ordered_list` / `check_list` hold
`items: [{spans: [...]}, …]` (non-empty). `check_list` items carry
`checked: bool` (default false). No nesting in v0.1.

Example:

```json
{
  "blocks": [
    { "type": "heading", "level": 1, "align": "left",
      "spans": [{ "text": "Grocery run", "marks": ["bold"] }] },
    { "type": "bullet_list", "items": [
      { "type": "list_item", "spans": [{ "text": "Oat milk" }] },
      { "type": "list_item", "spans": [{ "text": "Eggs", "marks": ["strikethrough"] }] }
    ]},
    { "type": "paragraph", "spans": [
      { "text": "See " }, { "text": "the list", "marks": [{"name": "link", "href": "https://example.com"}] }
    ]},
    { "type": "image", "attachment_id": "a91f…", "alt": "receipt" }
  ]
}
```

Tables, footnotes, comments, nested lists: deferred past MVP.

## 3. Records

**Note:** `id` (uuid) · `title` (auto = first heading/paragraph text, 80 chars, editable) ·
`body` (doc JSON) · `plaintext` (server-extracted, returned in responses for list snippets) ·
`folder_id` (nullable) · `tags` (string[]) · `favorite` (bool) · `pinned` (bool —
pinned notes sort first) · `archived` (bool — hidden from the default list) ·
`revision` (int, +1 per write) · `created_at` / `updated_at` (ISO 8601 UTC) ·
`deleted_at` (nullable → trash) · `reminder_at` (nullable ISO 8601 UTC —
a due date/time for the note; notification delivery is client-side in v1).

**Folder:** `id` · `name` · `parent_id` (nullable) · timestamps.

**Attachment:** `id` · `note_id` (nullable until attached) · `filename` · `mime` ·
`size` · `sha256` · timestamps. Served at `GET /api/v1/attachments/{id}/file`.

## 4. Sync (MVP)

- `revision` guards writes: `PUT` requires `base_revision`; mismatch → `409`
  with the current server copy included; client re-bases or asks the user.
- `PATCH` accepts an optional `base_revision` and 409-checks it when provided.
- `GET /notes?since=<ts>` returns only notes changed since `ts`.
  Deletions: poll `GET /notes/trash?since=<ts>` (soft deletes only).
- Deleting a folder bumps `revision`/`updated_at` on its notes, so a stale
  client PUT can't silently restore a note into the deleted folder.
- Reminders: any note may carry `reminder_at`. The server stores it and offers
  `has_reminder` / `reminder_before` list filters; actually notifying the user
  is the client's job in v1 (poll due reminders, fire a local notification).
- Last 50 revisions kept server-side and exposed via the revisions endpoints
  below (list, fetch one, restore as a new revision — history is never rewritten).

## 5. Endpoints

- `GET /health` → `{ "ok": true, "version": "0.1.0", "api_version": "0.1" }`
  (`api_version` lets clients detect breaking server changes from auto-update)
- `GET /api/v1/notes?since=&folder_id=&q=&favorite=&pinned=&archived=&has_reminder=&reminder_before=&limit=&cursor=` — excludes trash by default; archived notes are hidden unless `archived=true`. `q` searches via SQLite FTS5 (relevance-ranked); pinned notes sort first otherwise.
- `POST /api/v1/notes` — `{ title?, body, folder_id?, tags?, reminder_at?, pinned?, archived? }`
- `GET /api/v1/notes/{id}`
- `PUT /api/v1/notes/{id}` — full body replace:
  `{ title?, body, base_revision, folder_id?, tags?, favorite?, reminder_at?, pinned?, archived? }`.
  Omitted metadata fields keep their current values (never silently wiped).
- `PATCH /api/v1/notes/{id}` —
  `{ title?, folder_id?, tags?, favorite?, reminder_at?, pinned?, archived?, base_revision? }`.
  Explicit `folder_id: null` clears the folder, explicit `reminder_at: null`
  clears the reminder; omitted fields are untouched.
- `DELETE /api/v1/notes/{id}` — soft delete → trash (`?hard=true` purges)
- `POST /api/v1/notes/{id}/restore`
- `GET /api/v1/notes/{id}/revisions` — version history (revision + timestamp)
- `GET /api/v1/notes/{id}/revisions/{rev}` — one old version's body
- `POST /api/v1/notes/{id}/revisions/{rev}/restore` — restore as a new revision
- `DELETE /api/v1/notes/{id}` — soft delete → trash (`?hard=true` purges)
- `POST /api/v1/notes/{id}/restore`
- `GET /api/v1/notes/trash?since=&limit=&cursor=`
- `GET` / `POST /api/v1/folders` · `PUT /api/v1/folders/{id}` (rename) ·
  `DELETE /api/v1/folders/{id}`
- `POST /api/v1/attachments` (multipart file) → `{ id, url }`
- `GET /api/v1/attachments/{id}/file` · `DELETE /api/v1/attachments/{id}`
- Search via `q` param (SQLite FTS5, relevance-ranked)

## 6. Auth

`Authorization: Bearer <token>`. Token generated on first run, stored in server
config; rotate via CLI. No login UI in MVP.

## 7. Errors

`{ "error": { "code": "revision_conflict", "message": "…" } }`
(`server_copy` included on 409). HTTP: 400 / 401 / 404 / 409 / 413 / 422.

## 8. Non-goals / known limitations (MVP)

- Real-time collaboration · sharing links · E2E encryption (Tailscale covers
  transport for now) · tables · offline-first conflict UI (409 + rebase is enough).
- No tombstones for hard deletes or folders (soft-delete + trash covers sync).
- Offset-based paging on `updated_at` can skip/repeat rows mid-scroll; acceptable
  for MVP, keyset paging later.
- Unreferenced attachments are never cleaned up (a future GC pass).
