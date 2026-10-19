package com.raiden.craffa

import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.reminders.ReminderPlan
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderPlanTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")

    private fun note(id: String, at: Instant?) = Note(
        id = id, title = id, body = Doc.EMPTY, plaintext = "", folderId = null, tags = emptyList(),
        favorite = false, pinned = false, archived = false, revision = 1, createdAt = now, updatedAt = now, deletedAt = null, reminderAt = at,
    )

    @Test
    fun overdue_notifiesOnce_upcomingIsScheduled() {
        val due = note("due", now.minus(Duration.ofMinutes(5)))
        val soon = note("soon", now.plus(Duration.ofHours(3)))
        val far = note("far", now.plus(Duration.ofDays(10)))
        val p1 = ReminderPlan.plan(listOf(due, soon, far), now, emptySet())
        assertEquals(listOf("due"), p1.notifyNow.map { it.id })
        assertEquals(listOf("soon"), p1.schedule.map { it.id })

        val p2 = ReminderPlan.plan(listOf(due, soon, far), now, p1.notified)
        assertTrue(p2.notifyNow.isEmpty(), "must not notify twice")
    }

    @Test
    fun movedReminder_notifiesAgain_clearedOneIsForgotten() {
        val first = note("n", now.minus(Duration.ofMinutes(5)))
        val p1 = ReminderPlan.plan(listOf(first), now, emptySet())
        val moved = note("n", now.minus(Duration.ofMinutes(1)))
        val p2 = ReminderPlan.plan(listOf(moved), now, p1.notified)
        assertEquals(1, p2.notifyNow.size)
        val p3 = ReminderPlan.plan(emptyList(), now, p2.notified)
        assertTrue(p3.notified.isEmpty())
    }

    @Test
    fun staleOverdue_isSkipped() {
        val old = note("old", now.minus(Duration.ofDays(5)))
        assertTrue(ReminderPlan.plan(listOf(old), now, emptySet()).notifyNow.isEmpty())
    }
}
