package com.raiden.craffa.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raiden.craffa.data.model.DocText
import com.raiden.craffa.data.model.Folder
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.ui.CollectMessages
import com.raiden.craffa.ui.Format
import com.raiden.craffa.ui.components.BlobShape
import com.raiden.craffa.ui.components.EmptyState
import com.raiden.craffa.ui.components.ErrorState
import com.raiden.craffa.ui.components.FolderManagerSheet
import com.raiden.craffa.ui.components.FolderPickerSheet
import com.raiden.craffa.ui.components.FullScreenLoading
import com.raiden.craffa.ui.components.SectionHeader
import java.time.Instant

@Composable
fun NotesScreen(
    vm: NotesViewModel,
    trashedNote: Pair<String, String>?,
    archivedNote: Pair<String, String>?,
    onTrashedConsumed: () -> Unit,
    onArchivedConsumed: () -> Unit,
    onOpenNote: (String) -> Unit,
    onNewNote: (checklist: Boolean) -> Unit,
    onOpenReminders: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)

    // Silent refresh whenever we come back to the list (e.g. from the editor).
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(trashedNote) {
        trashedNote?.let { (id, title) ->
            vm.onTrashedElsewhere(id, title)
            onTrashedConsumed()
        }
    }
    LaunchedEffect(archivedNote) {
        archivedNote?.let { (id, title) ->
            vm.onArchivedElsewhere(id, title)
            onArchivedConsumed()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var managingFolders by remember { mutableStateOf(false) }
    var movingNote by remember { mutableStateOf<Note?>(null) }

    BackHandler(enabled = fabExpanded) { fabExpanded = false }

    // Infinite scroll: load the next page near the end.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, vm.hasMore) { if (nearEnd && vm.hasMore) vm.loadMore() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Notes") },
                actions = {
                    IconButton(onClick = onOpenReminders) { Icon(Icons.Outlined.Notifications, "Reminders") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                                onClick = { menuOpen = false; onOpenArchive() },
                            )
                            DropdownMenuItem(
                                text = { Text("Trash") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menuOpen = false; onOpenTrash() },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menuOpen = false; onOpenSettings() },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabExpanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = fabExpanded,
                        onCheckedChange = { fabExpanded = !fabExpanded },
                    ) {
                        val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Outlined.Close else Icons.Outlined.Add } }
                        Icon(icon, contentDescription = if (fabExpanded) "Close menu" else "New")
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; onNewNote(true) },
                    icon = { Icon(Icons.Outlined.Checklist, null) },
                    text = { Text("Checklist") },
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; onNewNote(false) },
                    icon = { Icon(Icons.AutoMirrored.Outlined.Notes, null) },
                    text = { Text("Note") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh(pull = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 120.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "search") {
                    SearchField(vm.query, vm::onQueryChange)
                }
                item(key = "folders") {
                    FolderChips(
                        folders = vm.folders,
                        filter = vm.filter,
                        onSelect = vm::selectFilter,
                        onManage = { managingFolders = true },
                    )
                }
                val error = vm.error
                when {
                    vm.initialLoading -> item(key = "loading") { FullScreenLoading(Modifier.height(320.dp)) }
                    error != null && vm.notes.isEmpty() -> item(key = "error") {
                        ErrorState(error, onRetry = { vm.refresh() }, onOpenSettings = onOpenSettings)
                    }
                    vm.notes.isEmpty() -> item(key = "empty") {
                        when {
                            vm.query.isNotBlank() -> EmptyState(
                                Icons.Outlined.SearchOff, "Nothing matches “${vm.query}”",
                                "Search matches whole words — try a full word, or search in All notes.", shape = BlobShape.Flower,
                            )
                            vm.filter == NoteFilter.Favorites -> EmptyState(
                                Icons.Outlined.StarBorder, "No favorites yet",
                                "Tap the star on a note to keep it here.", shape = BlobShape.Sunny,
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            vm.filter is NoteFilter.InFolder -> EmptyState(
                                Icons.Outlined.Folder, "This folder is empty",
                                "Move notes here from their ⋮ menu, or start a new one.", shape = BlobShape.Pill,
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            else -> EmptyState(
                                Icons.Outlined.Edit, "A blank page!",
                                "Your homelab is ready for its first note. Tap + to write one.",
                            )
                        }
                    }
                    else -> {
                        // Keep-style sections when browsing (search results stay relevance-ranked).
                        val sectioned = vm.query.isBlank() && vm.notes.any { it.pinned }
                        val pinned = if (sectioned) vm.notes.filter { it.pinned } else emptyList()
                        val others = if (sectioned) vm.notes.filterNot { it.pinned } else vm.notes
                        val card: @Composable (Note, Modifier) -> Unit = { note, m ->
                            NoteCard(
                                note = note,
                                folderName = vm.folders.firstOrNull { it.id == note.folderId }?.name,
                                showFolder = vm.filter !is NoteFilter.InFolder,
                                onClick = { onOpenNote(note.id) },
                                onToggleFavorite = { vm.toggleFavorite(note) },
                                actions = listOf(
                                    CardAction(if (note.pinned) "Unpin" else "Pin to top", Icons.Outlined.PushPin) { vm.togglePin(note) },
                                    CardAction(if (note.favorite) "Remove from favorites" else "Add to favorites",
                                        if (note.favorite) Icons.Outlined.StarBorder else Icons.Filled.Star) { vm.toggleFavorite(note) },
                                    CardAction("Move to folder…", Icons.Outlined.DriveFileMove) { movingNote = note },
                                    CardAction("Archive", Icons.Outlined.Archive) { vm.archive(note) },
                                    CardAction("Move to trash", Icons.Outlined.Delete) { vm.trash(note) },
                                ),
                                modifier = m,
                            )
                        }
                        if (sectioned) {
                            item(key = "h-pinned") { SectionHeader("Pinned", Modifier.animateItem()) }
                            items(pinned, key = { it.id }) { note -> card(note, Modifier.animateItem()) }
                            if (others.isNotEmpty()) item(key = "h-others") { SectionHeader("Others", Modifier.animateItem()) }
                        }
                        items(others, key = { it.id }) { note -> card(note, Modifier.animateItem()) }
                        if (vm.loadingMore) {
                            item(key = "more") {
                                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (managingFolders) {
        FolderManagerSheet(
            folders = vm.folders,
            onCreate = vm::createFolder,
            onRename = vm::renameFolder,
            onDelete = vm::deleteFolder,
            onDismiss = { managingFolders = false },
        )
    }
    movingNote?.let { note ->
        FolderPickerSheet(
            folders = vm.folders,
            currentId = note.folderId,
            onPick = { vm.moveToFolder(note, it); movingNote = null },
            onDismiss = { movingNote = null },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search notes") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Clear search") }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun FolderChips(
    folders: List<Folder>,
    filter: NoteFilter,
    onSelect: (NoteFilter) -> Unit,
    onManage: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = filter == NoteFilter.All, onClick = { onSelect(NoteFilter.All) }, label = { Text("All") })
        }
        item {
            FilterChip(
                selected = filter == NoteFilter.Favorites,
                onClick = { onSelect(NoteFilter.Favorites) },
                label = { Text("Favorites") },
                leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) },
            )
        }
        items(folders, key = { it.id }) { f ->
            val selected = filter == NoteFilter.InFolder(f.id)
            FilterChip(
                selected = selected,
                onClick = { onSelect(if (selected) NoteFilter.All else NoteFilter.InFolder(f.id)) },
                label = { Text(f.name) },
                leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)) },
            )
        }
        item {
            AssistChip(
                onClick = onManage,
                label = { Text(if (folders.isEmpty()) "New folder" else "Folders") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}
