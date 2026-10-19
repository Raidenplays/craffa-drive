package com.raiden.craffa.data.api

import com.raiden.craffa.data.json.Json
import com.raiden.craffa.data.json.arr
import com.raiden.craffa.data.json.int
import com.raiden.craffa.data.json.long
import com.raiden.craffa.data.json.obj
import com.raiden.craffa.data.json.str
import com.raiden.craffa.data.model.Attachment
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.Folder
import com.raiden.craffa.data.model.Health
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.data.model.Page
import com.raiden.craffa.data.model.RevisionBody
import com.raiden.craffa.data.model.RevisionInfo
import com.raiden.craffa.data.model.Time
import java.net.URLEncoder
import java.time.Instant

/** Where the server is and how to authenticate. */
data class ServerConfig(val baseUrl: String, val token: String) {
    val normalizedBase: String get() = baseUrl.trim().trimEnd('/')
    val isConfigured: Boolean get() = normalizedBase.isNotEmpty() && token.isNotBlank()
}

/** Errors the UI can react to specifically. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : ApiException("Add your server address and API token in Settings.")
    class Unauthorized(msg: String) : ApiException(msg)
    class NotFound(msg: String) : ApiException(msg)

    /** 409 revision_conflict — [serverCopy] is the note as it is on the server now. */
    class Conflict(val serverCopy: Note, msg: String) : ApiException(msg)
    class Invalid(msg: String) : ApiException(msg)
    class Server(val code: Int, msg: String) : ApiException(msg)
    class Network(msg: String, cause: Throwable? = null) : ApiException(msg, cause)
}

/** Filters for GET /api/v1/notes. Null = not sent. */
data class NoteQuery(
    val folderId: String? = null,
    val q: String? = null,
    val favorite: Boolean? = null,
    val pinned: Boolean? = null,
    /** null/false = only non-archived (server default); true = only archived. */
    val archived: Boolean? = null,
    val hasReminder: Boolean? = null,
    val reminderBefore: Instant? = null,
    val since: String? = null,
    val limit: Int = 50,
    val cursor: Int = 0,
)

/**
 * Field-level changes for PATCH. Only keys that are present are sent; a key
 * mapped to null is sent as an explicit null (clears folder / reminder).
 */
class NotePatch private constructor(internal val fields: Map<String, Any?>) {
    class Builder {
        private val f = linkedMapOf<String, Any?>()
        fun title(v: String) = apply { f["title"] = v }
        fun folder(id: String?) = apply { f["folder_id"] = id }
        fun tags(v: List<String>) = apply { f["tags"] = v }
        fun favorite(v: Boolean) = apply { f["favorite"] = v }
        fun pinned(v: Boolean) = apply { f["pinned"] = v }
        fun archived(v: Boolean) = apply { f["archived"] = v }
        fun reminder(at: Instant?) = apply { f["reminder_at"] = at?.let(Time::format) }
        fun build() = NotePatch(f.toMap())
    }

    companion object {
        fun favorite(v: Boolean) = Builder().favorite(v).build()
        fun pinned(v: Boolean) = Builder().pinned(v).build()
        fun archived(v: Boolean) = Builder().archived(v).build()
        fun folder(id: String?) = Builder().folder(id).build()
        fun reminder(at: Instant?) = Builder().reminder(at).build()
    }
}

/**
 * Blocking client for the Craffa notes API (spec v0.1). Call from a
 * background dispatcher. The transport is swappable: OkHttp on Android,
 * java.net.http in JVM tests.
 */
