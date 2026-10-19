package com.raiden.craffa.editor

import com.raiden.craffa.data.model.Align
import com.raiden.craffa.data.model.Block
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.ListItem
import com.raiden.craffa.data.model.ListKind
import java.util.concurrent.atomic.AtomicLong

/** What kind of line an editor row is. Lists are flattened to one row per item. */
sealed interface LineKind {
    data object Paragraph : LineKind
    data class Heading(val level: Int) : LineKind
    data class Item(val list: ListKind, val checked: Boolean = false) : LineKind
    data object Quote : LineKind
    data object Code : LineKind
    data class Image(val attachmentId: String, val alt: String?) : LineKind
    data object Divider : LineKind
    data class Unknown(val raw: Map<String, Any?>) : LineKind

    val isText: Boolean get() = this !is Image && this !is Divider && this !is Unknown
}

/**
 * One editable row. [extras] are unknown keys of the block (or list item);
 * [groupExtras] are unknown keys of the list block an item came from.
 */
data class EditorLine(
    val id: Long,
    val kind: LineKind,
    val text: RichText = RichText.EMPTY,
    val align: Align = Align.LEFT,
    val extras: Map<String, Any?> = emptyMap(),
    val groupExtras: Map<String, Any?> = emptyMap(),
)

/** Where the caret should go after an operation. */
data class Focus(val lineId: Long, val caret: Int)

data class EditResult(val lines: List<EditorLine>, val focus: Focus? = null)

/**
 * Pure operations on the editor's line list: Doc ⇄ lines, Enter/Backspace
 * structure changes, block-type and alignment changes, inserts.
 */
object EditorDoc {
    private val ids = AtomicLong(1)
    fun newId(): Long = ids.getAndIncrement()

    // ------------------------------------------------------------ conversion

    fun toLines(doc: Doc): List<EditorLine> {
        val out = ArrayList<EditorLine>()
        for (b in doc.blocks) {
            when (b) {
                is Block.Paragraph -> out += EditorLine(newId(), LineKind.Paragraph, RichText.fromSpans(b.spans), b.align, b.extras)
                is Block.Heading -> out += EditorLine(newId(), LineKind.Heading(b.level), RichText.fromSpans(b.spans), b.align, b.extras)
                is Block.Quote -> out += EditorLine(newId(), LineKind.Quote, RichText.fromSpans(b.spans), b.align, b.extras)
                is Block.CodeBlock -> out += EditorLine(newId(), LineKind.Code, RichText.fromSpans(b.spans), b.align, b.extras)
                is Block.ListBlock -> b.items.forEach { item ->
                    out += EditorLine(
                        newId(), LineKind.Item(b.kind, item.checked), RichText.fromSpans(item.spans),
                        b.align, item.extras, b.extras,
                    )
                }
                is Block.Image -> out += EditorLine(newId(), LineKind.Image(b.attachmentId, b.alt), align = b.align, extras = b.extras)
                is Block.Divider -> out += EditorLine(newId(), LineKind.Divider, align = b.align, extras = b.extras)
                is Block.Unknown -> out += EditorLine(newId(), LineKind.Unknown(b.raw))
            }
        }
        // Always leave somewhere to type: a doc that is empty or ends in a
        // non-text block gets a trailing empty paragraph (dropped again on save).
        if (out.isEmpty() || !out.last().kind.isText) out += EditorLine(newId(), LineKind.Paragraph)
        return out
    }

