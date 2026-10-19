package com.raiden.craffa.data.model

import com.raiden.craffa.data.json.arr
import com.raiden.craffa.data.json.bool
import com.raiden.craffa.data.json.long
import com.raiden.craffa.data.json.obj
import com.raiden.craffa.data.json.str
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class Note(
    val id: String,
    val title: String,
    val body: Doc,
    val plaintext: String,
    val folderId: String?,
    val tags: List<String>,
    val favorite: Boolean,
    val pinned: Boolean,
    val archived: Boolean,
    val revision: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val deletedAt: Instant?,
    val reminderAt: Instant?,
) {
    /** True when the title is just the server's auto-derived first line. */
    val hasAutoTitle: Boolean get() = title == DocText.deriveTitle(body)

    /** Plaintext minus the line used as the title — for list snippets. */
    val snippet: String
        get() {
            val lines = plaintext.lines().filter { it.isNotBlank() }
            val rest = if (lines.firstOrNull()?.trim() == title.trim()) lines.drop(1) else lines
            return rest.joinToString(" · ").take(240)
        }

    companion object {
        fun fromJson(j: Map<String, Any?>): Note = Note(
            id = j.str("id") ?: error("note without id"),
            title = j.str("title") ?: "Untitled",
            body = Doc.fromJson(j.obj("body")),
            plaintext = j.str("plaintext") ?: "",
            folderId = j.str("folder_id"),
            tags = j.arr("tags").filterIsInstance<String>(),
            favorite = j.bool("favorite"),
            pinned = j.bool("pinned"),
            archived = j.bool("archived"),
            revision = j.long("revision") ?: 0L,
            createdAt = Time.parse(j.str("created_at")),
            updatedAt = Time.parse(j.str("updated_at")),
            deletedAt = Time.parse(j.str("deleted_at")),
            reminderAt = Time.parse(j.str("reminder_at")),
        )
    }
}

data class Folder(
    val id: String,
    val name: String,
    val parentId: String?,
) {
    companion object {
        fun fromJson(j: Map<String, Any?>) = Folder(
            id = j.str("id") ?: error("folder without id"),
            name = j.str("name") ?: "",
            parentId = j.str("parent_id"),
        )
    }
}

data class Page<T>(val items: List<T>, val nextCursor: Int?)

/** One entry of a note's version history (GET /notes/{id}/revisions). */
data class RevisionInfo(val revision: Long, val updatedAt: Instant?)

/** An old version's body (GET /notes/{id}/revisions/{rev}). */
data class RevisionBody(val revision: Long, val body: Doc, val updatedAt: Instant?)

data class Health(val version: String, val apiVersion: String?)

data class Attachment(val id: String, val url: String, val filename: String?, val size: Long?)

object Time {
    /** Server timestamps are ISO 8601; tolerate offsets, `Z`, or naive (treated as UTC). */
    fun parse(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant() }
            .recoverCatching { Instant.parse(s) }
            .recoverCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }
            .getOrNull()
    }

    /**
     * Wire format for times we send: UTC with `Z`, minute precision for
     * reminders (e.g. 2026-10-20T09:00:00Z). Always the same shape, so the
     * server's string comparison for `reminder_before` stays correct.
     */
    fun format(i: Instant): String = i.truncatedTo(ChronoUnit.SECONDS).toString()
}
