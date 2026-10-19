package com.raiden.craffa.ui.trash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiden.craffa.AppContainer
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.io
import com.raiden.craffa.ui.CollectMessages
import com.raiden.craffa.ui.Format
import com.raiden.craffa.ui.UiMessage
import com.raiden.craffa.ui.components.BlobShape
import com.raiden.craffa.ui.components.ConfirmDialog
import com.raiden.craffa.ui.components.EmptyState
import com.raiden.craffa.ui.components.ErrorState
import com.raiden.craffa.ui.components.FullScreenLoading
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class TrashViewModel(private val c: AppContainer) : ViewModel() {
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var loadingMore by mutableStateOf(false); private set
    var emptying by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    private var nextCursor: Int? = null
    val hasMore get() = nextCursor != null
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                val page = io { c.api.trash() }
                notes = page.items
                nextCursor = page.nextCursor
                error = null
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = io { c.api.trash(cursor = cursor) }
                notes = notes + page.items.filter { n -> notes.none { it.id == n.id } }
                nextCursor = page.nextCursor
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't load more"))
            } finally {
                loadingMore = false
            }
        }
    }

    fun restore(note: Note) {
        viewModelScope.launch {
            try {
                io { c.api.restoreNote(note.id) }
                notes = notes.filterNot { it.id == note.id }
                messages.tryEmit(UiMessage("“${note.title.take(40)}” restored"))
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't restore"))
            }
        }
    }

    fun deleteForever(note: Note) {
        viewModelScope.launch {
            try {
                io { c.api.deleteNote(note.id, hard = true) }
                notes = notes.filterNot { it.id == note.id }
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't delete"))
            }
        }
    }

    /** Purge everything in the trash (all pages). */
    fun emptyTrash() {
        viewModelScope.launch {
            emptying = true
            try {
                var removed = 0
                while (true) {
                    val page = io { c.api.trash(limit = 200) }
                    if (page.items.isEmpty()) break
                    page.items.forEach { io { c.api.deleteNote(it.id, hard = true) } }
                    removed += page.items.size
                }
                notes = emptyList()
                nextCursor = null
                messages.tryEmit(UiMessage("Deleted $removed note${if (removed == 1) "" else "s"} forever"))
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't empty trash"))
                refresh()
            } finally {
                emptying = false
            }
        }
    }
}

@Composable
fun TrashScreen(vm: TrashViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    var confirmDelete by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 3 }
    }
    LaunchedEffect(nearEnd, vm.hasMore) { if (nearEnd && vm.hasMore) vm.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (vm.notes.isNotEmpty()) {
                        IconButton(onClick = { confirmEmpty = true }, enabled = !vm.emptying) {
                            Icon(Icons.Outlined.DeleteSweep, "Empty trash")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val error = vm.error
        when {
            vm.loading -> FullScreenLoading(Modifier.padding(padding))
            error != null -> ErrorState(error, onRetry = vm::refresh, onOpenSettings = onOpenSettings, modifier = Modifier.padding(padding))
            vm.notes.isEmpty() -> EmptyState(
                Icons.Outlined.DeleteSweep, "Trash is empty",
                "Deleted notes wait here until you restore them or delete them for good.",
                shape = BlobShape.Sunny,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (vm.emptying) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(8.dp)) }
                items(vm.notes, key = { it.id }) { note ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).animateItem(),
                    ) {
                        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                            Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (note.snippet.isNotBlank()) {
                                Text(
                                    note.snippet, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Deleted ${Format.relative(note.deletedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                                TextButton(onClick = { confirmDelete = note }) {
                                    Icon(Icons.Outlined.DeleteForever, null)
                                    Spacer(Modifier.padding(start = 6.dp))
                                    Text("Delete forever")
                                }
                                FilledTonalButton(onClick = { vm.restore(note) }) {
                                    Icon(Icons.Outlined.Restore, null)
                                    Spacer(Modifier.padding(start = 6.dp))
                                    Text("Restore")
                                }
                            }
                        }
                    }
                }
                if (vm.loadingMore) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
            }
        }
    }

    confirmDelete?.let { n ->
        ConfirmDialog(
            title = "Delete forever?",
            message = "“${n.title}” and its images will be gone for good. This can't be undone.",
            confirmLabel = "Delete forever",
            onConfirm = { vm.deleteForever(n); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "Every note in the trash will be permanently deleted. This can't be undone.",
            confirmLabel = "Empty trash",
            onConfirm = { vm.emptyTrash(); confirmEmpty = false },
            onDismiss = { confirmEmpty = false },
        )
    }
}
