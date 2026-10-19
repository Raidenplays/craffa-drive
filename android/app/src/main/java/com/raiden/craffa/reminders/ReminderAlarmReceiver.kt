package com.raiden.craffa.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raiden.craffa.container

/** Fires a scheduled reminder (from extras — no network needed) and handles notification actions. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
        when (intent.action) {
            ACTION_FIRE -> {
                val at = intent.getStringExtra(EXTRA_AT) ?: return
                val key = "$noteId@$at"
                val settings = context.container.settings
                if (key in settings.notifiedReminders) return  // a sync already showed it
                Notifications.showReminder(
                    context,
                    noteId,
                    intent.getStringExtra(EXTRA_TITLE) ?: "Reminder",
                    intent.getStringExtra(EXTRA_TEXT) ?: "",
                )
                settings.markNotified(key)
            }
            ACTION_DONE, ACTION_SNOOZE -> {
                Notifications.cancel(context, noteId)
                ReminderActionWorker.enqueue(context, intent.action!!, noteId)
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.raiden.craffa.REMINDER_FIRE"
        const val ACTION_DONE = "com.raiden.craffa.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.raiden.craffa.REMINDER_SNOOZE"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_AT = "at"

        fun actionIntent(context: Context, action: String, noteId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                (action + noteId).hashCode(),
                Intent(context, ReminderAlarmReceiver::class.java).setAction(action).putExtra(EXTRA_NOTE_ID, noteId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

/** Alarms don't survive reboots or app updates: re-arm via a sync. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.schedulePeriodic(context)
        ReminderScheduler.syncSoon(context)
    }
}
