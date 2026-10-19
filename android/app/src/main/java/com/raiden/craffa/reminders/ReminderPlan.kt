package com.raiden.craffa.reminders

import com.raiden.craffa.data.model.Note
import com.raiden.craffa.data.model.Time
import java.time.Duration
import java.time.Instant

/**
 * Pure decision logic for reminder delivery (unit-tested). Given the notes
 * that have reminders, what should fire now, what should get an alarm, and
 * what to remember as already-notified.
 *
 * A reminder is identified by note id + time, so moving a reminder to a new
 * time makes it notify again, while an overdue one that was already shown
 * never repeats.
 */
object ReminderPlan {
    /** Overdue reminders older than this are considered stale and skipped silently. */
    val STALE_AFTER: Duration = Duration.ofDays(3)

    /** How far ahead to set exact alarms; later ones are picked up by a later sync. */
    val HORIZON: Duration = Duration.ofDays(2)

    data class Plan(
        val notifyNow: List<Note>,
        val schedule: List<Note>,
        val notified: Set<String>,
    )

    fun key(note: Note): String = note.id + "@" + Time.format(note.reminderAt!!)

    fun plan(notes: List<Note>, now: Instant, alreadyNotified: Set<String>): Plan {
        val withReminder = notes.filter { it.reminderAt != null && it.deletedAt == null }
        val notifyNow = ArrayList<Note>()
        val schedule = ArrayList<Note>()
        val keep = HashSet<String>()
        for (n in withReminder) {
            val at = n.reminderAt!!
            val k = key(n)
            if (!at.isAfter(now)) {
                keep += k  // remember it while the reminder still exists
                if (k !in alreadyNotified && Duration.between(at, now) <= STALE_AFTER) notifyNow += n
            } else if (Duration.between(now, at) <= HORIZON) {
                schedule += n
            }
        }
        // Forget keys for reminders that were cleared/moved: the set stays small.
        val notified = (alreadyNotified intersect keep) + notifyNow.map(::key)
        return Plan(notifyNow, schedule.sortedBy { it.reminderAt }, notified)
    }
}
