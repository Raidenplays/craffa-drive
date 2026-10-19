package com.raiden.craffa.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.raiden.craffa.data.model.Align
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.editor.EditorDoc
import com.raiden.craffa.editor.EditorLine
import com.raiden.craffa.editor.LineKind
import com.raiden.craffa.ui.CollectMessages
import com.raiden.craffa.ui.Format
import com.raiden.craffa.ui.components.ErrorState
import com.raiden.craffa.ui.components.FolderPickerSheet
import com.raiden.craffa.ui.components.FullScreenLoading
import com.raiden.craffa.ui.notes.Pill
import com.raiden.craffa.ui.theme.NoteText
import java.time.Instant

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onClose: () -> Unit,
    onTrashed: (id: String, title: String) -> Unit,
    onArchived: (id: String, title: String) -> Unit,
    onOpenHistory: (id: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                EditorEvent.Close -> onClose()
                is EditorEvent.Trashed -> onTrashed(e.id, e.title)
                is EditorEvent.Archived -> onArchived(e.id, e.title)
                is EditorEvent.OpenHistory -> onOpenHistory(e.id)
            }
        }
    }
    // Save when the app goes to the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveNow() }
    BackHandler { vm.onBack() }

    var menuOpen by remember { mutableStateOf(false) }
    var pickingFolder by remember { mutableStateOf(false) }
    var pickingReminder by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf<Pair<String?, Boolean>?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.insertImage(uri, context.contentResolver)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val note = vm.note
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { SaveIndicator(vm.saveState, onRetry = vm::retrySave, onShowConflict = { vm.showConflict = true }) },
                actions = {
                    if (!vm.loading && vm.loadError == null) {
                        IconButton(onClick = { vm.togglePin() }) {
                            Icon(
                                if (note?.pinned == true) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (note?.pinned == true) "Unpin" else "Pin",
                                tint = if (note?.pinned == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.toggleFavorite() }) {
                            Icon(
                                if (note?.favorite == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (note?.favorite == true) "Unfavorite" else "Favorite",
                                tint = if (note?.favorite == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { pickingReminder = true }) {
                            Icon(
                                if (note?.reminderAt != null) Icons.Filled.Notifications else Icons.Outlined.NotificationAdd,
                                contentDescription = "Reminder",
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Move to folder…") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
                                    onClick = { menuOpen = false; pickingFolder = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (note?.archived == true) "Unarchive" else "Archive") },
                                    leadingIcon = { Icon(if (note?.archived == true) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                                    onClick = { menuOpen = false; vm.setArchived(note?.archived != true) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Version history") },
                                    leadingIcon = { Icon(Icons.Outlined.History, null) },
                                    enabled = note != null,
                                    onClick = { menuOpen = false; vm.openHistory() },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (note == null) "Discard" else "Move to trash") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menuOpen = false; vm.trash() },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val loadError = vm.loadError
        when {
            vm.loading -> FullScreenLoading(Modifier.padding(padding))
            loadError != null -> ErrorState(loadError, onRetry = vm::load, onOpenSettings = onOpenSettings, modifier = Modifier.padding(padding))
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp),
                ) {
                    if (note?.archived == true) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp)) {
                                Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Archived", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.setArchived(false) }) { Text("Unarchive") }
                            }
                        }
                    }
                    TitleField(vm.title, vm.autoTitle, vm::onTitleChange)
                    MetaRow(
                        reminderAt = note?.reminderAt,
                        folderName = vm.folders.firstOrNull { it.id == note?.folderId }?.name,
                        onReminder = { pickingReminder = true },
                        onFolder = { pickingFolder = true },
                    )
                    Spacer(Modifier.height(8.dp))
                    val lines = vm.lines
                    lines.forEachIndexed { index, line ->
                        key(line.id) {
                            LineRow(vm, lines, index, line, placeholder = if (lines.size == 1 && index == 0) "Start writing…" else null)
                        }
                    }
                    // Tap below the last line to keep typing at the end.
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.focusEnd() },
                    )
                }
                AnimatedVisibility(vm.uploading) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                }
                FormatToolbar(
                    vm = vm,
                    onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onLink = { linkDialog = vm.linkContext() },
                )
            }
        }
    }

    // ------------------------------------------------------------ sheets & dialogs

    if (pickingFolder) {
        FolderPickerSheet(
            folders = vm.folders,
            currentId = note?.folderId,
            onPick = { vm.moveToFolder(it); pickingFolder = false },
            onDismiss = { pickingFolder = false },
        )
    }
    if (pickingReminder) {
        ReminderSheet(
            current = note?.reminderAt,
            onPick = { at ->
                pickingReminder = false
                vm.setReminder(at)
                if (at != null && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDismiss = { pickingReminder = false },
        )
    }
    linkDialog?.let { (href, needsText) ->
        LinkDialog(
            existingHref = href,
            needsText = needsText,
            onApply = { h, t -> vm.applyLink(h, t); linkDialog = null },
            onDismiss = { linkDialog = null },
        )
    }
    val conflict = vm.conflict
    if (conflict != null && vm.showConflict) {
        ConflictDialog(
            conflict = conflict,
            onKeepMine = vm::keepMine,
            onUseServer = vm::useServer,
            onDismiss = { vm.showConflict = false },
        )
    }
    vm.leaveBlocked?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.leaveBlocked = null },
            icon = { Icon(Icons.Outlined.ErrorOutline, null) },
            title = { Text("Changes not saved") },
            text = { Text("$msg\n\nKeep editing and try again, or leave and lose the latest changes.") },
            confirmButton = { TextButton(onClick = { vm.leaveBlocked = null; vm.retrySave() }) { Text("Keep editing") } },
            dismissButton = { TextButton(onClick = { vm.discardAndLeave() }) { Text("Discard changes") } },
        )
    }
}

