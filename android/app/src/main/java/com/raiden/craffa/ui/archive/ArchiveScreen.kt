package com.raiden.craffa.ui.archive

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiden.craffa.AppContainer
import com.raiden.craffa.data.api.ApiException
import com.raiden.craffa.data.api.NotePatch
import com.raiden.craffa.data.api.NoteQuery
import com.raiden.craffa.data.model.Folder
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.io
import com.raiden.craffa.ui.CollectMessages
import com.raiden.craffa.ui.UiMessage
import com.raiden.craffa.ui.components.BlobShape
import com.raiden.craffa.ui.components.EmptyState
import com.raiden.craffa.ui.components.ErrorState
import com.raiden.craffa.ui.components.FullScreenLoading
import com.raiden.craffa.ui.notes.CardAction
import com.raiden.craffa.ui.notes.NoteCard
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class ArchiveViewModel(private val c: AppContainer) : ViewModel() {
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var folders by mutableStateOf<List<Folder>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    private var nextCursor: Int? = null
    val hasMore get() = nextCursor != null
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    fun refresh(pull: Boolean = false) {
        viewModelScope.launch {
            if (pull) refreshing = true
            try {
                val (page, fs) = io { c.api.listNotes(NoteQuery(archived = true)) to c.api.folders() }
                notes = page.items
                folders = fs
                nextCursor = page.nextCursor
                error = null
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = io { c.api.listNotes(NoteQuery(archived = true, cursor = cursor)) }
                notes = notes + page.items.filter { n -> notes.none { it.id == n.id } }
                nextCursor = page.nextCursor
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't load more"))
            } finally {
                loadingMore = false
            }
        }
    }

    private fun setArchived(note: Note, archived: Boolean) {
        viewModelScope.launch {
            try {
                try {
                    io { c.api.patchNote(note.id, NotePatch.archived(archived), note.revision) }
                } catch (e: ApiException.Conflict) {
                    io { c.api.patchNote(note.id, NotePatch.archived(archived), e.serverCopy.revision) }
                }
                if (!archived) {
                    notes = notes.filterNot { it.id == note.id }
                    messages.tryEmit(UiMessage("“${note.title.take(40)}” is back in your notes", "Undo") { setArchived(note, true) })
                } else {
                    refresh()
                }
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't update"))
            }
        }
    }

    fun unarchive(note: Note) = setArchived(note, false)

    fun toggleFavorite(note: Note) {
        viewModelScope.launch {
            try {
                val updated = io { c.api.patchNote(note.id, NotePatch.favorite(!note.favorite)) }
                notes = notes.map { if (it.id == updated.id) updated else it }
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't update favorite"))
            }
        }
    }

    fun trash(note: Note) {
        viewModelScope.launch {
            try {
                io { c.api.deleteNote(note.id) }
                notes = notes.filterNot { it.id == note.id }
                messages.tryEmit(UiMessage("Moved to trash"))
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't move to trash"))
            }
        }
    }
}

@Composable
fun ArchiveScreen(
    vm: ArchiveViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(Unit) { vm.refresh() }   // also after coming back from the editor

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 3 }
    }
    LaunchedEffect(nearEnd, vm.hasMore) { if (nearEnd && vm.hasMore) vm.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archive") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val error = vm.error
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh(pull = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                vm.loading -> FullScreenLoading()
                error != null -> ErrorState(error, onRetry = { vm.refresh() }, onOpenSettings = onOpenSettings)
                vm.notes.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            Icons.Outlined.Archive, "Nothing archived",
                            "Archive notes you're done with but want to keep — they leave your list, not your drive.",
                            shape = BlobShape.Pill,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                    items(vm.notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            folderName = vm.folders.firstOrNull { it.id == note.folderId }?.name,
                            showFolder = true,
                            onClick = { onOpenNote(note.id) },
                            onToggleFavorite = { vm.toggleFavorite(note) },
                            actions = listOf(
                                CardAction("Unarchive", Icons.Outlined.Unarchive) { vm.unarchive(note) },
                                CardAction("Move to trash", Icons.Outlined.Delete) { vm.trash(note) },
                            ),
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (vm.loadingMore) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
                }
            }
        }
    }
}
