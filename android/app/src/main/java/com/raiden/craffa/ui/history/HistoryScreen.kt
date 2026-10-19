package com.raiden.craffa.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiden.craffa.AppContainer
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.data.model.RevisionInfo
import com.raiden.craffa.editor.DocDiff
import com.raiden.craffa.io
import com.raiden.craffa.ui.CollectMessages
import com.raiden.craffa.ui.Format
import com.raiden.craffa.ui.UiMessage
import com.raiden.craffa.ui.components.ConfirmDialog
import com.raiden.craffa.ui.components.ErrorState
import com.raiden.craffa.ui.components.FullScreenLoading
import com.raiden.craffa.ui.components.NoteRenderer
import com.raiden.craffa.ui.editor.DiffRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class HistoryViewModel(private val c: AppContainer, private val noteId: String) : ViewModel() {
    var current by mutableStateOf<Note?>(null); private set
    var revisions by mutableStateOf<List<RevisionInfo>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var selected by mutableStateOf<RevisionInfo?>(null); private set
    var selectedBody by mutableStateOf<Doc?>(null); private set
    var loadingVersion by mutableStateOf(false); private set
    var restoring by mutableStateOf(false); private set
    private val cache = HashMap<Long, Doc>()

    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val restored = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Every body change adds a history row, so the newest row is what the note shows now. */
    fun isCurrent(r: RevisionInfo) = r.revision == revisions.firstOrNull()?.revision

    init { load() }

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                val (note, revs) = io { c.api.getNote(noteId) to c.api.revisions(noteId) }
                current = note
                revisions = revs
                error = null
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    fun select(r: RevisionInfo?) {
        selected = r
        selectedBody = r?.let { cache[it.revision] }
        if (r == null || selectedBody != null) return
        loadingVersion = true
        viewModelScope.launch {
            try {
                val body = io { c.api.revision(noteId, r.revision) }.body
                cache[r.revision] = body
                if (selected?.revision == r.revision) selectedBody = body
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't load that version"))
                selected = null
            } finally {
                loadingVersion = false
            }
        }
    }

    fun restore(r: RevisionInfo) {
        restoring = true
        viewModelScope.launch {
            try {
                io { c.api.restoreRevision(noteId, r.revision) }
                restored.tryEmit(Unit)
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't restore"))
            } finally {
                restoring = false
            }
        }
    }

    fun attachmentUrl(id: String) = c.api.attachmentUrl(id)
}

@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, onRestored: () -> Unit, onOpenSettings: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(vm) { vm.restored.collect { onRestored() } }
    BackHandler(enabled = vm.selected != null) { vm.select(null) }
    var confirm by remember { mutableStateOf<RevisionInfo?>(null) }

    val selected = vm.selected
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selected != null) "Revision ${selected.revision}" else "Version history")
                        val sub = selected?.updatedAt?.let(Format::dateTime) ?: vm.current?.title
                        if (sub != null) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) vm.select(null) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selected != null && vm.selectedBody != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (vm.isCurrent(selected)) {
                            Text("This is what the note shows now.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        } else {
                            Text(
                                "Restoring keeps today's text in history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { confirm = selected }, enabled = !vm.restoring) {
                                if (vm.restoring) {
                                    CircularWavyProgressIndicator(Modifier.size(18.dp))
                                } else {
                                    Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.padding(start = 8.dp))
                                Text("Restore")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val error = vm.error
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.loading -> FullScreenLoading()
                error != null -> ErrorState(error, onRetry = vm::load, onOpenSettings = onOpenSettings)
                else -> AnimatedContent(
                    targetState = selected,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "history",
                ) { sel ->
                    if (sel == null) {
                        RevisionList(vm.revisions, vm::isCurrent, onSelect = vm::select)
                    } else {
                        VersionPreview(vm, vm.selectedBody)
                    }
                }
            }
        }
    }

    confirm?.let { r ->
        ConfirmDialog(
            title = "Restore revision ${r.revision}?",
            message = "The note's text goes back to this version (saved ${r.updatedAt?.let(Format::dateTime) ?: "earlier"}). " +
                "The current text stays in history, so you can come back to it.",
            confirmLabel = "Restore",
            onConfirm = { confirm = null; vm.restore(r) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun RevisionList(revisions: List<RevisionInfo>, isCurrent: (RevisionInfo) -> Boolean, onSelect: (RevisionInfo) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "The server keeps up to 50 versions of the text. " +
                    "Favorite, pin, folder and reminder changes don't make new versions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(revisions, key = { _, r -> r.revision }) { _, r ->
            ListItem(
                headlineContent = { Text(if (isCurrent(r)) "Current version" else "Revision ${r.revision}") },
                supportingContent = { Text(r.updatedAt?.let { "${Format.dateTime(it)} · ${Format.relative(it)}" } ?: "Unknown time") },
                leadingContent = {
                    Icon(
                        Icons.Outlined.History, null,
                        tint = if (isCurrent(r)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable { onSelect(r) }.animateItem(),
            )
        }
    }
}

@Composable
private fun VersionPreview(vm: HistoryViewModel, body: Doc?) {
    if (body == null) {
        FullScreenLoading()
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    val current = vm.current?.body ?: Doc.EMPTY
    val diff = remember(body, current) { DocDiff.diff(body, current) }
    val isCurrent = vm.selected?.let(vm::isCurrent) == true
    Column(Modifier.fillMaxSize()) {
        if (!isCurrent) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf("This version", "Changes since").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    ) { Text(label) }
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (tab == 0 || isCurrent) {
                NoteRenderer(body, vm::attachmentUrl)
            } else if (diff.none { it.op != DocDiff.Op.SAME }) {
                Text(
                    "Same text as now — only formatting, checkboxes or images differ.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "− only in this version   + added since",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (line in diff) DiffRow(line)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
