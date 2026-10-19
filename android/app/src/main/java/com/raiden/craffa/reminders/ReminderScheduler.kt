package com.raiden.craffa.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.raiden.craffa.AppContainer
import com.raiden.craffa.container
import com.raiden.craffa.data.api.ApiException
import com.raiden.craffa.data.api.NotePatch
import com.raiden.craffa.data.api.NoteQuery
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.data.model.Time
import com.raiden.craffa.io
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Client-side reminder delivery (the server only stores `reminder_at`):
 *  - every ~15 min (WorkManager) and on app start / reminder edits, fetch
 *    notes with reminders due within [ReminderPlan.HORIZON];
 *  - post overdue ones that haven't been shown yet;
 *  - set an AlarmManager alarm for upcoming ones so they fire on the minute.
 */
object ReminderScheduler {
    private const val WORK_PERIODIC = "reminder-sync"
    private const val WORK_NOW = "reminder-sync-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Re-sync shortly (after a reminder changed in the app, or at app start). */
    fun syncSoon(c: AppContainer) = syncSoon(c.appContext)

    fun syncSoon(context: Context) {
        val req = OneTimeWorkRequestBuilder<ReminderSyncWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** One sync pass. Blocking network — call from a worker. */
    fun sync(context: Context) {
        val c = context.container
        if (!c.settings.config.isConfigured) return
        val now = Instant.now()
        // Archived notes are hidden from default lists, but their reminders still count.
        val notes = c.api.listAllNotesIncludingArchived(
            NoteQuery(hasReminder = true, reminderBefore = now.plus(ReminderPlan.HORIZON)),
        )
        val before = c.settings.notifiedReminders
        val plan = ReminderPlan.plan(notes, now, before)
        for (n in plan.notifyNow) Notifications.showReminder(context, n.id, n.title, n.snippet)
        // keep anything an alarm marked while we were fetching
        c.settings.notifiedReminders = plan.notified + (c.settings.notifiedReminders - before)

        val wanted = plan.schedule.map { it.id }.toSet()
        for (n in plan.schedule) setAlarm(context, n)
        for (stale in c.settings.scheduledReminders - wanted) cancelAlarm(context, stale)
        c.settings.scheduledReminders = wanted
    }

    private fun pendingAlarm(context: Context, noteId: String, note: Note?): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ReminderAlarmReceiver.ACTION_FIRE)
            .putExtra(ReminderAlarmReceiver.EXTRA_NOTE_ID, noteId)
        if (note != null) {
            intent.putExtra(ReminderAlarmReceiver.EXTRA_TITLE, note.title)
                .putExtra(ReminderAlarmReceiver.EXTRA_TEXT, note.snippet)
                .putExtra(ReminderAlarmReceiver.EXTRA_AT, Time.format(note.reminderAt!!))
        }
        return PendingIntent.getBroadcast(
            context,
            Notifications.idFor(noteId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setAlarm(context: Context, note: Note) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = note.reminderAt!!.toEpochMilli()
        val pi = pendingAlarm(context, note.id, note)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(context: Context, noteId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingAlarm(context, noteId, null))
    }

    /** Notification actions: clear the reminder, or push it an hour later. */
    fun applyAction(context: Context, action: String, noteId: String) {
        val c = context.container
        val at = when (action) {
            ReminderAlarmReceiver.ACTION_SNOOZE -> Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
            else -> null
        }
        // No base_revision: only the reminder changes, which can't clobber note content.
        c.api.patchNote(noteId, NotePatch.reminder(at))
        Notifications.cancel(context, noteId)
    }
}

class ReminderSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        io { ReminderScheduler.sync(applicationContext) }
        Result.success()
    } catch (e: ApiException.Unauthorized) {
        Result.success()      // nothing to do until the token is fixed
    } catch (e: ApiException.NotConfigured) {
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.success()
    }
}

class ReminderActionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE) ?: return Result.failure()
        val action = inputData.getString(KEY_ACTION) ?: return Result.failure()
        return try {
            io { ReminderScheduler.applyAction(applicationContext, action, noteId) }
            io { ReminderScheduler.sync(applicationContext) }
            Result.success()
        } catch (e: ApiException.NotFound) {
            Notifications.cancel(applicationContext, noteId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_NOTE = "note_id"
        const val KEY_ACTION = "action"

        fun enqueue(context: Context, action: String, noteId: String) {
            val req = OneTimeWorkRequestBuilder<ReminderActionWorker>()
                .setInputData(workDataOf(KEY_NOTE to noteId, KEY_ACTION to action))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("reminder-action-$noteId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
