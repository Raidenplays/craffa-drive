package com.raiden.craffa.data.model

import com.raiden.craffa.data.json.arr
import com.raiden.craffa.data.json.bool
import com.raiden.craffa.data.json.int
import com.raiden.craffa.data.json.str

/**
 * The Craffa rich-text document model (docs/craffa-notes-api-spec.md §2).
 *
 * `{ "blocks": [ … ] }` with block types paragraph, heading (1–3), bullet_list,
 * ordered_list, check_list, quote, code, image, divider. Inline marks are plain
 * strings, except links: `{"name": "link", "href": "…"}`.
 *
 * Unknown keys on blocks and list items are kept in `extras` and written back
 * unchanged, and unknown block types survive as [Block.Unknown], so a newer
 * client's additions are never destroyed by this one.
 */
data class Doc(val blocks: List<Block>) {
    companion object {
        val EMPTY = Doc(emptyList())

        fun fromJson(json: Map<String, Any?>?): Doc {
            if (json == null) return EMPTY
            return Doc(json.arr("blocks").mapNotNull { raw ->
                @Suppress("UNCHECKED_CAST")
                (raw as? Map<String, Any?>)?.let { Block.fromJson(it) }
            })
        }
    }

    fun toJson(): Map<String, Any?> = linkedMapOf("blocks" to blocks.map { it.toJson() })
}

enum class Align(val wire: String) {
    LEFT("left"), CENTER("center"), RIGHT("right");

    companion object {
        fun fromWire(s: String?): Align = entries.firstOrNull { it.wire == s } ?: LEFT
    }
}

sealed class Mark(val wire: String, val order: Int) {
    data object Bold : Mark("bold", 0)
    data object Italic : Mark("italic", 1)
    data object Underline : Mark("underline", 2)
    data object Strikethrough : Mark("strikethrough", 3)
    data object Code : Mark("code", 4)
    data class Link(val href: String) : Mark("link", 5)

    fun toJson(): Any = if (this is Link) linkedMapOf("name" to "link", "href" to href) else wire

    companion object {
        fun fromJson(raw: Any?): Mark? = when (raw) {
            "bold" -> Bold
            "italic" -> Italic
            "underline" -> Underline
            "strikethrough" -> Strikethrough
            "code" -> Code
            is Map<*, *> -> if (raw["name"] == "link") (raw["href"] as? String)?.takeIf { it.isNotBlank() }?.let { Link(it) } else null
            else -> null
        }
    }
}

data class Span(val text: String, val marks: Set<Mark> = emptySet()) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("text" to text)
        if (marks.isNotEmpty()) m["marks"] = marks.sortedBy { it.order }.map { it.toJson() }
        return m
    }

    companion object {
        fun fromJson(raw: Map<String, Any?>): Span =
            Span(raw.str("text") ?: "", raw.arr("marks").mapNotNull { Mark.fromJson(it) }.toSet())

        fun listFromJson(raw: List<Any?>): List<Span> = raw.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (it as? Map<String, Any?>)?.let(::fromJson)
        }
    }
}

fun List<Span>.plain(): String = joinToString("") { it.text }

enum class ListKind(val wire: String) {
    BULLET("bullet_list"), ORDERED("ordered_list"), CHECK("check_list");

    companion object {
        fun fromWire(s: String?): ListKind? = entries.firstOrNull { it.wire == s }
    }
}

data class ListItem(
    val spans: List<Span>,
    val checked: Boolean = false,
    val extras: Map<String, Any?> = emptyMap(),
)

sealed class Block {
    abstract val align: Align
    abstract val extras: Map<String, Any?>

    data class Paragraph(val spans: List<Span>, override val align: Align = Align.LEFT, override val extras: Map<String, Any?> = emptyMap()) : Block()
    data class Heading(val level: Int, val spans: List<Span>, override val align: Align = Align.LEFT, override val extras: Map<String, Any?> = emptyMap()) : Block()
    data class ListBlock(val kind: ListKind, val items: List<ListItem>, override val align: Align = Align.LEFT, override val extras: Map<String, Any?> = emptyMap()) : Block()
    data class Quote(val spans: List<Span>, override val align: Align = Align.LEFT, override val extras: Map<String, Any?> = emptyMap()) : Block()
    data class CodeBlock(val spans: List<Span>, override val align: Align = Align.LEFT, override val extras: Map<String, Any?> = emptyMap()) : Block()
    data class Image(val attachmentId: String, val alt: String? = null, override val align: Align = Align.LEFT, override val extras: Map<String, Any?> = emptyMap()) : Block()
    data class Divider(override val align: Align = Align.LEFT, override val extras: Map<String, Any?> = emptyMap()) : Block()

    /** A block type this client doesn't know. Kept verbatim and written back. */
    data class Unknown(val raw: Map<String, Any?>) : Block() {
        override val align: Align get() = Align.fromWire(raw["align"] as? String)
        override val extras: Map<String, Any?> get() = emptyMap()
    }

