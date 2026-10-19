package com.raiden.craffa

import com.raiden.craffa.data.json.Json
import com.raiden.craffa.data.model.Align
import com.raiden.craffa.data.model.Block
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.DocText
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.data.model.Mark
import com.raiden.craffa.data.model.Time
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocModelTest {

    private val specExample = """
    {
      "blocks": [
        { "type": "heading", "level": 1, "align": "left",
          "spans": [{ "text": "Grocery run", "marks": ["bold"] }] },
        { "type": "bullet_list", "items": [
          { "type": "list_item", "spans": [{ "text": "Oat milk" }] },
          { "type": "list_item", "spans": [{ "text": "Eggs", "marks": ["strikethrough"] }] }
        ]},
        { "type": "check_list", "align": "center", "items": [
          { "type": "list_item", "spans": [{ "text": "Bread" }], "checked": true },
          { "type": "list_item", "spans": [{ "text": "Jam" }] }
        ]},
        { "type": "paragraph", "spans": [
          { "text": "See " }, { "text": "the list", "marks": [{"name": "link", "href": "https://example.com"}] }
        ]},
        { "type": "image", "attachment_id": "a91f", "alt": "receipt" },
        { "type": "code", "language": "kotlin", "spans": [{ "text": "val x = 1\nval y = 2" }] },
        { "type": "divider" },
        { "type": "future_block", "foo": [1, 2, 3] }
      ]
    }
    """

    @Test
    fun json_roundTripsValues() {
        val text = """{"a":[1,2.5,true,false,null,"x\"y\né"],"b":{"c":-3e2}}"""
        val parsed = Json.parse(text)
        val again = Json.parse(Json.stringify(parsed))
        assertEquals(parsed, again)
        @Suppress("UNCHECKED_CAST")
        val a = (parsed as Map<String, Any?>)["a"] as List<Any?>
        assertEquals(1L, a[0])
        assertEquals("x\"y\né", a[5])
    }

    @Test
    fun json_writesExplicitNull() {
        assertEquals("""{"folder_id":null}""", Json.stringify(linkedMapOf("folder_id" to null)))
    }

    @Test
    fun doc_parsesSpecExample() {
        val doc = Doc.fromJson(Json.parseObject(specExample))
        assertEquals(8, doc.blocks.size)
        val h = assertIs<Block.Heading>(doc.blocks[0])
        assertEquals(1, h.level)
        assertEquals(setOf<Mark>(Mark.Bold), h.spans[0].marks)
        val check = assertIs<Block.ListBlock>(doc.blocks[2])
        assertEquals(ListKind.CHECK, check.kind)
        assertEquals(Align.CENTER, check.align)
        assertEquals(listOf(true, false), check.items.map { it.checked })
        val p = assertIs<Block.Paragraph>(doc.blocks[3])
        assertEquals(setOf<Mark>(Mark.Link("https://example.com")), p.spans[1].marks)
        assertIs<Block.Image>(doc.blocks[4])
        val code = assertIs<Block.CodeBlock>(doc.blocks[5])
        assertEquals("kotlin", code.extras["language"])
        assertIs<Block.Unknown>(doc.blocks[7])
    }

    @Test
    fun doc_roundTripIsStable() {
        val doc = Doc.fromJson(Json.parseObject(specExample))
        val json1 = Json.stringify(doc.toJson())
        val doc2 = Doc.fromJson(Json.parseObject(json1))
        assertEquals(doc, doc2)
        assertEquals(json1, Json.stringify(doc2.toJson()))
        // extras + unknown blocks survive
        assertTrue("\"language\":\"kotlin\"" in json1)
        assertTrue("\"future_block\"" in json1)
        // check_list items always carry checked; other lists never do
        assertTrue("\"checked\":false" in json1)
    }

    @Test
    fun plaintext_matchesServerRules() {
        val doc = Doc.fromJson(Json.parseObject(specExample))
        val expected = listOf(
            "Grocery run", "Oat milk", "Eggs", "[x] Bread", "[ ] Jam",
            "See the list", "[image: receipt]", "val x = 1\nval y = 2", "---",
        ).joinToString("\n")
        assertEquals(expected, DocText.plaintext(doc))
        assertEquals("Grocery run", DocText.deriveTitle(doc))
        assertEquals(1 to 2, DocText.checklistProgress(doc))
        assertEquals("Untitled", DocText.deriveTitle(Doc.EMPTY))
    }

    @Test
    fun time_parsesServerFormats() {
        val a = Time.parse("2026-10-19T13:11:08.123456+00:00")
        val b = Time.parse("2026-10-19T13:11:08.123456Z")
        assertEquals(a, b)
        assertEquals("2026-10-20T09:00:00Z", Time.format(Time.parse("2026-10-20T06:00:00-03:00")!!))
        assertEquals(Time.parse("2026-10-20T09:00:00Z"), Time.parse("2026-10-20T09:00:00"))
        assertNull(Time.parse("nope"))
    }
}
