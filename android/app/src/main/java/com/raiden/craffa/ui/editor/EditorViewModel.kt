package com.raiden.craffa.ui.editor

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiden.craffa.AppContainer
import com.raiden.craffa.data.api.ApiException
import com.raiden.craffa.data.api.NotePatch
import com.raiden.craffa.data.model.Align
import com.raiden.craffa.data.model.Block
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.Folder
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.data.model.Mark
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.editor.EditorDoc
import com.raiden.craffa.editor.EditorLine
import com.raiden.craffa.editor.Focus
import com.raiden.craffa.editor.LineKind
import com.raiden.craffa.io
import com.raiden.craffa.reminders.ReminderScheduler
import com.raiden.craffa.ui.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

sealed interface SaveState {
    data object New : SaveState        // nothing written yet
    data object Saved : SaveState
    data object Editing : SaveState    // unsaved edits, autosave pending
    data object Saving : SaveState
    data class Failed(val message: String) : SaveState
    data object Conflict : SaveState
}

/** A 409: the server copy plus what we were trying to save. */
data class ConflictState(val server: Note, val mineDoc: Doc, val mineTitle: String?)

/** Ask a line to take focus with the caret at [caret]. [seq] makes repeats distinct. */
data class FocusTarget(val lineId: Long, val caret: Int, val seq: Long)

sealed interface EditorEvent {
    data object Close : EditorEvent
    data class Trashed(val id: String, val title: String) : EditorEvent
    data class Archived(val id: String, val title: String) : EditorEvent
    data class OpenHistory(val id: String) : EditorEvent
}

