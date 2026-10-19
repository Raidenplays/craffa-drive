package com.raiden.craffa

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raiden.craffa.reminders.ReminderScheduler
import com.raiden.craffa.ui.archive.ArchiveScreen
import com.raiden.craffa.ui.archive.ArchiveViewModel
import com.raiden.craffa.ui.history.HistoryScreen
import com.raiden.craffa.ui.history.HistoryViewModel
import com.raiden.craffa.ui.editor.EditorScreen
import com.raiden.craffa.ui.editor.EditorViewModel
import com.raiden.craffa.ui.notes.NoteFilter
import com.raiden.craffa.ui.notes.NotesScreen
import com.raiden.craffa.ui.notes.NotesViewModel
import com.raiden.craffa.ui.reminders.RemindersScreen
import com.raiden.craffa.ui.reminders.RemindersViewModel
import com.raiden.craffa.ui.settings.SettingsScreen
import com.raiden.craffa.ui.settings.SettingsViewModel
import com.raiden.craffa.ui.theme.CraffaTheme
import com.raiden.craffa.ui.trash.TrashScreen
import com.raiden.craffa.ui.trash.TrashViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Note to open from a reminder notification tap. */
    private val pendingNoteId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            CraffaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CraffaNavHost(pendingNoteId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is a good moment to refresh reminder alarms.
        if (container.settings.config.isConfigured) ReminderScheduler.syncSoon(container)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_NOTE_ID)?.let { pendingNoteId.value = it }
    }

    companion object {
        const val EXTRA_NOTE_ID = "com.raiden.craffa.NOTE_ID"
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val NOTES = "notes"
    const val NOTE = "note/{id}?checklist={checklist}&folder={folder}"
    const val REMINDERS = "reminders"
    const val TRASH = "trash"
    const val ARCHIVE = "archive"
    const val HISTORY = "history/{id}"
    const val SETTINGS = "settings"

    fun note(id: String) = "note/$id"
    fun history(id: String) = "history/$id"
    fun newNote(checklist: Boolean, folderId: String?) =
        "note/new?checklist=$checklist" + (folderId?.let { "&folder=$it" } ?: "")
}

private const val KEY_TRASHED = "trashed_note"
private const val KEY_ARCHIVED = "archived_note"
private const val KEY_RESTORED = "restored_version"

private fun pairOf(s: String?) = s?.let { it.substringBefore('\n') to it.substringAfter('\n', "") }

@Composable
private fun CraffaNavHost(pendingNoteId: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val container = context.container
    val nav = rememberNavController()
    val start = if (container.settings.config.isConfigured) Routes.NOTES else Routes.ONBOARDING

    val pending by pendingNoteId.collectAsState()
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        if (container.settings.config.isConfigured) nav.navigate(Routes.note(id)) { launchSingleTop = true }
        pendingNoteId.value = null
    }

    val springInt = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { slideInHorizontally(springInt) { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() + scaleOut(targetScale = 0.96f) },
        popEnterTransition = { fadeIn() + scaleIn(initialScale = 0.96f) },
        popExitTransition = { slideOutHorizontally(springInt) { it / 4 } + fadeOut() },
    ) {
        composable(Routes.ONBOARDING) {
            val vm = viewModel { SettingsViewModel(container) }
            SettingsScreen(
                vm = vm,
                onboarding = true,
                onBack = {},
                onConnected = {
                    nav.navigate(Routes.NOTES) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
            )
        }

        composable(Routes.NOTES) { entry ->
            val vm = viewModel { NotesViewModel(container) }
            val trashed by entry.savedStateHandle.getStateFlow<String?>(KEY_TRASHED, null).collectAsState()
            val archived by entry.savedStateHandle.getStateFlow<String?>(KEY_ARCHIVED, null).collectAsState()
            NotesScreen(
                vm = vm,
                trashedNote = pairOf(trashed),
                archivedNote = pairOf(archived),
                onTrashedConsumed = { entry.savedStateHandle[KEY_TRASHED] = null },
                onArchivedConsumed = { entry.savedStateHandle[KEY_ARCHIVED] = null },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onNewNote = { checklist ->
                    nav.navigate(Routes.newNote(checklist, (vm.filter as? NoteFilter.InFolder)?.folderId))
                },
                onOpenReminders = { nav.navigate(Routes.REMINDERS) },
                onOpenTrash = { nav.navigate(Routes.TRASH) },
                onOpenArchive = { nav.navigate(Routes.ARCHIVE) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            Routes.NOTE,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("checklist") { type = NavType.BoolType; defaultValue = false },
                navArgument("folder") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val args = entry.arguments
            val rawId = args?.getString("id")
            val id = rawId?.takeIf { it != "new" }
            val checklist = args?.getBoolean("checklist") ?: false
            val folder = args?.getString("folder")
            val vm = viewModel { EditorViewModel(container, id, checklist, folder) }
            // Came back from version history after restoring: reload the note.
            val restored by entry.savedStateHandle.getStateFlow(KEY_RESTORED, false).collectAsState()
            LaunchedEffect(restored) {
                if (restored) {
                    entry.savedStateHandle[KEY_RESTORED] = false
                    vm.load()
                }
            }
            EditorScreen(
                vm = vm,
                onClose = { nav.popBackStack() },
                onTrashed = { noteId, title ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(KEY_TRASHED, "$noteId\n$title")
                    nav.popBackStack()
                },
                onArchived = { noteId, title ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(KEY_ARCHIVED, "$noteId\n$title")
                    nav.popBackStack()
                },
                onOpenHistory = { noteId -> nav.navigate(Routes.history(noteId)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.REMINDERS) {
            val vm = viewModel { RemindersViewModel(container) }
            RemindersScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            Routes.HISTORY,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val noteId = entry.arguments?.getString("id") ?: return@composable
            val vm = viewModel { HistoryViewModel(container, noteId) }
            HistoryScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onRestored = {
                    nav.previousBackStackEntry?.savedStateHandle?.set(KEY_RESTORED, true)
                    nav.popBackStack()
                },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.ARCHIVE) {
            val vm = viewModel { ArchiveViewModel(container) }
            ArchiveScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.TRASH) {
            val vm = viewModel { TrashViewModel(container) }
            TrashScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }

        composable(Routes.SETTINGS) {
            val vm = viewModel { SettingsViewModel(container) }
            SettingsScreen(vm = vm, onboarding = false, onBack = { nav.popBackStack() }, onConnected = {})
        }
    }
}
