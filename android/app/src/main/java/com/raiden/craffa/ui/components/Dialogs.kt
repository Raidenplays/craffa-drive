package com.raiden.craffa.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.raiden.craffa.data.model.Folder

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = value.text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a folder for a note (or "No folder"). */
@Composable
fun FolderPickerSheet(
    folders: List<Folder>,
    currentId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Move to folder",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                ListItem(
                    headlineContent = { Text("No folder") },
                    leadingContent = { Icon(Icons.Outlined.FolderOff, null) },
                    trailingContent = { if (currentId == null) Icon(Icons.Outlined.Check, "Current") },
                    modifier = Modifier.clickable { onPick(null) },
                )
            }
            items(folders, key = { it.id }) { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    leadingContent = { Icon(Icons.Outlined.Folder, null) },
                    trailingContent = { if (currentId == f.id) Icon(Icons.Outlined.Check, "Current") },
                    modifier = Modifier.clickable { onPick(f.id) },
                )
            }
            if (folders.isEmpty()) {
                item {
                    Text(
                        "No folders yet — create one from the folder chips on the notes list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Create / rename / delete folders. */
@Composable
fun FolderManagerSheet(
    folders: List<Folder>,
    onCreate: (String) -> Unit,
    onRename: (Folder, String) -> Unit,
    onDelete: (Folder) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Folder?>(null) }
    var deleting by remember { mutableStateOf<Folder?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Folders",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                ListItem(
                    headlineContent = { Text("New folder") },
                    leadingContent = { Icon(Icons.Outlined.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { creating = true },
                )
            }
            items(folders, key = { it.id }) { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    leadingContent = { Icon(Icons.Outlined.Folder, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { renaming = f }) { Icon(Icons.Outlined.Edit, "Rename ${f.name}") }
                            IconButton(onClick = { deleting = f }) { Icon(Icons.Outlined.Delete, "Delete ${f.name}") }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (creating) {
        TextInputDialog(
            title = "New folder", label = "Name", confirmLabel = "Create",
            onConfirm = { onCreate(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { f ->
        TextInputDialog(
            title = "Rename folder", label = "Name", initial = f.name,
            onConfirm = { onRename(f, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { f ->
        ConfirmDialog(
            title = "Delete “${f.name}”?",
            message = "The notes inside aren't deleted — they just won't be in a folder anymore.",
            confirmLabel = "Delete folder",
            onConfirm = { onDelete(f); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}