    fun toDoc(lines: List<EditorLine>): Doc {
        val src = dropSyntheticTail(lines)
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < src.size) {
            val line = src[i]
            val spans = line.text.toSpans()
            when (val k = line.kind) {
                LineKind.Paragraph -> blocks += Block.Paragraph(spans, line.align, line.extras)
                is LineKind.Heading -> blocks += Block.Heading(k.level, spans, line.align, line.extras)
                LineKind.Quote -> blocks += Block.Quote(spans, line.align, line.extras)
                LineKind.Code -> blocks += Block.CodeBlock(spans, line.align, line.extras)
                is LineKind.Image -> blocks += Block.Image(k.attachmentId, k.alt, line.align, line.extras)
                LineKind.Divider -> blocks += Block.Divider(line.align, line.extras)
                is LineKind.Unknown -> blocks += Block.Unknown(k.raw)
                is LineKind.Item -> {
                    val items = ArrayList<ListItem>()
                    var j = i
                    while (j < src.size) {
                        val kj = src[j].kind
                        if (kj !is LineKind.Item || kj.list != k.list) break
                        items += ListItem(src[j].text.toSpans(), kj.checked, src[j].extras)
                        j++
                    }
                    blocks += Block.ListBlock(k.list, items, line.align, line.groupExtras)
                    i = j
                    continue
                }
            }
            i++
        }
        return Doc(blocks)
    }

    private fun dropSyntheticTail(lines: List<EditorLine>): List<EditorLine> {
        val last = lines.lastOrNull() ?: return lines
        val isBlankPara = last.kind == LineKind.Paragraph && last.text.isEmpty() &&
            last.align == Align.LEFT && last.extras.isEmpty()
        if (!isBlankPara) return lines
        val prev = lines.getOrNull(lines.size - 2)
        return if (prev == null || !prev.kind.isText) lines.dropLast(1) else lines
    }

    // ------------------------------------------------------------ text edits

    /**
     * Apply a text change to line [index]. Newlines (typed Enter or pasted
     * text) split the line, except inside code blocks. [caret] is the caret
     * position within [newText].
     */
    fun replaceText(lines: List<EditorLine>, index: Int, newText: RichText, caret: Int): EditResult {
        val line = lines[index]
        if (line.kind == LineKind.Code || '\n' !in newText.text) {
            return EditResult(lines.replace(index, line.copy(text = newText)), Focus(line.id, caret))
        }
        val segments = newText.splitLines()

        // Enter on an empty list item / quote leaves the list (becomes a paragraph).
        if (segments.size == 2 && segments.all { it.isEmpty() } &&
            (line.kind is LineKind.Item || line.kind == LineKind.Quote)
        ) {
            return EditResult(
                lines.replace(index, line.copy(kind = LineKind.Paragraph, text = RichText.EMPTY, extras = emptyMap(), groupExtras = emptyMap())),
                Focus(line.id, 0),
            )
        }

        // Enter at the very start of a non-empty line: open an empty line above.
        if (segments.size == 2 && segments[0].isEmpty() && !segments[1].isEmpty()) {
            val above = EditorLine(newId(), continuation(line.kind), RichText.EMPTY, line.align, groupExtras = line.groupExtras)
            val updated = line.copy(text = segments[1])
            val out = lines.toMutableList()
            out[index] = updated
            out.add(index, above)
            return EditResult(out, Focus(line.id, 0))
        }

        val newLines = ArrayList<EditorLine>()
        newLines += line.copy(text = segments[0])
        for (k in 1 until segments.size) {
            newLines += EditorLine(newId(), continuation(line.kind), segments[k], line.align, groupExtras = line.groupExtras)
        }
        // caret → (segment, offset)
        val before = newText.text.substring(0, caret.coerceIn(0, newText.length))
        val seg = before.count { it == '\n' }
        val offset = before.length - (before.lastIndexOf('\n') + 1)
        val out = lines.toMutableList()
        out.removeAt(index)
        out.addAll(index, newLines)
        return EditResult(out, Focus(newLines[seg].id, offset))
    }

    private fun continuation(kind: LineKind): LineKind = when (kind) {
        is LineKind.Item -> kind.copy(checked = false)
        LineKind.Quote -> LineKind.Quote
        else -> LineKind.Paragraph
    }

    /**
     * Backspace with the caret at the start of line [index]. Word-style:
     * first strip list/heading/quote/code formatting, then merge into the line above.
     */
    fun backspaceAtStart(lines: List<EditorLine>, index: Int): EditResult {
        val line = lines[index]
        if (line.kind != LineKind.Paragraph) {
            return EditResult(
                lines.replace(index, line.copy(kind = LineKind.Paragraph, groupExtras = emptyMap())),
                Focus(line.id, 0),
            )
        }
        if (index == 0) return EditResult(lines, Focus(line.id, 0))
        val prev = lines[index - 1]
        if (!prev.kind.isText) {
            // delete the image/divider above
            val out = lines.toMutableList().apply { removeAt(index - 1) }
            return EditResult(out, Focus(line.id, 0))
        }
        val merged = prev.copy(text = prev.text + line.text)
        val out = lines.toMutableList()
        out[index - 1] = merged
        out.removeAt(index)
        return EditResult(out, Focus(prev.id, prev.text.length))
    }

    // ------------------------------------------------------------ block formatting

    fun setKind(lines: List<EditorLine>, index: Int, kind: LineKind): List<EditorLine> {
        val line = lines[index]
        if (!line.kind.isText || !kind.isText) return lines
        val groupExtras = if (kind is LineKind.Item) line.groupExtras else emptyMap()
        return lines.replace(index, line.copy(kind = kind, groupExtras = groupExtras))
    }

    /** Toggle heading [level] (same level again → paragraph). */
    fun toggleHeading(lines: List<EditorLine>, index: Int, level: Int): List<EditorLine> {
        val k = lines[index].kind
        return setKind(lines, index, if (k is LineKind.Heading && k.level == level) LineKind.Paragraph else LineKind.Heading(level))
    }

    fun toggleList(lines: List<EditorLine>, index: Int, list: ListKind): List<EditorLine> {
        val k = lines[index].kind
        return setKind(lines, index, if (k is LineKind.Item && k.list == list) LineKind.Paragraph else LineKind.Item(list))
    }

    fun toggleQuote(lines: List<EditorLine>, index: Int): List<EditorLine> =
        setKind(lines, index, if (lines[index].kind == LineKind.Quote) LineKind.Paragraph else LineKind.Quote)

    fun toggleCode(lines: List<EditorLine>, index: Int): List<EditorLine> =
        setKind(lines, index, if (lines[index].kind == LineKind.Code) LineKind.Paragraph else LineKind.Code)

    fun toggleChecked(lines: List<EditorLine>, index: Int): List<EditorLine> {
        val line = lines[index]
        val k = line.kind as? LineKind.Item ?: return lines
        return lines.replace(index, line.copy(kind = k.copy(checked = !k.checked)))
    }

    /** Alignment lives on the block, so a list item aligns its whole list. */
    fun setAlign(lines: List<EditorLine>, index: Int, align: Align): List<EditorLine> {
        val range = listRun(lines, index) ?: (index..index)
        return lines.mapIndexed { i, l -> if (i in range) l.copy(align = align) else l }
    }

    /** Indices of the contiguous run of same-kind list items containing [index]. */
    fun listRun(lines: List<EditorLine>, index: Int): IntRange? {
        val k = lines[index].kind as? LineKind.Item ?: return null
        fun same(i: Int) = (lines.getOrNull(i)?.kind as? LineKind.Item)?.list == k.list
        var s = index
        var e = index
        while (same(s - 1)) s--
        while (same(e + 1)) e++
        return s..e
    }

    /** 1-based position of an ordered item within its list. */
    fun orderedNumber(lines: List<EditorLine>, index: Int): Int = index - (listRun(lines, index)?.first ?: index) + 1

    // ------------------------------------------------------------ inserts / removes

    /**
     * Insert a non-text block (image, divider) at the caret line. On an empty
     * paragraph it goes above it; otherwise below. Keeps a text line after it.
     */
    fun insertBlock(lines: List<EditorLine>, index: Int, kind: LineKind, align: Align = Align.LEFT): EditResult {
        val out = lines.toMutableList()
        val cur = lines.getOrNull(index)
        val block = EditorLine(newId(), kind, align = align)
        if (cur != null && cur.kind == LineKind.Paragraph && cur.text.isEmpty()) {
            out.add(index, block)
            return EditResult(out, Focus(cur.id, 0))
        }
        val at = (index + 1).coerceIn(0, out.size)
        out.add(at, block)
        val next = out.getOrNull(at + 1)
        return if (next != null && next.kind.isText) {
            EditResult(out, Focus(next.id, 0))
        } else {
            val para = EditorLine(newId(), LineKind.Paragraph)
            out.add(at + 1, para)
            EditResult(out, Focus(para.id, 0))
        }
    }

    fun removeLine(lines: List<EditorLine>, index: Int): List<EditorLine> {
        val out = lines.toMutableList().apply { removeAt(index) }
        if (out.isEmpty() || !out.last().kind.isText) out += EditorLine(newId(), LineKind.Paragraph)
        return out
    }

    private fun <T> List<T>.replace(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }
}
