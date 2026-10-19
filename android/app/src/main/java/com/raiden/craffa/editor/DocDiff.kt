package com.raiden.craffa.editor

import com.raiden.craffa.data.model.Block
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.data.model.plain

/** Line-level diff between two documents, for the 409 "server vs mine" view. */
object DocDiff {
    enum class Op { SAME, ONLY_SERVER, ONLY_MINE }

    data class Line(val op: Op, val text: String)

    /** A readable one-line-per-thing rendering of a doc. */
    fun describe(doc: Doc): List<String> {
        val out = ArrayList<String>()
        for (b in doc.blocks) {
            when (b) {
                is Block.Paragraph -> b.spans.plain().takeIf { it.isNotBlank() }?.let { out += it }
                is Block.Heading -> out += "#".repeat(b.level) + " " + b.spans.plain()
                is Block.Quote -> out += "❝ " + b.spans.plain()
                is Block.CodeBlock -> b.spans.plain().split('\n').forEach { out += "    $it" }
                is Block.ListBlock -> b.items.forEachIndexed { i, item ->
                    val bullet = when (b.kind) {
                        ListKind.BULLET -> "•"
                        ListKind.ORDERED -> "${i + 1}."
                        ListKind.CHECK -> if (item.checked) "☑" else "☐"
                    }
                    out += "$bullet ${item.spans.plain()}"
                }
                is Block.Image -> out += "🖼 Image" + (b.alt?.let { " ($it)" } ?: "")
                is Block.Divider -> out += "———"
                is Block.Unknown -> out += "[${b.raw["type"] ?: "unsupported"} block]"
            }
        }
        return out
    }

    /** Diff server → mine. Formatting-only changes show as a changed line pair. */
    fun diff(server: Doc, mine: Doc): List<Line> = diffLines(describe(server), describe(mine))

    fun diffLines(a: List<String>, b: List<String>): List<Line> {
        // Too big for an O(n·m) table on a phone: show both sides whole.
        if (a.size.toLong() * b.size > 4_000_000L) {
            return a.map { Line(Op.ONLY_SERVER, it) } + b.map { Line(Op.ONLY_MINE, it) }
        }
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Line>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += Line(Op.SAME, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += Line(Op.ONLY_SERVER, a[i]); i++ }
                else -> { out += Line(Op.ONLY_MINE, b[j]); j++ }
            }
        }
        while (i < n) out += Line(Op.ONLY_SERVER, a[i++])
        while (j < m) out += Line(Op.ONLY_MINE, b[j++])
        return out
    }
}
