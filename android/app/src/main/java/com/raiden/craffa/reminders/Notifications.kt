package com.raiden.craffa.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raiden.craffa.MainActivity
import com.raiden.craffa.R

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_REMINDERS) != null) return
        val ch = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_reminders_desc) }
        nm.createNotificationChannel(ch)
    }

    fun idFor(noteId: String) = noteId.hashCode()

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Post the reminder for a note: tap opens it; Done clears the reminder; Snooze adds an hour. */
    @SuppressLint("MissingPermission") // checked in canNotify
    fun showReminder(context: Context, noteId: String, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            idFor(noteId),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_NOTE_ID, noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Reminder" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifBlank { "Reminder" }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", ReminderAlarmReceiver.actionIntent(context, ReminderAlarmReceiver.ACTION_DONE, noteId))
            .addAction(0, "Snooze 1 h", ReminderAlarmReceiver.actionIntent(context, ReminderAlarmReceiver.ACTION_SNOOZE, noteId))
            .build()
        NotificationManagerCompat.from(context).notify(idFor(noteId), n)
    }

    fun cancel(context: Context, noteId: String) {
        NotificationManagerCompat.from(context).cancel(idFor(noteId))
    }
}