class CraffaApi(
    private val config: () -> ServerConfig,
    private val transport: HttpTransport,
) {
    companion object {
        /** The API contract this build was written against. */
        const val SUPPORTED_API_MAJOR = "0"
    }

    // ------------------------------------------------------------ health

    fun health(): Health {
        val cfg = config()
        if (cfg.normalizedBase.isEmpty()) throw ApiException.NotConfigured()
        val j = obj(send(HttpRequest("GET", cfg.normalizedBase + "/health"), auth = false))
        return Health(j.str("version") ?: "?", j.str("api_version"))
    }

    // ------------------------------------------------------------ notes

    fun listNotes(query: NoteQuery = NoteQuery()): Page<Note> {
        val params = buildList {
            query.since?.let { add("since" to it) }
            query.folderId?.let { add("folder_id" to it) }
            query.q?.takeIf { it.isNotBlank() }?.let { add("q" to it.trim()) }
            query.favorite?.let { add("favorite" to it.toString()) }
            query.pinned?.let { add("pinned" to it.toString()) }
            query.archived?.let { add("archived" to it.toString()) }
            query.hasReminder?.let { add("has_reminder" to it.toString()) }
            query.reminderBefore?.let { add("reminder_before" to Time.format(it)) }
            add("limit" to query.limit.toString())
            add("cursor" to query.cursor.toString())
        }
        val j = get("/api/v1/notes", params)
        return Page(notes(j), j.int("next_cursor"))
    }

    /** Follows next_cursor until done (or [maxNotes] reached). */
    fun listAllNotes(query: NoteQuery, maxNotes: Int = 2000): List<Note> {
        val out = ArrayList<Note>()
        var cursor: Int? = 0
        while (cursor != null && out.size < maxNotes) {
            val page = listNotes(query.copy(limit = 200, cursor = cursor))
            out += page.items
            cursor = page.nextCursor
        }
        return out
    }

    /**
     * Like [listAllNotes] but across both archived and non-archived notes
     * (the server hides archived ones unless asked) — e.g. for reminders.
     */
    fun listAllNotesIncludingArchived(query: NoteQuery, maxNotes: Int = 2000): List<Note> =
        listAllNotes(query.copy(archived = false), maxNotes) + listAllNotes(query.copy(archived = true), maxNotes)

    fun trash(cursor: Int = 0, limit: Int = 50): Page<Note> {
        val j = get("/api/v1/notes/trash", listOf("limit" to limit.toString(), "cursor" to cursor.toString()))
        return Page(notes(j), j.int("next_cursor"))
    }

    fun getNote(id: String): Note = Note.fromJson(get("/api/v1/notes/${enc(id)}"))

    fun createNote(
        body: Doc,
        title: String? = null,
        folderId: String? = null,
        tags: List<String> = emptyList(),
        reminderAt: Instant? = null,
    ): Note {
        val payload = linkedMapOf<String, Any?>("body" to body.toJson())
        if (!title.isNullOrBlank()) payload["title"] = title
        if (folderId != null) payload["folder_id"] = folderId
        if (tags.isNotEmpty()) payload["tags"] = tags
        if (reminderAt != null) payload["reminder_at"] = Time.format(reminderAt)
        return Note.fromJson(write("POST", "/api/v1/notes", payload))
    }

    /**
     * Full body replace guarded by [baseRevision]. [title] null/blank = let the
     * server derive it from the first line. Metadata (folder, tags, favorite,
     * reminder) is omitted, so the server keeps its current values.
     * Throws [ApiException.Conflict] on a revision mismatch.
     */
    fun updateNote(id: String, body: Doc, title: String?, baseRevision: Long): Note {
        val payload = linkedMapOf<String, Any?>(
            "title" to title?.takeIf { it.isNotBlank() },
            "body" to body.toJson(),
            "base_revision" to baseRevision,
        )
        return Note.fromJson(write("PUT", "/api/v1/notes/${enc(id)}", payload))
    }

    fun patchNote(id: String, patch: NotePatch, baseRevision: Long? = null): Note {
        val payload = LinkedHashMap(patch.fields)
        if (baseRevision != null) payload["base_revision"] = baseRevision
        return Note.fromJson(write("PATCH", "/api/v1/notes/${enc(id)}", payload))
    }

    fun deleteNote(id: String, hard: Boolean = false) {
        val q = if (hard) "?hard=true" else ""
        send(request("DELETE", "/api/v1/notes/${enc(id)}$q"))
    }

    fun restoreNote(id: String): Note = Note.fromJson(write("POST", "/api/v1/notes/${enc(id)}/restore", null))

    // ------------------------------------------------------------ revisions

    /** Version history, newest first. The server keeps the last 50 body versions. */
    fun revisions(noteId: String): List<RevisionInfo> =
        get("/api/v1/notes/${enc(noteId)}/revisions").arr("revisions").mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            (raw as? Map<String, Any?>)?.let { r ->
                r.long("revision")?.let { RevisionInfo(it, Time.parse(r.str("updated_at"))) }
            }
        }.sortedByDescending { it.revision }

    fun revision(noteId: String, rev: Long): RevisionBody {
        val j = get("/api/v1/notes/${enc(noteId)}/revisions/$rev")
        return RevisionBody(j.long("revision") ?: rev, Doc.fromJson(j.obj("body")), Time.parse(j.str("updated_at")))
    }

    /** Restore an old body as a new revision (history is never rewritten). */
    fun restoreRevision(noteId: String, rev: Long): Note =
        Note.fromJson(write("POST", "/api/v1/notes/${enc(noteId)}/revisions/$rev/restore", null))

    // ------------------------------------------------------------ folders

    fun folders(): List<Folder> = get("/api/v1/folders").arr("folders").mapNotNull { raw ->
        @Suppress("UNCHECKED_CAST")
        (raw as? Map<String, Any?>)?.let(Folder::fromJson)
    }

    fun createFolder(name: String, parentId: String? = null): Folder {
        val payload = linkedMapOf<String, Any?>("name" to name.trim())
        if (parentId != null) payload["parent_id"] = parentId
        return Folder.fromJson(write("POST", "/api/v1/folders", payload))
    }

    fun renameFolder(id: String, name: String): Folder =
        Folder.fromJson(write("PUT", "/api/v1/folders/${enc(id)}", linkedMapOf("name" to name.trim())))

    fun deleteFolder(id: String) {
        send(request("DELETE", "/api/v1/folders/${enc(id)}"))
    }

    // ------------------------------------------------------------ attachments

    fun uploadAttachment(fileName: String, mime: String, bytes: ByteArray, noteId: String? = null): Attachment {
        val q = if (noteId != null) "?note_id=${enc(noteId)}" else ""
        val req = request("POST", "/api/v1/attachments$q").copy(
            multipart = MultipartFile("file", fileName, mime, bytes),
        )
        val j = obj(send(req))
        return Attachment(
            id = j.str("id") ?: throw ApiException.Server(500, "upload returned no id"),
            url = j.str("url") ?: "",
            filename = j.str("filename"),
            size = j.long("size"),
        )
    }

    fun attachmentUrl(id: String): String = config().normalizedBase + "/api/v1/attachments/${enc(id)}/file"

    fun deleteAttachment(id: String) {
        send(request("DELETE", "/api/v1/attachments/${enc(id)}"))
    }

    // ------------------------------------------------------------ plumbing

    private fun notes(j: Map<String, Any?>): List<Note> = j.arr("notes").mapNotNull { raw ->
        @Suppress("UNCHECKED_CAST")
        (raw as? Map<String, Any?>)?.let(Note::fromJson)
    }

    private fun get(path: String, params: List<Pair<String, String>> = emptyList()): Map<String, Any?> {
        val qs = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return obj(send(request("GET", path + qs)))
    }

    private fun write(method: String, path: String, payload: Map<String, Any?>?): Map<String, Any?> {
        val req = request(method, path).copy(
            body = payload?.let { Json.stringify(it).toByteArray(Charsets.UTF_8) },
            contentType = if (payload != null) "application/json" else null,
        )
        return obj(send(req))
    }

    private fun request(method: String, path: String): HttpRequest {
        val cfg = config()
        if (!cfg.isConfigured) throw ApiException.NotConfigured()
        return HttpRequest(method, cfg.normalizedBase + path)
    }

    private fun obj(body: String): Map<String, Any?> = try {
        if (body.isBlank()) emptyMap() else Json.parseObject(body)
    } catch (e: Json.ParseException) {
        throw ApiException.Server(200, "Unexpected response from server (not JSON). Is the address right?")
    }

    private fun send(req: HttpRequest, auth: Boolean = true): String {
        val cfg = config()
        val headers = LinkedHashMap(req.headers)
        headers["Accept"] = "application/json"
        if (auth) headers["Authorization"] = "Bearer ${cfg.token.trim()}"
        val resp = try {
            transport.execute(req.copy(headers = headers))
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.Network(networkMessage(e, cfg), e)
        }
        if (resp.code in 200..299) return resp.body
        throw errorFor(resp)
    }

    private fun errorFor(resp: HttpResponse): ApiException {
        val j = runCatching { Json.parseObject(resp.body) }.getOrNull()
        val msg = j?.obj("error")?.str("message")
            ?: j?.let { detailMessage(it) }
            ?: "HTTP ${resp.code}"
        return when (resp.code) {
            401 -> ApiException.Unauthorized("The server rejected the API token. Check it in Settings.")
            404 -> ApiException.NotFound(msg)
            409 -> {
                val copy = j?.obj("server_copy")?.let { runCatching { Note.fromJson(it) }.getOrNull() }
                if (copy != null) ApiException.Conflict(copy, msg) else ApiException.Server(409, msg)
            }
            400, 413, 422 -> ApiException.Invalid(msg)
            else -> ApiException.Server(resp.code, msg)
        }
    }

    /** FastAPI's own 422s use {"detail": [...]} rather than the Craffa error shape. */
    private fun detailMessage(j: Map<String, Any?>): String? {
        val detail = j["detail"] ?: return null
        if (detail is String) return detail
        val first = (detail as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
        return first["msg"] as? String
    }

    private fun networkMessage(e: Exception, cfg: ServerConfig): String {
        val text = (e.message ?: "") + " " + e.javaClass.simpleName
        return when {
            "CLEARTEXT" in text.uppercase() ->
                "Plain HTTP to ${hostOf(cfg.normalizedBase)} isn't allowed by this build. " +
                    "Use the Tailscale address or add the host to network_security_config.xml."
            e is java.net.UnknownHostException -> "Can't find ${hostOf(cfg.normalizedBase)}. Is Tailscale connected?"
            e is java.net.SocketTimeoutException -> "The server took too long to answer. Is Tailscale connected?"
            e is java.net.ConnectException -> "Can't reach the server. Is it running, and is Tailscale connected?"
            else -> "Can't reach the server: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').ifEmpty { url }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
