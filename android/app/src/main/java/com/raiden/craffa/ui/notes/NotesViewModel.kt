package com.raiden.craffa.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiden.craffa.AppContainer
import com.raiden.craffa.data.api.ApiException
import com.raiden.craffa.data.api.NotePatch
import com.raiden.craffa.data.api.NoteQuery
import com.raiden.craffa.data.model.Folder
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.io
import com.raiden.craffa.reminders.ReminderScheduler
import com.raiden.craffa.ui.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

sealed interface NoteFilter {
    data object All : NoteFilter
    data object Favorites : NoteFilter
    data class InFolder(val folderId: String) : NoteFilter
}

class NotesViewModel(private val c: AppContainer) : ViewModel() {
    var notes by mutableStateOf<List<Note>>(emptyList())
        private set
    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set
    var filter by mutableStateOf<NoteFilter>(NoteFilter.All)
        private set
    var query by mutableStateOf("")
        private set
    var initialLoading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set

    private var nextCursor: Int? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages

    val hasMore: Boolean get() = nextCursor != null

    private fun currentQuery() = NoteQuery(
        q = query.takeIf { it.isNotBlank() },
        favorite = if (filter == NoteFilter.Favorites) true else null,
        folderId = (filter as? NoteFilter.InFolder)?.folderId,
    )

    /** Reload the first page (and folders). [pull] shows the pull-to-refresh spinner. */
    fun refresh(pull: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (pull) refreshing = true
            try {
                val q = currentQuery()
                val (page, fs) = io { c.api.listNotes(q) to c.api.folders() }
                notes = page.items
                nextCursor = page.nextCursor
                folders = fs
                error = null
                // the filtered folder may have been deleted elsewhere
                val f = filter
                if (f is NoteFilter.InFolder && fs.none { it.id == f.folderId }) selectFilter(NoteFilter.All)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (notes.isEmpty()) error = e else _messages.tryEmit(UiMessage(e.message ?: "Couldn't refresh"))
            } finally {
                initialLoading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadingMore || loadJob?.isActive == true) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = io { c.api.listNotes(currentQuery().copy(cursor = cursor)) }
                val seen = notes.mapTo(HashSet()) { it.id }
                notes = notes + page.items.filter { it.id !in seen }
                nextCursor = page.nextCursor
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't load more"))
            } finally {
                loadingMore = false
            }
        }
    }

    fun onQueryChange(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    fun selectFilter(f: NoteFilter) {
        if (f == filter) return
        filter = f
        notes = emptyList()
        initialLoading = true
        refresh()
    }

    // ------------------------------------------------------------ note actions

    private fun replace(updated: Note) {
        notes = notes.map { if (it.id == updated.id) updated else it }
            .let { list ->
                // drop it from the view if it no longer matches the filter
                when (val f = filter) {
                    NoteFilter.Favorites -> list.filter { it.favorite }
                    is NoteFilter.InFolder -> list.filter { it.folderId == f.folderId }
                    NoteFilter.All -> list
                }
            }
            .filterNot { it.archived }
            .let { list -> if (query.isBlank()) pinnedFirst(list) else list }
    }

    /** The server's default order: pinned first, then most recently updated. */
    private fun pinnedFirst(list: List<Note>) =
        list.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })

    private fun patch(note: Note, patch: NotePatch, failMsg: String) {
        viewModelScope.launch {
            try {
                replace(io { c.api.patchNote(note.id, patch, note.revision) })
            } catch (e: ApiException.Conflict) {
                // changed on another device: apply on top of the fresh copy
                try {
                    replace(io { c.api.patchNote(note.id, patch, e.serverCopy.revision) })
                } catch (e2: Exception) {
                    _messages.tryEmit(UiMessage(e2.message ?: failMsg))
                }
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: failMsg))
            }
        }
    }

    fun toggleFavorite(note: Note) {
        // optimistic flip, the PATCH result replaces it
        notes = notes.map { if (it.id == note.id) it.copy(favorite = !note.favorite) else it }
        patch(note, NotePatch.favorite(!note.favorite), "Couldn't update favorite")
    }

    fun togglePin(note: Note) {
        notes = notes.map { if (it.id == note.id) it.copy(pinned = !note.pinned) else it }
            .let { if (query.isBlank()) pinnedFirst(it) else it }
        patch(note, NotePatch.pinned(!note.pinned), "Couldn't update pin")
    }

    fun archive(note: Note) {
        viewModelScope.launch {
            try {
                val updated = try {
                    io { c.api.patchNote(note.id, NotePatch.archived(true), note.revision) }
                } catch (e: ApiException.Conflict) {
                    io { c.api.patchNote(note.id, NotePatch.archived(true), e.serverCopy.revision) }
                }
                onArchivedElsewhere(updated.id, updated.title)
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't archive"))
            }
        }
    }

    /** A note was archived (here or from the editor): drop it and offer Undo. */
    fun onArchivedElsewhere(id: String, title: String? = null) {
        notes = notes.filterNot { it.id == id }
        _messages.tryEmit(
            UiMessage(
                text = if (title != null) "“${title.take(40)}” archived" else "Archived",
                actionLabel = "Undo",
                action = { unarchive(id) },
            ),
        )
    }

    private fun unarchive(id: String) {
        viewModelScope.launch {
            try {
                // no base_revision: only the archived flag changes
                io { c.api.patchNote(id, NotePatch.archived(false)) }
                refresh()
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't unarchive"))
            }
        }
    }

    fun moveToFolder(note: Note, folderId: String?) {
        if (note.folderId == folderId) return
        patch(note, NotePatch.folder(folderId), "Couldn't move note")
    }

    fun trash(note: Note) {
        viewModelScope.launch {
            try {
                io { c.api.deleteNote(note.id) }
                onTrashedElsewhere(note.id, note.title)
                if (note.reminderAt != null) ReminderScheduler.syncSoon(c)
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't move to trash"))
            }
        }
    }

    /** A note was trashed (here or from the editor): drop it and offer Undo. */
    fun onTrashedElsewhere(id: String, title: String? = null) {
        notes = notes.filterNot { it.id == id }
        _messages.tryEmit(
            UiMessage(
                text = if (title != null) "“${title.take(40)}” moved to trash" else "Moved to trash",
                actionLabel = "Undo",
                action = { restore(id) },
            ),
        )
    }

    private fun restore(id: String) {
        viewModelScope.launch {
            try {
                io { c.api.restoreNote(id) }
                refresh()
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't restore"))
            }
        }
    }

    // ------------------------------------------------------------ folders

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val f = io { c.api.createFolder(name) }
                folders = (folders + f).sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't create folder"))
            }
        }
    }

    fun renameFolder(folder: Folder, name: String) {
        if (name.isBlank() || name == folder.name) return
        viewModelScope.launch {
            try {
                val f = io { c.api.renameFolder(folder.id, name) }
                folders = folders.map { if (it.id == f.id) f else it }.sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't rename folder"))
            }
        }
    }

    fun deleteFolder(folder: Folder) {
        viewModelScope.launch {
            try {
                io { c.api.deleteFolder(folder.id) }
                folders = folders.filterNot { it.id == folder.id }
                if (filter == NoteFilter.InFolder(folder.id)) selectFilter(NoteFilter.All) else refresh()
                _messages.tryEmit(UiMessage("Folder “${folder.name}” deleted — its notes are unfiled"))
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage(e.message ?: "Couldn't delete folder"))
            }
        }
    }
}