class EditorViewModel(
    private val c: AppContainer,
    private val noteId: String?,
    startAsChecklist: Boolean,
    private val newNoteFolderId: String?,
) : ViewModel() {

    var loading by mutableStateOf(noteId != null); private set
    var loadError by mutableStateOf<Throwable?>(null); private set
    var note by mutableStateOf<Note?>(null); private set
    var lines by mutableStateOf(emptyList<EditorLine>()); private set
    var title by mutableStateOf(""); private set
    var autoTitle by mutableStateOf("Title"); private set
    var folders by mutableStateOf(emptyList<Folder>()); private set
    var saveState by mutableStateOf<SaveState>(SaveState.New); private set
    var conflict by mutableStateOf<ConflictState?>(null); private set
    var showConflict by mutableStateOf(false)
    var uploading by mutableStateOf(false); private set
    var leaveBlocked by mutableStateOf<String?>(null)   // error text when back couldn't save

    // caret / formatting state
    var focusedLineId by mutableStateOf<Long?>(null); private set
    var selection by mutableStateOf(TextRange.Zero); private set
    var typingMarks by mutableStateOf<Set<Mark>?>(null); private set
    private var typingAt: Pair<Long, Int>? = null
    var focusTarget by mutableStateOf<FocusTarget?>(null); private set
    private var focusSeq = 0L

    private val writeMutex = Mutex()
    private var saveJob: Job? = null
    private var editSeq = 0L
    private var savedSeq = 0L

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages
    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<EditorEvent> = _events

    init {
        if (noteId == null) {
            val first = EditorLine(
                EditorDoc.newId(),
                if (startAsChecklist) LineKind.Item(ListKind.CHECK) else LineKind.Paragraph,
            )
            lines = listOf(first)
            requestFocus(Focus(first.id, 0))
        } else {
            load()
        }
        viewModelScope.launch { runCatching { folders = io { c.api.folders() } } }
    }

    /** (Re)load from the server — also used after restoring an old version. */
    fun load() {
        val id = note?.id ?: noteId ?: return
        saveJob?.cancel()
        conflict = null
        showConflict = false
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                adopt(io { c.api.getNote(id) })
                saveState = SaveState.Saved
            } catch (e: Exception) {
                loadError = e
            } finally {
                loading = false
            }
        }
    }

    /** Take a server note as the editor content. */
    private fun adopt(n: Note) {
        note = n
        lines = EditorDoc.toLines(n.body)
        title = if (n.hasAutoTitle) "" else n.title
        autoTitle = n.title.takeIf { n.hasAutoTitle && it != "Untitled" } ?: "Title"
        editSeq = 0
        savedSeq = 0
        typingMarks = null
    }

    private val isDirty get() = editSeq != savedSeq

    private fun onEdited() {
        editSeq++
        if (conflict != null) return
        saveState = SaveState.Editing
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1200)
            save()
        }
    }

    // ------------------------------------------------------------ saving

    suspend fun save(): Boolean = writeMutex.withLock { saveLocked() }

    fun saveNow() {
        saveJob?.cancel()
        if (isDirty) viewModelScope.launch { save() }
    }

    fun retrySave() {
        saveJob?.cancel()
        viewModelScope.launch { save() }
    }

    private fun currentTitle(): String? = title.trim().ifBlank { null }

    private fun storedCustomTitle(n: Note): String? = if (n.hasAutoTitle) null else n.title

    private suspend fun saveLocked(force: Boolean = false): Boolean {
        if (conflict != null) return false
        val seq = editSeq
        val doc = EditorDoc.toDoc(lines)
        val t = currentTitle()
        val n = note
        try {
            if (n == null) {
                if (!force && isBlank(doc) && t == null) {
                    savedSeq = seq
                    return true
                }
                saveState = SaveState.Saving
                val created = io { c.api.createNote(doc, t, folderId = newNoteFolderId) }
                note = created
            } else {
                if (doc == n.body && t == storedCustomTitle(n)) {
                    savedSeq = seq
                    saveState = SaveState.Saved
                    return true
                }
                saveState = SaveState.Saving
                note = try {
                    io { c.api.updateNote(n.id, doc, t, n.revision) }
                } catch (e: ApiException.Conflict) {
                    val s = e.serverCopy
                    // Only metadata moved on the server (favorite, folder, reminder —
                    // e.g. "Snooze" from a notification): content is still what we
                    // started from, so re-save on the new revision. Not a merge.
                    if (s.body == n.body && s.title == n.title) {
                        io { c.api.updateNote(n.id, doc, t, s.revision) }
                    } else {
                        throw e
                    }
                }
            }
            savedSeq = seq
            note?.let { if (it.hasAutoTitle) autoTitle = it.title.takeIf { s -> s != "Untitled" } ?: "Title" }
            saveState = if (isDirty) SaveState.Editing else SaveState.Saved
            if (isDirty) onEditedAgainLater()
            return true
        } catch (e: ApiException.Conflict) {
            conflict = ConflictState(e.serverCopy, doc, t)
            showConflict = true
            saveState = SaveState.Conflict
            return false
        } catch (e: Exception) {
            saveState = SaveState.Failed(e.message ?: "Couldn't save")
            return false
        }
    }

    /** Edits arrived while a save was in flight — schedule another round. */
    private fun onEditedAgainLater() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            save()
        }
    }

    private fun isBlank(doc: Doc) = doc.blocks.all {
        (it is Block.Paragraph && it.spans.all { s -> s.text.isBlank() }) ||
            (it is Block.ListBlock && it.items.all { i -> i.spans.all { s -> s.text.isBlank() } })
    }

    /** Back pressed: save first; leave only if that worked (or there was nothing to save). */
    fun onBack() {
        saveJob?.cancel()
        viewModelScope.launch {
            if (conflict != null) {
                showConflict = true
                return@launch
            }
            if (!isDirty || save()) {
                _events.tryEmit(EditorEvent.Close)
            } else if (conflict == null) {
                leaveBlocked = (saveState as? SaveState.Failed)?.message ?: "Couldn't save your changes."
            }
        }
    }

    fun discardAndLeave() {
        leaveBlocked = null
        _events.tryEmit(EditorEvent.Close)
    }

    // ------------------------------------------------------------ conflicts

    fun keepMine() {
        val cf = conflict ?: return
        viewModelScope.launch {
            writeMutex.withLock {
                saveState = SaveState.Saving
                try {
                    // re-base our current content on the server's revision
                    note = io { c.api.updateNote(cf.server.id, EditorDoc.toDoc(lines), currentTitle(), cf.server.revision) }
                    conflict = null
                    showConflict = false
                    savedSeq = editSeq
                    saveState = SaveState.Saved
                    _messages.tryEmit(UiMessage("Kept your version"))
                } catch (e: ApiException.Conflict) {
                    conflict = cf.copy(server = e.serverCopy)  // it moved again; show the newer copy
                    saveState = SaveState.Conflict
                } catch (e: Exception) {
                    saveState = SaveState.Conflict
                    _messages.tryEmit(UiMessage(e.message ?: "Couldn't save"))
                }
            }
        }
    }

    fun useServer() {
        val cf = conflict ?: return
        conflict = null
        showConflict = false
        adopt(cf.server)
        saveState = SaveState.Saved
        focusedLineId = null
        _messages.tryEmit(UiMessage("Loaded the server version"))
    }

    // ------------------------------------------------------------ metadata (PATCH)

    /**
     * Save pending edits, then PATCH with the current revision. If the server
     * moved on but its body is what we have, retry on top; otherwise it's a
     * real content conflict and the user decides.
     */
    private fun patch(p: NotePatch, failMsg: String, after: (Note) -> Unit = {}) {
        viewModelScope.launch {
            writeMutex.withLock {
                if (note == null || isDirty) {
                    if (!saveLocked(force = true)) return@withLock
                }
                val n = note ?: return@withLock
                try {
                    val updated = try {
                        io { c.api.patchNote(n.id, p, n.revision) }
                    } catch (e: ApiException.Conflict) {
                        if (e.serverCopy.body == EditorDoc.toDoc(lines)) {
                            io { c.api.patchNote(n.id, p, e.serverCopy.revision) }
                        } else {
                            conflict = ConflictState(e.serverCopy, EditorDoc.toDoc(lines), currentTitle())
                            showConflict = true
                            saveState = SaveState.Conflict
                            return@withLock
                        }
                    }
                    note = updated
                    after(updated)
                } catch (e: Exception) {
                    _messages.tryEmit(UiMessage(e.message ?: failMsg))
                }
            }
        }
    }

    fun toggleFavorite() {
        val fav = note?.favorite ?: false
        patch(NotePatch.favorite(!fav), "Couldn't update favorite")
    }

    fun togglePin() {
        val pinned = note?.pinned ?: false
        patch(NotePatch.pinned(!pinned), "Couldn't update pin") { n ->
            _messages.tryEmit(UiMessage(if (n.pinned) "Pinned to the top" else "Unpinned"))
        }
    }

    fun setArchived(archived: Boolean) {
        patch(NotePatch.archived(archived), if (archived) "Couldn't archive" else "Couldn't unarchive") { n ->
            if (n.archived) _events.tryEmit(EditorEvent.Archived(n.id, n.title))
            else _messages.tryEmit(UiMessage("Back in your notes"))
        }
    }

    /** Version history needs the latest text on the server first. */
    fun openHistory() {
        saveJob?.cancel()
        viewModelScope.launch {
            if (conflict != null) {
                showConflict = true
                return@launch
            }
            if (isDirty && !save()) {
                if (conflict == null) _messages.tryEmit(UiMessage("Save your changes before opening history"))
                return@launch
            }
            val n = note
            if (n == null) _messages.tryEmit(UiMessage("No history yet — start writing first"))
            else _events.tryEmit(EditorEvent.OpenHistory(n.id))
        }
    }

    fun moveToFolder(folderId: String?) {
        if (note?.folderId == folderId && note != null) return
        patch(NotePatch.folder(folderId), "Couldn't move note") { n ->
            val name = folders.firstOrNull { it.id == n.folderId }?.name
            _messages.tryEmit(UiMessage(if (name != null) "Moved to $name" else "Removed from folder"))
        }
    }

    fun setReminder(at: Instant?) {
        patch(NotePatch.reminder(at), "Couldn't set reminder") {
            ReminderScheduler.syncSoon(c)
            if (at != null) _messages.tryEmit(UiMessage("Reminder set for ${com.raiden.craffa.ui.Format.reminder(at)}"))
        }
    }

    fun trash() {
        viewModelScope.launch {
            writeMutex.withLock {
                saveJob?.cancel()
                val n = note
                if (n == null) {
                    _events.tryEmit(EditorEvent.Close)  // never saved: nothing to trash
                    return@withLock
                }
                if (isDirty) saveLocked()  // so a restore brings the latest text back
                try {
                    io { c.api.deleteNote(n.id) }
                    if (n.reminderAt != null) ReminderScheduler.syncSoon(c)
                    _events.tryEmit(EditorEvent.Trashed(n.id, note?.title ?: n.title))
                } catch (e: Exception) {
                    _messages.tryEmit(UiMessage(e.message ?: "Couldn't move to trash"))
                }
            }
        }
    }

    // ------------------------------------------------------------ title

    fun onTitleChange(t: String) {
        title = t.replace('\n', ' ')
        onEdited()
    }

    // ------------------------------------------------------------ text editing

    private fun indexOf(lineId: Long) = lines.indexOfFirst { it.id == lineId }

    private fun requestFocus(f: Focus) {
        focusTarget = FocusTarget(f.lineId, f.caret, ++focusSeq)
        focusedLineId = f.lineId
        selection = TextRange(f.caret)
    }

    fun onFocusConsumed(seq: Long) {
        if (focusTarget?.seq == seq) focusTarget = null
    }

    fun onLineFocused(lineId: Long, sel: TextRange) {
        if (focusedLineId != lineId) typingMarks = null
        focusedLineId = lineId
        selection = sel
    }

    /** A line's text field changed. [text]/[sel] exclude the field's sentinel. */
    fun onLineValueChange(lineId: Long, text: String, sel: TextRange) {
        val idx = indexOf(lineId)
        if (idx < 0) return
        val line = lines[idx]
        focusedLineId = lineId
        if (text == line.text.text) {
            if (typingAt != lineId to sel.end || !sel.collapsed) typingMarks = null
            selection = sel
            return
        }
        val typing = typingMarks.takeIf { typingAt?.first == lineId }
        val rich = line.text.applyEdit(text, caret = sel.end, typingMarks = typing)
        typingMarks = null
        val before = lines
        val r = EditorDoc.replaceText(lines, idx, rich, sel.end)
        lines = r.lines
        val f = r.focus
        if (f != null && (f.lineId != lineId || before.size != r.lines.size || r.lines[idx].kind != line.kind)) {
            requestFocus(f)
        } else {
            selection = sel
        }
        onEdited()
    }

    fun onBackspaceAtStart(lineId: Long) {
        val idx = indexOf(lineId)
        if (idx < 0) return
        val r = EditorDoc.backspaceAtStart(lines, idx)
        if (r.lines == lines) return
        lines = r.lines
        r.focus?.let(::requestFocus)
        onEdited()
    }

    fun focusEnd() {
        val last = lines.lastOrNull { it.kind.isText } ?: return
        requestFocus(Focus(last.id, last.text.length))
    }

    // ------------------------------------------------------------ formatting

    private val focusedIndex: Int get() = focusedLineId?.let(::indexOf) ?: -1

    val focusedLine: EditorLine? get() = focusedIndex.takeIf { it >= 0 }?.let { lines[it] }

    fun isMarkActive(mark: Mark): Boolean {
        val line = focusedLine ?: return false
        if (!line.kind.isText) return false
        val s = selection
        return if (s.collapsed) {
            val pos = s.start.coerceIn(0, line.text.length)
            if (mark is Mark.Link) return line.text.linkAt(pos) != null
            mark in (typingMarks ?: line.text.insertionMarks(pos))
        } else {
            line.text.hasMark(s.min.coerceAtMost(line.text.length), s.max.coerceAtMost(line.text.length), mark)
        }
    }

    fun toggleMark(mark: Mark) {
        val idx = focusedIndex.takeIf { it >= 0 } ?: return
        val line = lines[idx]
        if (!line.kind.isText) return
        val s = selection
        if (s.collapsed) {
            val pos = s.start.coerceIn(0, line.text.length)
            val base = typingMarks ?: line.text.insertionMarks(pos)
            typingMarks = if (mark in base) base - mark else base + mark
            typingAt = line.id to pos
        } else {
            val a = s.min.coerceAtMost(line.text.length)
            val b = s.max.coerceAtMost(line.text.length)
            lines = lines.toMutableList().also { it[idx] = line.copy(text = line.text.toggle(a, b, mark)) }
            onEdited()
        }
    }

    /** What the link dialog should show: existing href, whether text must be typed. */
    fun linkContext(): Pair<String?, Boolean>? {
        val line = focusedLine ?: return null
        if (!line.kind.isText) return null
        val s = selection
        val pos = s.start.coerceIn(0, line.text.length)
        val existing = line.text.linkAt(if (s.collapsed) pos else s.min)?.href
        val needsText = s.collapsed && existing == null
        return existing to needsText
    }

    fun applyLink(href: String?, insertText: String?) {
        val idx = focusedIndex.takeIf { it >= 0 } ?: return
        val line = lines[idx]
        val s = selection
        val len = line.text.length
        val newText = when {
            !s.collapsed -> line.text.setLink(s.min.coerceAtMost(len), s.max.coerceAtMost(len), href)
            line.text.linkRange(s.start.coerceIn(0, len)) != null -> {
                val r = line.text.linkRange(s.start.coerceIn(0, len))!!
                line.text.setLink(r.first, r.last + 1, href)
            }
            !insertText.isNullOrBlank() && !href.isNullOrBlank() -> {
                val pos = s.start.coerceIn(0, len)
                val marks = line.text.insertionMarks(pos).filterNot { it is Mark.Link }.toSet() + Mark.Link(href.trim())
                val inserted = line.text.insert(pos, insertText, marks)
                lines = lines.toMutableList().also { it[idx] = line.copy(text = inserted) }
                requestFocus(Focus(line.id, pos + insertText.length))
                onEdited()
                return
            }
            else -> return
        }
        lines = lines.toMutableList().also { it[idx] = line.copy(text = newText) }
        onEdited()
    }

    private fun blockOp(op: (List<EditorLine>, Int) -> List<EditorLine>) {
        val idx = focusedIndex.takeIf { it >= 0 } ?: return
        val updated = op(lines, idx)
        if (updated == lines) return
        lines = updated
        // keep caret where it was
        focusedLineId?.let { requestFocus(Focus(it, selection.end)) }
        onEdited()
    }

    fun toggleHeading(level: Int) = blockOp { l, i -> EditorDoc.toggleHeading(l, i, level) }
    fun toggleList(kind: ListKind) = blockOp { l, i -> EditorDoc.toggleList(l, i, kind) }
    fun toggleQuote() = blockOp { l, i -> EditorDoc.toggleQuote(l, i) }
    fun toggleCodeBlock() = blockOp { l, i -> EditorDoc.toggleCode(l, i) }

    fun cycleAlign() = blockOp { l, i ->
        val next = when (l[i].align) {
            Align.LEFT -> Align.CENTER
            Align.CENTER -> Align.RIGHT
            Align.RIGHT -> Align.LEFT
        }
        EditorDoc.setAlign(l, i, next)
    }

    fun toggleChecked(lineId: Long) {
        val idx = indexOf(lineId)
        if (idx < 0) return
        lines = EditorDoc.toggleChecked(lines, idx)
        onEdited()
    }

    fun insertDivider() {
        val idx = if (focusedIndex >= 0) focusedIndex else lines.size - 1
        val r = EditorDoc.insertBlock(lines, idx, LineKind.Divider)
        lines = r.lines
        r.focus?.let(::requestFocus)
        onEdited()
    }

    fun removeLine(lineId: Long) {
        val idx = indexOf(lineId)
        if (idx < 0) return
        lines = EditorDoc.removeLine(lines, idx)
        onEdited()
    }

    // ------------------------------------------------------------ images

    fun insertImage(uri: Uri, resolver: ContentResolver) {
        val atIndex = if (focusedIndex >= 0) focusedIndex else lines.size - 1
        viewModelScope.launch {
            uploading = true
            try {
                val (name, mime, bytes) = io {
                    val mime = resolver.getType(uri) ?: "image/jpeg"
                    var name = "image"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                        if (cur.moveToFirst()) name = cur.getString(0) ?: name
                    }
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMAGE_BYTES) throw IllegalArgumentException("That image is over 25 MB")
                            buf.write(chunk, 0, n)
                        }
                        buf.toByteArray()
                    } ?: throw IllegalStateException("Couldn't open that image")
                    Triple(name, mime, bytes)
                }
                val att = io { c.api.uploadAttachment(name, mime, bytes, note?.id) }
                val idx = atIndex.coerceIn(0, lines.size - 1)
                val r = EditorDoc.insertBlock(lines, idx, LineKind.Image(att.id, name.substringBeforeLast('.').take(80)))
                lines = r.lines
                r.focus?.let(::requestFocus)
                onEdited()
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Image upload failed"))
            } finally {
                uploading = false
            }
        }
    }

    fun attachmentUrl(id: String) = c.api.attachmentUrl(id)

    override fun onCleared() {
        // Last-chance save (e.g. the system tore the screen down) on the app scope.
        if (isDirty && conflict == null) {
            val n = note
            val doc = EditorDoc.toDoc(lines)
            val t = currentTitle()
            c.appScope.launch {
                runCatching {
                    if (n == null) {
                        if (!isBlank(doc) || t != null) c.api.createNote(doc, t, folderId = newNoteFolderId)
                    } else {
                        c.api.updateNote(n.id, doc, t, n.revision)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    }
}
