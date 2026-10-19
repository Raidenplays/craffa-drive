package com.raiden.craffa.editor

import com.raiden.craffa.data.model.Mark
import com.raiden.craffa.data.model.Span

/**
 * Editable rich text for one block: the characters plus the set of marks on
 * each character. Per-character marks make every edit a plain splice, so
 * typing, IME recomposition, paste, and deleting across formatting
 * boundaries all keep formatting exactly where it was.
 */
class RichText private constructor(
    val text: String,
    private val marks: List<Set<Mark>>,
) {
    init {
        require(marks.size == text.length) { "marks/text length mismatch" }
    }

    val length: Int get() = text.length

    fun isEmpty() = text.isEmpty()

    fun marksAt(index: Int): Set<Mark> = marks[index]

    // ------------------------------------------------------------ conversion

    fun toSpans(): List<Span> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        var start = 0
        for (i in 1..text.length) {
            if (i == text.length || marks[i] != marks[start]) {
                out += Span(text.substring(start, i), marks[start])
                start = i
            }
        }
        return out
    }

    /** Runs of identical marks, as [start, end) ranges — for styling in the UI. */
    fun runs(): List<Triple<Int, Int, Set<Mark>>> {
        val out = ArrayList<Triple<Int, Int, Set<Mark>>>()
        var start = 0
        for (i in 1..text.length) {
            if (i == text.length || marks[i] != marks[start]) {
                if (marks[start].isNotEmpty()) out += Triple(start, i, marks[start])
                start = i
            }
        }
        return out
    }

    // ------------------------------------------------------------ queries

    /**
     * The marks new text typed at [pos] picks up: those of the character
     * before (or after, at the very start). A link only continues when
     * typing strictly inside it — typing right after a link starts plain text.
     */
    fun insertionMarks(pos: Int): Set<Mark> {
        if (text.isEmpty()) return emptySet()
        val before = if (pos > 0) marks[pos - 1] else marks[0]
        val after = if (pos < text.length) marks[pos] else emptySet()
        val linkBefore = before.firstOrNull { it is Mark.Link }
        val keepLink = linkBefore != null && pos > 0 && linkBefore in after
        return if (keepLink) before else before.filterNot { it is Mark.Link }.toSet()
    }

    /** True if every character in [start, end) has [mark] (for Link: any link). */
    fun hasMark(start: Int, end: Int, mark: Mark): Boolean {
        if (start >= end) return false
        for (i in start until end) {
            val ok = if (mark is Mark.Link) marks[i].any { it is Mark.Link } else mark in marks[i]
            if (!ok) return false
        }
        return true
    }

    fun linkAt(pos: Int): Mark.Link? {
        val i = when {
            pos < text.length && marks[pos].any { it is Mark.Link } -> pos
            pos > 0 && pos <= text.length && marks[pos - 1].any { it is Mark.Link } -> pos - 1
            else -> return null
        }
        return marks[i].firstOrNull { it is Mark.Link } as Mark.Link?
    }

    /** Extent [start, end) of the link run touching [pos], if any. */
    fun linkRange(pos: Int): IntRange? {
        val link = linkAt(pos) ?: return null
        var i = if (pos < text.length && link in marks[pos]) pos else pos - 1
        var s = i
        while (s > 0 && link in marks[s - 1]) s--
        while (i < text.length && link in marks[i]) i++
        return s until i
    }

    // ------------------------------------------------------------ edits

    /**
     * Turn the old text into [newText], keeping marks on untouched characters.
     * [caret] (the new selection end) disambiguates where an insertion
     * happened when neighbouring characters repeat. [typingMarks] overrides
     * the inherited marks for a pure insertion (a mark toggled with no selection).
     */
    fun applyEdit(newText: String, caret: Int? = null, typingMarks: Set<Mark>? = null): RichText {
        if (newText == text) return this
        val oldLen = text.length
        val newLen = newText.length
        var prefix: Int
        var suffix: Int

        val d = newLen - oldLen
        if (caret != null && d > 0 && caret in d..newLen &&
            newText.regionMatches(0, text, 0, caret - d) &&
            newText.regionMatches(caret, text, caret - d, newLen - caret)
        ) {
            // pure insertion of d chars ending at the caret
            prefix = caret - d
            suffix = newLen - caret
        } else {
            prefix = 0
            val max = minOf(oldLen, newLen)
            while (prefix < max && text[prefix] == newText[prefix]) prefix++
            suffix = 0
            while (suffix < max - prefix && text[oldLen - 1 - suffix] == newText[newLen - 1 - suffix]) suffix++
        }

        val deleted = oldLen - prefix - suffix
        val inserted = newLen - prefix - suffix
        val out = ArrayList<Set<Mark>>(newLen)
        for (i in 0 until prefix) out += marks[i]
        if (inserted > 0) {
            if (deleted == 0) {
                val m = typingMarks ?: insertionMarks(prefix)
                repeat(inserted) { out += m }
            } else {
                // replacement (IME recomposition, autocorrect, paste over a
                // selection): new chars take the replaced chars' marks positionally
                for (k in 0 until inserted) {
                    out += marks[prefix + minOf(k, deleted - 1)]
                }
            }
        }
        for (i in oldLen - suffix until oldLen) out += marks[i]
        return RichText(newText, out)
    }

    /** Add [mark] to [start, end) — or remove it if the whole range already has it. */
    fun toggle(start: Int, end: Int, mark: Mark): RichText {
        if (start >= end) return this
        val remove = hasMark(start, end, mark)
        return mapRange(start, end) { set -> if (remove) set - mark else set + mark }
    }

    /** Set (or with null, remove) the link on [start, end). */
    fun setLink(start: Int, end: Int, href: String?): RichText {
        if (start >= end) return this
        return mapRange(start, end) { set ->
            val base = set.filterNot { it is Mark.Link }.toSet()
            if (href.isNullOrBlank()) base else base + Mark.Link(href.trim())
        }
    }

    fun clearMarks(start: Int, end: Int): RichText = mapRange(start, end) { emptySet() }

    /** Insert [s] at [pos] with exactly [withMarks]. */
    fun insert(pos: Int, s: String, withMarks: Set<Mark>): RichText {
        val out = ArrayList<Set<Mark>>(text.length + s.length)
        out.addAll(marks.subList(0, pos))
        repeat(s.length) { out += withMarks }
        out.addAll(marks.subList(pos, marks.size))
        return RichText(text.substring(0, pos) + s + text.substring(pos), out)
    }

    fun substring(start: Int, end: Int = text.length): RichText =
        RichText(text.substring(start, end), marks.subList(start, end).toList())

    operator fun plus(other: RichText): RichText = RichText(text + other.text, marks + other.marks)

    /** Split on '\n' (the newlines themselves are dropped). */
    fun splitLines(): List<RichText> {
        val out = ArrayList<RichText>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                out += substring(start, i)
                start = i + 1
            }
        }
        out += substring(start)
        return out
    }

    private fun mapRange(start: Int, end: Int, f: (Set<Mark>) -> Set<Mark>): RichText {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val out = marks.toMutableList()
        for (i in s until e) out[i] = f(out[i])
        return RichText(text, out)
    }

    override fun equals(other: Any?) = other is RichText && other.text == text && other.marks == marks
    override fun hashCode() = text.hashCode() * 31 + marks.hashCode()
    override fun toString() = "RichText(${toSpans()})"

    companion object {
        val EMPTY = RichText("", emptyList())

        fun plain(s: String, withMarks: Set<Mark> = emptySet()) = RichText(s, List(s.length) { withMarks })

        fun fromSpans(spans: List<Span>): RichText {
            val sb = StringBuilder()
            val m = ArrayList<Set<Mark>>()
            for (span in spans) {
                sb.append(span.text)
                repeat(span.text.length) { m += span.marks }
            }
            return RichText(sb.toString(), m)
        }
    }
}
