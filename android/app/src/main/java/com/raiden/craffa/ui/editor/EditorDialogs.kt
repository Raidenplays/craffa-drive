package com.raiden.craffa.ui.editor

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.raiden.craffa.editor.DocDiff
import com.raiden.craffa.ui.Format
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// ------------------------------------------------------------------ link

@Composable
fun LinkDialog(
    existingHref: String?,
    needsText: Boolean,
    onApply: (href: String?, text: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var href by remember { mutableStateOf(existingHref ?: "https://") }
    var text by remember { mutableStateOf("") }
    val valid = href.isNotBlank() && href != "https://" && (!needsText || text.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingHref != null) "Edit link" else "Add link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (needsText) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Text") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = href, onValueChange = { href = it.trim() },
                    label = { Text("Address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existingHref != null) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(existingHref))) }
                    }) { Text("Open link") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(normalizeHref(href), text.ifBlank { null }) }, enabled = valid) { Text("Apply") }
        },
        dismissButton = {
            Row {
                if (existingHref != null) TextButton(onClick = { onApply(null, null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun normalizeHref(raw: String): String {
    val s = raw.trim()
    return if ("://" in s || s.startsWith("mailto:") || s.startsWith("tel:")) s else "https://$s"
}

// ------------------------------------------------------------------ reminders

/** Quick picks + custom date/time, plus "remove" when one is set. */
@Composable
fun ReminderSheet(
    current: Instant?,
    onPick: (Instant?) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.now(zone)
    val laterToday = if (now.hour < 17) now.toLocalDate().atTime(18, 0) else now.plusHours(1).truncatedTo(ChronoUnit.HOURS)
    val tomorrow = now.toLocalDate().plusDays(1).atTime(9, 0)
    val nextWeek = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0)
    var custom by remember { mutableStateOf(false) }

    if (custom) {
        DateTimePickerFlow(
            initial = current ?: tomorrow.atZone(zone).toInstant(),
            onPicked = { onPick(it) },
            onDismiss = onDismiss,
        )
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (current != null) "Reminder: ${Format.reminder(current)}" else "Remind me",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val opts = listOf(
                Triple(Icons.Outlined.WbTwilight, if (now.hour < 17) "Later today" else "In an hour", laterToday),
                Triple(Icons.Outlined.WbSunny, "Tomorrow morning", tomorrow),
                Triple(Icons.Outlined.DateRange, "Next week", nextWeek),
            )
            for ((icon, label, at) in opts) {
                val instant = at.atZone(zone).toInstant()
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(Format.reminder(instant)) },
                    leadingContent = { Icon(icon, null) },
                    modifier = Modifier.clickable { onPick(instant) },
                )
            }
            ListItem(
                headlineContent = { Text("Pick date & time") },
                leadingContent = { Icon(Icons.Outlined.CalendarMonth, null) },
                modifier = Modifier.clickable { custom = true },
            )
            if (current != null) {
                ListItem(
                    headlineContent = { Text("Remove reminder") },
                    leadingContent = { Icon(Icons.Outlined.NotificationsOff, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { onPick(null) },
                )
            }
        }
    }
}

@Composable
private fun DateTimePickerFlow(initial: Instant, onPicked: (Instant) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val initialLocal = initial.atZone(zone)
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    // DatePicker works in UTC-midnight millis for the chosen calendar day.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialLocal.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(
        initialHour = initialLocal.hour,
        initialMinute = initialLocal.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )

    val date = pickedDate
    if (date == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let {
                            pickedDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                    },
                    enabled = dateState.selectedDateMillis != null,
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.Schedule, null) },
            title = { Text("What time?") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val at = date.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant()
                    onPicked(at)
                }) { Text("Set reminder") }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text("Back") } },
        )
    }
}

// ------------------------------------------------------------------ conflict

/**
 * 409 resolution: show what changed between the server's copy and the
 * user's, then Keep mine (re-save on top of the server revision) or Use
 * server version.
 */
@Composable
fun ConflictDialog(
    conflict: ConflictState,
    onKeepMine: () -> Unit,
    onUseServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val diff = remember(conflict) { DocDiff.diff(conflict.server.body, conflict.mineDoc) }
    val serverLines = remember(conflict) { DocDiff.describe(conflict.server.body) }
    val mineLines = remember(conflict) { DocDiff.describe(conflict.mineDoc) }
    val textChanged = diff.any { it.op != DocDiff.Op.SAME }
    val serverTitle = if (conflict.server.hasAutoTitle) null else conflict.server.title

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Changed on another device") },
        text = {
            Column {
                Text(
                    "The server copy was updated ${Format.relative(conflict.server.updatedAt)} while you were editing. " +
                        "Pick which version to keep.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (serverTitle != conflict.mineTitle) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Title — server: “${serverTitle ?: conflict.server.title}”, yours: “${conflict.mineTitle ?: "(automatic)"}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Changes", "Server", "Mine").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = tab == i,
                            onClick = { tab = i },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp),
                ) {
                    LazyColumn(Modifier.padding(8.dp)) {
                        when (tab) {
                            0 -> if (!textChanged) {
                                item {
                                    Text(
                                        "The text is the same — only formatting, checkboxes or images differ.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                            } else {
                                items(diff) { DiffRow(it) }
                            }
                            1 -> items(serverLines) { DiffRow(DocDiff.Line(DocDiff.Op.SAME, it)) }
                            else -> items(mineLines) { DiffRow(DocDiff.Line(DocDiff.Op.SAME, it)) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (tab == 0 && textChanged) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Legend("−", "only on server", MaterialTheme.colorScheme.errorContainer)
                        Legend("+", "only yours", MaterialTheme.colorScheme.primaryContainer)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onKeepMine) { Text("Keep mine") } },
        dismissButton = { OutlinedButton(onClick = onUseServer) { Text("Use server version") } },
    )
}

/** One line of a [DocDiff] (also used by version history). */
@Composable
fun DiffRow(line: DocDiff.Line) {
    val (prefix, bg) = when (line.op) {
        DocDiff.Op.SAME -> "  " to androidx.compose.ui.graphics.Color.Transparent
        DocDiff.Op.ONLY_SERVER -> "− " to MaterialTheme.colorScheme.errorContainer
        DocDiff.Op.ONLY_MINE -> "+ " to MaterialTheme.colorScheme.primaryContainer
    }
    Text(
        prefix + line.text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun Legend(symbol: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Row {
        Box(Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp)) {
            Text(symbol, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
