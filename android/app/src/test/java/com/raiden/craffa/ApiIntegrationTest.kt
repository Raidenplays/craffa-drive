package com.raiden.craffa

import com.raiden.craffa.data.api.ApiException
import com.raiden.craffa.data.api.CraffaApi
import com.raiden.craffa.data.api.NotePatch
import com.raiden.craffa.data.api.NoteQuery
import com.raiden.craffa.data.api.ServerConfig
import com.raiden.craffa.data.json.Json
import com.raiden.craffa.data.model.Block
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.ListItem
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.data.model.Mark
import com.raiden.craffa.data.model.Span
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real client against a live Craffa server. Skipped unless
 * CRAFFA_TEST_URL and CRAFFA_TEST_TOKEN are set (so CI doesn't need one):
 *
 *   NOTES_TOKEN=testtoken uvicorn app.main:app --port 8471
 *   CRAFFA_TEST_URL=http://127.0.0.1:8471 CRAFFA_TEST_TOKEN=testtoken ./gradlew testDebugUnitTest
 */
class ApiIntegrationTest {
    private val url: String? = System.getenv("CRAFFA_TEST_URL")
    private val token: String? = System.getenv("CRAFFA_TEST_TOKEN")
    private val api by lazy { CraffaApi({ ServerConfig(url!!, token!!) }, JvmTransport()) }

    private fun live(block: () -> Unit) {
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            println("  (skipped: CRAFFA_TEST_URL not set)")
            return
        }
        block()
    }

    private fun richDoc(text: String) = Doc(listOf(
        Block.Heading(2, listOf(Span(text, setOf(Mark.Bold, Mark.Italic)))),
        Block.Paragraph(listOf(Span("see "), Span("link", setOf(Mark.Link("https://example.com"), Mark.Underline)))),
        Block.ListBlock(ListKind.CHECK, listOf(ListItem(listOf(Span("milk"))), ListItem(listOf(Span("eggs")), checked = true))),
        Block.ListBlock(ListKind.ORDERED, listOf(ListItem(listOf(Span("one"))))),
        Block.Quote(listOf(Span("q", setOf(Mark.Strikethrough, Mark.Code)))),
        Block.CodeBlock(listOf(Span("a\nb"))),
        Block.Divider(),
    ))

    @Test
    fun health_reportsApiVersion() = live {
        assertEquals("0.1", api.health().apiVersion)
    }

    @Test
    fun badToken_isUnauthorized() = live {
        val bad = CraffaApi({ ServerConfig(url!!, "nope") }, JvmTransport())
        assertFailsWith<ApiException.Unauthorized> { bad.folders() }
    }

    @Test
    fun noteLifecycle_withConflictsAndMetadata() = live {
        val folder = api.createFolder("Test ${System.nanoTime()}")
        val created = api.createNote(richDoc("Hello"), folderId = folder.id)
        assertEquals("Hello", created.title)          // derived
        assertTrue(created.hasAutoTitle)
        assertEquals(richDoc("Hello"), created.body)  // server accepted + returned our exact doc
        assertEquals("[ ] milk", created.plaintext.lines()[2])

        // favorite via PATCH with base_revision
        val fav = api.patchNote(created.id, NotePatch.favorite(true), created.revision)
        assertTrue(fav.favorite)
        assertEquals(created.revision + 1, fav.revision)

        // PUT with the new revision keeps folder + favorite (metadata omitted)
        val edited = api.updateNote(created.id, richDoc("Hello again"), title = null, baseRevision = fav.revision)
        assertEquals("Hello again", edited.title)
        assertTrue(edited.favorite)
        assertEquals(folder.id, edited.folderId)

        // stale PUT → Conflict with the server copy
        val conflict = assertFailsWith<ApiException.Conflict> {
            api.updateNote(created.id, richDoc("stale"), title = null, baseRevision = created.revision)
        }
        assertEquals(edited.revision, conflict.serverCopy.revision)
        assertEquals(edited.body, conflict.serverCopy.body)

        // "Keep mine": re-PUT on top of the server revision
        val kept = api.updateNote(created.id, richDoc("mine wins"), title = "Custom", baseRevision = conflict.serverCopy.revision)
        assertEquals("Custom", kept.title)
        assertFalse(kept.hasAutoTitle)

        // stale PATCH → conflict too
        assertFailsWith<ApiException.Conflict> { api.patchNote(created.id, NotePatch.favorite(false), created.revision) }

        // explicit null clears the folder
        val unfiled = api.patchNote(created.id, NotePatch.folder(null), kept.revision)
        assertNull(unfiled.folderId)

        // full-text search (FTS5, whole words) and favorite filter
        assertTrue(api.listNotes(NoteQuery(q = "mine wins")).items.any { it.id == created.id })
        assertFalse(api.listNotes(NoteQuery(q = "zebra")).items.any { it.id == created.id })
        assertTrue(api.listNotes(NoteQuery(favorite = true)).items.any { it.id == created.id })

        // trash + restore
        api.deleteNote(created.id)
        assertFailsWith<ApiException.NotFound> { api.getNote(created.id) }
        assertTrue(api.trash().items.any { it.id == created.id })
        val restored = api.restoreNote(created.id)
        assertNull(restored.deletedAt)

        api.deleteNote(created.id, hard = true)
        api.deleteFolder(folder.id)
    }

    @Test
    fun reminders_setFilterClear() = live {
        val at = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        val n = api.createNote(richDoc("Reminder note"), reminderAt = at)
        assertEquals(at, n.reminderAt)

        val upcoming = api.listAllNotes(NoteQuery(hasReminder = true, reminderBefore = at.plus(1, ChronoUnit.MINUTES)))
        assertTrue(upcoming.any { it.id == n.id })
        val tooEarly = api.listAllNotes(NoteQuery(hasReminder = true, reminderBefore = at.minus(1, ChronoUnit.MINUTES)))
        assertFalse(tooEarly.any { it.id == n.id })

        // PUT keeps reminder (omitted); PATCH null clears it
        val edited = api.updateNote(n.id, richDoc("Reminder note 2"), null, n.revision)
        assertEquals(at, edited.reminderAt)
        val cleared = api.patchNote(n.id, NotePatch.reminder(null), edited.revision)
        assertNull(cleared.reminderAt)
        assertFalse(api.listAllNotes(NoteQuery(hasReminder = true)).any { it.id == n.id })
        api.deleteNote(n.id, hard = true)
    }

    @Test
    fun metadataOnlyChange_leavesBodySoEditorCanRebase() = live {
        val n = api.createNote(richDoc("Rebase me"))
        // e.g. "Snooze" from a notification: PATCH without base_revision
        api.patchNote(n.id, NotePatch.reminder(Instant.now().plus(1, ChronoUnit.HOURS)))
        val conflict = assertFailsWith<ApiException.Conflict> {
            api.updateNote(n.id, richDoc("edited"), null, n.revision)
        }
        assertEquals(n.body, conflict.serverCopy.body)   // the editor's "content unchanged" check
        assertEquals(n.title, conflict.serverCopy.title)
        val saved = api.updateNote(n.id, richDoc("edited"), null, conflict.serverCopy.revision)
        assertEquals("edited", saved.title)
        assertNotNull(saved.reminderAt)                     // PUT kept the reminder
        api.deleteNote(n.id, hard = true)
    }

    @Test
    fun pinned_sortsFirst_andFilters() = live {
        val a = api.createNote(richDoc("Pin A ${System.nanoTime()}"))
        val b = api.createNote(richDoc("Pin B ${System.nanoTime()}"))  // newer, so normally first
        val pinned = api.patchNote(a.id, NotePatch.pinned(true), a.revision)
        assertTrue(pinned.pinned)
        val first = api.listNotes(NoteQuery()).items
        assertTrue(first.indexOfFirst { it.id == a.id } < first.indexOfFirst { it.id == b.id })
        assertTrue(api.listAllNotes(NoteQuery(pinned = true)).all { it.pinned })
        // PUT keeps pinned
        assertTrue(api.updateNote(a.id, richDoc("Pin A edited"), null, pinned.revision).pinned)
        api.deleteNote(a.id, hard = true)
        api.deleteNote(b.id, hard = true)
    }

    @Test
    fun archive_hidesFromDefaultList_andReminderQueryCoversIt() = live {
        val at = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        val n = api.createNote(richDoc("Archive me"), reminderAt = at)
        val archived = api.patchNote(n.id, NotePatch.archived(true), n.revision)
        assertTrue(archived.archived)
        assertFalse(api.listAllNotes(NoteQuery()).any { it.id == n.id })
        assertTrue(api.listAllNotes(NoteQuery(archived = true)).any { it.id == n.id })
        // reminders on archived notes still need to fire
        assertFalse(api.listAllNotes(NoteQuery(hasReminder = true)).any { it.id == n.id })
        assertTrue(api.listAllNotesIncludingArchived(NoteQuery(hasReminder = true)).any { it.id == n.id })
        val back = api.patchNote(n.id, NotePatch.archived(false), archived.revision)
        assertTrue(api.listAllNotes(NoteQuery()).any { it.id == back.id })
        api.deleteNote(n.id, hard = true)
    }

    @Test
    fun revisions_listFetchRestore() = live {
        val v1 = api.createNote(richDoc("Version one"))
        val v2 = api.updateNote(v1.id, richDoc("Version two"), null, v1.revision)
        val fav = api.patchNote(v1.id, NotePatch.favorite(true), v2.revision) // metadata: no history row
        val history = api.revisions(v1.id)
        assertEquals(listOf(v2.revision, v1.revision), history.map { it.revision })
        assertEquals(richDoc("Version one"), api.revision(v1.id, v1.revision).body)
        val restored = api.restoreRevision(v1.id, v1.revision)
        assertEquals(richDoc("Version one"), restored.body)
        assertEquals(fav.revision + 1, restored.revision)
        assertTrue(restored.favorite)                       // metadata untouched
        assertEquals(3, api.revisions(v1.id).size)          // restore adds a new version
        assertFailsWith<ApiException.NotFound> { api.revision(v1.id, 999) }
        api.deleteNote(v1.id, hard = true)
    }

    @Test
    fun folders_renameAndDeleteBumpsRevision() = live {
        val f = api.createFolder("Old")
        assertEquals("New", api.renameFolder(f.id, "New").name)
        val n = api.createNote(richDoc("In folder"), folderId = f.id)
        api.deleteFolder(f.id)
        val after = api.getNote(n.id)
        assertNull(after.folderId)
        assertEquals(n.revision + 1, after.revision)
        // a stale PUT can't resurrect the folder link
        assertFailsWith<ApiException.Conflict> { api.updateNote(n.id, n.body, null, n.revision) }
        api.deleteNote(n.id, hard = true)
    }

    @Test
    fun attachments_uploadAndImageBlock() = live {
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        )
        val att = api.uploadAttachment("dot.png", "image/png", png)
        assertNotNull(att.id)
        val n = api.createNote(Doc(listOf(Block.Image(att.id, alt = "dot"))))
        assertEquals("[image: dot]", n.plaintext)
        api.deleteNote(n.id, hard = true)
    }

    @Test
    fun invalidDoc_is422Invalid() = live {
        // the server validator is the arbiter of the format: make sure our error path maps it
        val raw = Json.parseObject("""{"blocks":[{"type":"paragraph","spans":[{"text":"x","marks":["sparkle"]}]}]}""")
        val e = assertFailsWith<ApiException.Invalid> {
            api.createNote(Doc(listOf(Block.Unknown(raw.let {
                @Suppress("UNCHECKED_CAST")
                (it["blocks"] as List<Map<String, Any?>>)[0]
            }))))
        }
        assertTrue("sparkle" in (e.message ?: ""))
    }
}
