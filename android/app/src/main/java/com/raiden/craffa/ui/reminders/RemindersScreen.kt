package com.raiden.craffa.ui.reminders

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiden.craffa.AppContainer
import com.raiden.craffa.data.api.ApiException
import com.raiden.craffa.data.api.NotePatch
import com.raiden.craffa.data.api.NoteQuery
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.io
import com.raiden.craffa.reminders.ReminderScheduler
import com.raiden.craffa.ui.CollectMessages
import com.raiden.craffa.ui.Format
import com.raiden.craffa.ui.UiMessage
import com.raiden.craffa.ui.components.BlobShape
import com.raiden.craffa.ui.components.EmptyState
import com.raiden.craffa.ui.components.ErrorState
import com.raiden.craffa.ui.components.FullScreenLoading
import com.raiden.craffa.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class RemindersViewModel(private val c: AppContainer) : ViewModel() {
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    fun refresh(pull: Boolean = false) {
        viewModelScope.launch {
            if (pull) refreshing = true
            try {
                notes = io { c.api.listAllNotesIncludingArchived(NoteQuery(hasReminder = true)) }
                    .filter { it.reminderAt != null }
                    .sortedBy { it.reminderAt }
                error = null
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    private fun patchReminder(note: Note, at: Instant?, msg: String) {
        viewModelScope.launch {
            try {
                val updated = try {
                    io { c.api.patchNote(note.id, NotePatch.reminder(at), note.revision) }
                } catch (e: ApiException.Conflict) {
                    // only the reminder changes, so apply it on top of the newer copy
                    io { c.api.patchNote(note.id, NotePatch.reminder(at), e.serverCopy.revision) }
                }
                notes = notes.map { if (it.id == note.id) updated else it }
                    .filter { it.reminderAt != null }
                    .sortedBy { it.reminderAt }
                ReminderScheduler.syncSoon(c)
                messages.tryEmit(UiMessage(msg))
            } catch (e: Exception) {
                messages.tryEmit(UiMessage(e.message ?: "Couldn't update reminder"))
            }
        }
    }

    fun done(note: Note) = patchReminder(note, null, "Reminder cleared")

    fun snooze(note: Note) {
        val at = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        patchReminder(note, at, "Snoozed until ${Format.clock(at)}")
    }
}

@Composable
fun RemindersScreen(
    vm: RemindersViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(Unit) { vm.refresh() }

    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
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
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                    if (!notifGranted) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.NotificationsOff, null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Notifications are off, so reminders can't ping you.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(onClick = { requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                                }
                            }
                        }
                    }
                    if (vm.notes.isEmpty()) {
                        item {
                            EmptyState(
                                Icons.Outlined.Alarm, "No reminders",
                                "Open a note and tap the bell to have Craffa nudge you about it.",
                                shape = BlobShape.Flower,
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    } else {
                        val zone = ZoneId.systemDefault()
                        val now = Instant.now()
                        val today = LocalDate.now(zone)
                        val groups = vm.notes.groupBy { n ->
                            val at = n.reminderAt!!
                            val day = at.atZone(zone).toLocalDate()
                            when {
                                at.isBefore(now) -> "Overdue"
                                day == today -> "Today"
                                day == today.plusDays(1) -> "Tomorrow"
                                else -> "Later"
                            }
                        }
                        for (label in listOf("Overdue", "Today", "Tomorrow", "Later")) {
                            val list = groups[label] ?: continue
                            item(key = "h-$label") { SectionHeader(label) }
                            items(list, key = { it.id }) { note ->
                                ReminderCard(
                                    note = note,
                                    overdue = label == "Overdue",
                                    onOpen = { onOpenNote(note.id) },
                                    onDone = { vm.done(note) },
                                    onSnooze = { vm.snooze(note) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderCard(
    note: Note,
    overdue: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.NotificationsActive, null,
                tint = if (overdue) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Format.reminder(note.reminderAt!!), style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onSnooze) { Icon(Icons.Outlined.Snooze, "Snooze 1 hour") }
            IconButton(onClick = onDone) { Icon(Icons.Outlined.CheckCircle, "Done — clear reminder") }
        }
    }
}