@Composable
private fun SaveIndicator(state: SaveState, onRetry: () -> Unit, onShowConflict: () -> Unit) {
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "save",
    ) { s ->
        val style = MaterialTheme.typography.labelLarge
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (s) {
                SaveState.New -> Text("New note", style = style, color = muted)
                SaveState.Editing -> Text("Editing", style = style, color = muted)
                SaveState.Saving -> {
                    CircularWavyProgressIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…", style = style, color = muted)
                }
                SaveState.Saved -> {
                    Icon(Icons.Outlined.CloudDone, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Saved", style = style, color = muted)
                }
                is SaveState.Failed -> TextButton(onClick = onRetry) {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Not saved · Retry", color = MaterialTheme.colorScheme.error)
                }
                SaveState.Conflict -> TextButton(onClick = onShowConflict) {
                    Icon(Icons.Outlined.MergeType, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Conflict · Resolve", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun TitleField(title: String, autoTitle: String, onChange: (String) -> Unit) {
    val style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = title,
        onValueChange = onChange,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        decorationBox = { inner ->
            Box {
                if (title.isEmpty()) {
                    Text(autoTitle, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
                }
                inner()
            }
        },
    )
}

@Composable
private fun MetaRow(reminderAt: Instant?, folderName: String?, onReminder: () -> Unit, onFolder: () -> Unit) {
    if (reminderAt == null && folderName == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        if (reminderAt != null) {
            val overdue = reminderAt.isBefore(Instant.now())
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onReminder)) {
                Pill(
                    Icons.Outlined.Alarm, Format.reminder(reminderAt),
                    container = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    content = if (overdue) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        if (folderName != null) {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onFolder)) {
                Pill(Icons.Outlined.Folder, folderName)
            }
        }
    }
}

private fun textAlignOf(a: Align) = when (a) {
    Align.LEFT -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.RIGHT -> TextAlign.End
}

private fun boxAlignOf(a: Align) = when (a) {
    Align.LEFT -> Alignment.CenterStart
    Align.CENTER -> Alignment.Center
    Align.RIGHT -> Alignment.CenterEnd
}

@Composable
private fun LineRow(vm: EditorViewModel, lines: List<EditorLine>, index: Int, line: EditorLine, placeholder: String?) {
    val springy = Modifier.animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
    when (val kind = line.kind) {
        is LineKind.Image -> ImageRow(vm, line, kind, springy)
        LineKind.Divider -> HorizontalDivider(Modifier.padding(vertical = 18.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
        is LineKind.Unknown -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Text(
                "A “${kind.raw["type"]}” block from a newer Craffa — kept as is.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        else -> {
            val base: TextStyle = when (kind) {
                is LineKind.Heading -> when (kind.level) { 1 -> NoteText.h1; 2 -> NoteText.h2; else -> NoteText.h3 }
                LineKind.Code -> NoteText.code
                else -> NoteText.paragraph
            }
            val checkedDone = kind is LineKind.Item && kind.list == ListKind.CHECK && kind.checked
            val style = base.copy(
                textAlign = textAlignOf(line.align),
                color = when {
                    checkedDone || kind == LineKind.Quote -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (checkedDone) TextDecoration.LineThrough else null,
            )
            val field = @Composable { m: Modifier ->
                RichLineField(
                    lineId = line.id,
                    rich = line.text,
                    style = style,
                    focusTarget = vm.focusTarget,
                    onValueChange = { t, s -> vm.onLineValueChange(line.id, t, s) },
                    onBackspaceAtStart = { vm.onBackspaceAtStart(line.id) },
                    onFocused = { s -> vm.onLineFocused(line.id, s) },
                    onFocusConsumed = vm::onFocusConsumed,
                    onShortcut = vm::toggleMark,
                    placeholder = placeholder ?: if (kind is LineKind.Heading) "Heading" else null,
                    code = kind == LineKind.Code,
                    modifier = m,
                )
            }
            val vPad = when (kind) {
                is LineKind.Heading -> Modifier.padding(top = 12.dp, bottom = 4.dp)
                is LineKind.Item -> Modifier.padding(vertical = 1.dp)
                else -> Modifier.padding(vertical = 3.dp)
            }
            when (kind) {
                is LineKind.Item -> Row(verticalAlignment = Alignment.Top, modifier = vPad.then(springy)) {
                    when (kind.list) {
                        ListKind.CHECK -> Checkbox(
                            checked = kind.checked,
                            onCheckedChange = { vm.toggleChecked(line.id) },
                            modifier = Modifier.size(32.dp).padding(end = 4.dp),
                        )
                        ListKind.BULLET -> Text("•", style = NoteText.paragraph, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                        ListKind.ORDERED -> Text(
                            "${EditorDoc.orderedNumber(lines, index)}.",
                            style = NoteText.paragraph,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(min = 28.dp).padding(end = 4.dp),
                        )
                    }
                    field(Modifier.weight(1f).padding(top = if (kind.list == ListKind.CHECK) 3.dp else 0.dp))
                }
                LineKind.Quote -> Row(vPad.then(springy).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    field(Modifier.weight(1f))
                }
                LineKind.Code -> Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = vPad.then(springy).fillMaxWidth(),
                ) {
                    field(Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                else -> Box(vPad.then(springy)) { field(Modifier) }
            }
        }
    }
}

@Composable
private fun ImageRow(vm: EditorViewModel, line: EditorLine, kind: LineKind.Image, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = boxAlignOf(line.align)) {
        Box {
            AsyncImage(
                model = vm.attachmentUrl(kind.attachmentId),
                contentDescription = kind.alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(min = 160.dp)
                    .heightIn(min = 120.dp, max = 420.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            FilledTonalIconButton(
                onClick = { vm.removeLine(line.id) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp),
            ) {
                Icon(Icons.Outlined.Close, "Remove image", Modifier.size(18.dp))
            }
        }
    }
}
