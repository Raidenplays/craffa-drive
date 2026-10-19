package com.raiden.craffa

import com.raiden.craffa.data.json.Json
import com.raiden.craffa.data.model.Align
import com.raiden.craffa.data.model.Block
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.ListItem
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.data.model.Mark
import com.raiden.craffa.data.model.Span
import com.raiden.craffa.editor.DocDiff
import com.raiden.craffa.editor.EditorDoc
import com.raiden.craffa.editor.LineKind
import com.raiden.craffa.editor.RichText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorLogicTest {
    private val bold = setOf<Mark>(Mark.Bold)

    // ------------------------------------------------------------ RichText

    @Test
    fun typing_afterBold_continuesBold() {
        val rt = RichText.plain("Hi ").insert(3, "there", bold)
        val typed = rt.applyEdit("Hi there!", caret = 9)
        assertEquals(listOf(Span("Hi "), Span("there!", bold)), typed.toSpans())
    }

    @Test
    fun typing_afterLink_isPlain_butInsideLink_staysLinked() {
        val link = Mark.Link("https://x.y")
        val rt = RichText.plain("go").let { it.insert(2, "here", setOf(link)) }
        val after = rt.applyEdit("gohere!", caret = 7)
        assertEquals(Span("!"), after.toSpans().last())
        val inside = rt.applyEdit("gohe-re", caret = 5)
        assertEquals(listOf(Span("go"), Span("he-re", setOf(link))), inside.toSpans())
    }

    @Test
    fun repeatedChars_useCaretToPlaceInsertion() {
        // "aa" with the second a bold; typing an 'a' between them (caret 2) must be plain
        val rt = RichText.plain("a").insert(1, "a", bold)
        val typed = rt.applyEdit("aaa", caret = 2)
        assertEquals(listOf(Span("aa"), Span("a", bold)), typed.toSpans())
    }

    @Test
    fun imeRecomposition_keepsFormatting() {
        // composing replaces "helo" with "hello" inside a bold word
        val rt = RichText.plain("say ").insert(4, "helo", bold)
        val fixed = rt.applyEdit("say hello", caret = 9)
        assertEquals(listOf(Span("say "), Span("hello", bold)), fixed.toSpans())
    }

    @Test
    fun typingMarks_overrideInheritance() {
        val rt = RichText.plain("ab")
        val typed = rt.applyEdit("abc", caret = 3, typingMarks = setOf(Mark.Italic))
        assertEquals(listOf(Span("ab"), Span("c", setOf(Mark.Italic))), typed.toSpans())
    }

    @Test
    fun toggle_addsThenRemoves_andMixedRangeAdds() {
        val rt = RichText.plain("hello world")
        val b = rt.toggle(0, 5, Mark.Bold)
        assertTrue(b.hasMark(0, 5, Mark.Bold))
        assertFalse(b.hasMark(0, 6, Mark.Bold))
        val mixed = b.toggle(3, 8, Mark.Bold) // partially bold → make all bold
        assertTrue(mixed.hasMark(0, 8, Mark.Bold))
        val off = mixed.toggle(0, 8, Mark.Bold)
        assertEquals(listOf(Span("hello world")), off.toSpans())
    }

    @Test
    fun links_setReplaceAndFindRange() {
        val rt = RichText.plain("see the docs").setLink(4, 12, "https://a")
        assertEquals(4 until 12, rt.linkRange(6))
        assertEquals(4 until 12, rt.linkRange(12)) // caret right after the link
        val replaced = rt.setLink(4, 12, "https://b")
        assertEquals(Mark.Link("https://b"), replaced.linkAt(5))
        assertEquals(null, replaced.setLink(4, 12, null).linkAt(5))
    }

    @Test
    fun deleteAcrossBoundary() {
        val rt = RichText.plain("ab").insert(2, "cd", bold).insert(4, "ef", emptySet())
        val del = rt.applyEdit("abf", caret = 2)
        assertEquals(listOf(Span("abf")), del.toSpans()) // the bold "cd" + "e" are gone; plain runs merge
    }

    // ------------------------------------------------------------ structure

    private fun lines(vararg kinds: Pair<LineKind, String>) = kinds.map { (k, t) ->
        com.raiden.craffa.editor.EditorLine(EditorDoc.newId(), k, RichText.plain(t))
    }

    @Test
    fun enter_splitsParagraph_andMovesCaret() {
        val ls = lines(LineKind.Paragraph to "helloworld")
        val r = EditorDoc.replaceText(ls, 0, ls[0].text.applyEdit("hello\nworld", caret = 6), 6)
        assertEquals(listOf("hello", "world"), r.lines.map { it.text.text })
        assertEquals(r.lines[1].id, r.focus!!.lineId)
        assertEquals(0, r.focus!!.caret)
    }

    @Test
    fun enter_inList_continuesList_uncheckedForChecklists() {
        val ls = lines(LineKind.Item(ListKind.CHECK, checked = true) to "milk")
        val r = EditorDoc.replaceText(ls, 0, ls[0].text.applyEdit("milk\n", caret = 5), 5)
        assertEquals(LineKind.Item(ListKind.CHECK, false), r.lines[1].kind)
        assertEquals(LineKind.Item(ListKind.CHECK, true), r.lines[0].kind)
    }

    @Test
    fun enter_onEmptyItem_exitsList() {
        val ls = lines(LineKind.Item(ListKind.BULLET) to "a", LineKind.Item(ListKind.BULLET) to "")
        val r = EditorDoc.replaceText(ls, 1, RichText.plain("\n"), 1)
        assertEquals(LineKind.Paragraph, r.lines[1].kind)
        assertEquals(2, r.lines.size)
    }

    @Test
    fun enter_afterHeading_givesParagraph_atStart_opensLineAbove() {
        val ls = lines(LineKind.Heading(1) to "Title")
        val end = EditorDoc.replaceText(ls, 0, ls[0].text.applyEdit("Title\n", caret = 6), 6)
        assertEquals(LineKind.Paragraph, end.lines[1].kind)
        val start = EditorDoc.replaceText(ls, 0, ls[0].text.applyEdit("\nTitle", caret = 1), 1)
        assertEquals(listOf("", "Title"), start.lines.map { it.text.text })
        assertEquals(LineKind.Heading(1), start.lines[1].kind)
        assertEquals(ls[0].id, start.focus!!.lineId)
    }

    @Test
    fun paste_multiline_makesSeveralLines() {
        val ls = lines(LineKind.Paragraph to "")
        val r = EditorDoc.replaceText(ls, 0, RichText.plain("a\nb\nc"), 5)
        assertEquals(listOf("a", "b", "c"), r.lines.map { it.text.text })
        assertEquals(r.lines[2].id, r.focus!!.lineId)
        assertEquals(1, r.focus!!.caret)
    }

    @Test
    fun codeBlocks_keepNewlines() {
        val ls = lines(LineKind.Code to "a")
        val r = EditorDoc.replaceText(ls, 0, RichText.plain("a\nb"), 3)
        assertEquals(1, r.lines.size)
        assertEquals("a\nb", r.lines[0].text.text)
    }

    @Test
    fun backspace_stripsFormatting_thenMerges_thenDeletesImage() {
        var ls = lines(LineKind.Paragraph to "one", LineKind.Item(ListKind.BULLET) to "two")
        var r = EditorDoc.backspaceAtStart(ls, 1)
        assertEquals(LineKind.Paragraph, r.lines[1].kind)
        r = EditorDoc.backspaceAtStart(r.lines, 1)
        assertEquals(listOf("onetwo"), r.lines.map { it.text.text })
        assertEquals(3, r.focus!!.caret)

        ls = lines(LineKind.Image("img", null) to "", LineKind.Paragraph to "x")
        r = EditorDoc.backspaceAtStart(ls, 1)
        assertEquals(1, r.lines.size)
    }

    @Test
    fun linesRoundTrip_groupsLists_andAlignsWholeList() {
        val doc = Doc(listOf(
            Block.Heading(2, listOf(Span("T", setOf(Mark.Bold)))),
            Block.ListBlock(ListKind.ORDERED, listOf(ListItem(listOf(Span("a"))), ListItem(listOf(Span("b"))))),
            Block.ListBlock(ListKind.CHECK, listOf(ListItem(listOf(Span("c")), true))),
            Block.Image("att1", "pic"),
        ))
        val ls = EditorDoc.toLines(doc)
        assertEquals(6, ls.size) // + synthetic trailing paragraph after the image
        assertEquals(doc, EditorDoc.toDoc(ls))
        assertEquals(2, EditorDoc.orderedNumber(ls, 2))

        val centered = EditorDoc.setAlign(ls, 2, Align.CENTER)
        val list = EditorDoc.toDoc(centered).blocks[1] as Block.ListBlock
        assertEquals(Align.CENTER, list.align)
        assertEquals(Align.CENTER, centered[1].align)
    }

    @Test
    fun emptyDoc_roundTripsToEmpty() {
        assertEquals(Doc.EMPTY, EditorDoc.toDoc(EditorDoc.toLines(Doc.EMPTY)))
        // and the JSON the editor would send is valid per spec
        assertEquals("""{"blocks":[]}""", Json.stringify(Doc.EMPTY.toJson()))
    }

    @Test
    fun insertImage_onEmptyLine_goesAbove() {
        val ls = lines(LineKind.Paragraph to "text", LineKind.Paragraph to "")
        val r = EditorDoc.insertBlock(ls, 1, LineKind.Image("a", null))
        assertEquals(listOf("text", "", ""), r.lines.map { it.text.text })
        assertTrue(r.lines[1].kind is LineKind.Image)
        assertEquals(ls[1].id, r.focus!!.lineId)
        val r2 = EditorDoc.insertBlock(ls, 0, LineKind.Divider)
        assertTrue(r2.lines[1].kind == LineKind.Divider)
        assertEquals(ls[1].id, r2.focus!!.lineId)
    }

    @Test
    fun diff_marksServerAndMineLines() {
        val server = Doc(listOf(Block.Paragraph(listOf(Span("a"))), Block.Paragraph(listOf(Span("b")))))
        val mine = Doc(listOf(Block.Paragraph(listOf(Span("a"))), Block.Paragraph(listOf(Span("c")))))
        val d = DocDiff.diff(server, mine)
        assertEquals(listOf(DocDiff.Op.SAME, DocDiff.Op.ONLY_SERVER, DocDiff.Op.ONLY_MINE), d.map { it.op })
    }
}
