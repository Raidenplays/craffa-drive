package com.raiden.craffa.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Format {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayInWeek = DateTimeFormatter.ofPattern("EEE")
    private val monthDay = DateTimeFormatter.ofPattern("MMM d")
    private val full = DateTimeFormatter.ofPattern("MMM d, yyyy")

    /** "Today 9:00 AM", "Tomorrow 9:00 AM", "Thu 9:00 AM", "Nov 3 9:00 AM". */
    fun reminder(at: Instant, now: Instant = Instant.now()): String {
        val zone = ZoneId.systemDefault()
        val t = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val day = t.toLocalDate()
        val dayLabel = when {
            day == today -> "Today"
            day == today.plusDays(1) -> "Tomorrow"
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today) && day.isBefore(today.plusDays(7)) -> dayInWeek.format(t)
            day.year == today.year -> monthDay.format(t)
            else -> full.format(t)
        }
        return "$dayLabel ${time.format(t)}"
    }

    /** "just now", "5 min ago", "3 h ago", "Mon", "Oct 3". */
    fun relative(at: Instant?, now: Instant = Instant.now()): String {
        if (at == null) return ""
        val d = Duration.between(at, now)
        val zone = ZoneId.systemDefault()
        return when {
            d.isNegative || d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            d.toDays() < 7 -> dayInWeek.format(at.atZone(zone))
            at.atZone(zone).year == LocalDate.now(zone).year -> monthDay.format(at.atZone(zone))
            else -> full.format(at.atZone(zone))
        }
    }

    fun clock(at: Instant): String = time.format(at.atZone(ZoneId.systemDefault()))

    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    /** "Oct 19, 2026, 10:05 AM" in the device's locale. */
    fun dateTime(at: Instant): String = dateTimeFmt.format(at.atZone(ZoneId.systemDefault()))
}