    fun toJson(): Map<String, Any?> {
        if (this is Unknown) return raw
        val m = linkedMapOf<String, Any?>()
        when (this) {
            is Paragraph -> { m["type"] = "paragraph"; m["spans"] = spans.map { it.toJson() } }
            is Heading -> { m["type"] = "heading"; m["level"] = level.coerceIn(1, 3); m["spans"] = spans.map { it.toJson() } }
            is ListBlock -> {
                m["type"] = kind.wire
                m["items"] = items.map { item ->
                    val im = linkedMapOf<String, Any?>("type" to "list_item", "spans" to item.spans.map { it.toJson() })
                    if (kind == ListKind.CHECK) im["checked"] = item.checked
                    for ((k, v) in item.extras) if (k !in im && k !in ITEM_KEYS) im[k] = v
                    im
                }
            }
            is Quote -> { m["type"] = "quote"; m["spans"] = spans.map { it.toJson() } }
            is CodeBlock -> { m["type"] = "code"; m["spans"] = spans.map { it.toJson() } }
            is Image -> { m["type"] = "image"; m["attachment_id"] = attachmentId; if (alt != null) m["alt"] = alt }
            is Divider -> m["type"] = "divider"
            is Unknown -> Unit
        }
        if (align != Align.LEFT) m["align"] = align.wire
        for ((k, v) in extras) if (k !in m && k !in BLOCK_KEYS) m[k] = v
        return m
    }

    companion object {
        private val BLOCK_KEYS = setOf("type", "align", "spans", "level", "items", "attachment_id", "alt")
        private val ITEM_KEYS = setOf("type", "spans", "checked")

        fun fromJson(raw: Map<String, Any?>): Block {
            val type = raw.str("type")
            val align = Align.fromWire(raw.str("align"))
            val extras = raw.filterKeys { it !in BLOCK_KEYS }
            val spans = { Span.listFromJson(raw.arr("spans")) }
            return when (type) {
                "paragraph" -> Paragraph(spans(), align, extras)
                "heading" -> Heading((raw.int("level") ?: 1).coerceIn(1, 3), spans(), align, extras)
                "quote" -> Quote(spans(), align, extras)
                "code" -> CodeBlock(spans(), align, extras)
                "bullet_list", "ordered_list", "check_list" -> ListBlock(
                    kind = ListKind.fromWire(type)!!,
                    items = raw.arr("items").mapNotNull { itemRaw ->
                        @Suppress("UNCHECKED_CAST")
                        (itemRaw as? Map<String, Any?>)?.let { item ->
                            ListItem(
                                spans = Span.listFromJson(item.arr("spans")),
                                checked = item.bool("checked"),
                                extras = item.filterKeys { it !in ITEM_KEYS },
                            )
                        }
                    },
                    align = align,
                    extras = extras,
                )
                "image" -> {
                    val id = raw.str("attachment_id")
                    if (id.isNullOrEmpty()) Unknown(raw) else Image(id, raw.str("alt"), align, extras)
                }
                "divider" -> Divider(align, extras)
                else -> Unknown(raw)
            }
        }
    }
}

/**
 * Mirrors the server's `extract_plaintext` / `derive_title` (app/doctext.py)
 * so the app can tell whether a stored title is auto-derived.
 */
object DocText {
    fun plaintext(doc: Doc): String {
        val parts = ArrayList<String>()
        for (b in doc.blocks) {
            when (b) {
                is Block.Paragraph -> parts += b.spans.plain()
                is Block.Heading -> parts += b.spans.plain()
                is Block.Quote -> parts += b.spans.plain()
                is Block.CodeBlock -> parts += b.spans.plain()
                is Block.ListBlock -> for (item in b.items) {
                    parts += if (b.kind == ListKind.CHECK) {
                        (if (item.checked) "[x] " else "[ ] ") + item.spans.plain()
                    } else {
                        item.spans.plain()
                    }
                }
                is Block.Image -> parts += if (!b.alt.isNullOrEmpty()) "[image: ${b.alt}]" else "[image]"
                is Block.Divider -> parts += "---"
                is Block.Unknown -> Unit
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n").trim()
    }

    fun deriveTitle(doc: Doc): String {
        val text = plaintext(doc)
        val first = if (text.isEmpty()) "" else text.split("\n")[0].trim()
        return if (first.isEmpty()) "Untitled" else takeCodePoints(first, 80)
    }

    /** Python slices by code point, not UTF-16 unit — match it for emoji titles. */
    private fun takeCodePoints(s: String, n: Int): String {
        if (s.codePointCount(0, s.length) <= n) return s
        return s.substring(0, s.offsetByCodePoints(0, n))
    }

    /** (done, total) across all check_list blocks, or null if the note has none. */
    fun checklistProgress(doc: Doc): Pair<Int, Int>? {
        var done = 0
        var total = 0
        for (b in doc.blocks) {
            if (b is Block.ListBlock && b.kind == ListKind.CHECK) {
                total += b.items.size
                done += b.items.count { it.checked }
            }
        }
        return if (total == 0) null else done to total
    }
}
