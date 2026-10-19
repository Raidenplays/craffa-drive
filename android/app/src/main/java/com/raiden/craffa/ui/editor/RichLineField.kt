package com.raiden.craffa.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.raiden.craffa.data.model.Mark
import com.raiden.craffa.editor.RichText

/**
 * Sentinel at the start of every field. Soft keyboards don't send a key
 * event for Backspace at position 0, but they do delete this character —
 * that's how a field knows to merge into the line above. It's a HAIR SPACE
 * (escaped below): Android counts it as whitespace, so the keyboard still
 * auto-capitalizes the first word, and the visual transformation hides it.
 */
private const val SENTINEL = '\u200A'

data class MarkColors(val link: Color, val codeBackground: Color)

/**
 * One editable line of rich text. The text itself lives in the ViewModel
 * (as [RichText]); this field only owns caret/composition state and renders
 * marks through a [VisualTransformation], so formatting can never be lost by
 * the platform text field.
 */
@Composable
fun RichLineField(
    lineId: Long,
    rich: RichText,
    style: TextStyle,
    focusTarget: FocusTarget?,
    onValueChange: (text: String, sel: TextRange) -> Unit,
    onBackspaceAtStart: () -> Unit,
    onFocused: (TextRange) -> Unit,
    onFocusConsumed: (Long) -> Unit,
    onShortcut: (Mark) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    code: Boolean = false,
) {
    val colors = MarkColors(
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    val modelText = rich.text
    var sel by remember(lineId) { mutableStateOf(TextRange(modelText.length + 1)) }
    var comp by remember(lineId) { mutableStateOf<TextRange?>(null) }
    var lastText by remember(lineId) { mutableStateOf(modelText) }
    var focused by remember(lineId) { mutableStateOf(false) }
    val externallyChanged = modelText != lastText
    val fullLen = modelText.length + 1

    val value = TextFieldValue(
        text = SENTINEL + modelText,
        selection = TextRange(sel.start.coerceIn(1, fullLen), sel.end.coerceIn(1, fullLen)),
        composition = if (externallyChanged) null else comp?.takeIf { it.max <= fullLen && it.min >= 1 },
    )
    SideEffect {
        if (externallyChanged) {
            lastText = modelText
            comp = null
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusTarget) {
        val t = focusTarget ?: return@LaunchedEffect
        if (t.lineId != lineId) return@LaunchedEffect
        sel = TextRange(t.caret + 1)
        comp = null
        withFrameNanos { }   // let a newly inserted line attach first
        runCatching { focusRequester.requestFocus() }
        onFocusConsumed(t.seq)
    }

    val transformation = remember(rich, colors) { RichTransformation(rich, colors) }

    BasicTextField(
        value = value,
        onValueChange = { new ->
            val t = new.text
            if (t.isEmpty() || t[0] != SENTINEL) {
                if (t == modelText) {
                    // the sentinel was deleted: Backspace at the very start
                    sel = TextRange(1)
                    onBackspaceAtStart()
                    return@BasicTextField
                }
                // sentinel replaced (select-all + type, etc.): treat as a normal edit
                val body = t.replace(SENTINEL.toString(), "")
                val caret = new.selection.end.coerceIn(0, body.length)
                sel = TextRange(caret + 1)
                comp = null
                lastText = body
                onValueChange(body, TextRange(caret))
                return@BasicTextField
            }
            val body = t.substring(1).replace(SENTINEL.toString(), "")
            val s = TextRange(new.selection.start.coerceAtLeast(1), new.selection.end.coerceAtLeast(1))
            sel = s
            comp = new.composition
            lastText = body
            val inner = TextRange((s.start - 1).coerceAtMost(body.length), (s.end - 1).coerceAtMost(body.length))
            onValueChange(body, inner)
        },
        textStyle = style.copy(color = if (style.color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.color),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
        keyboardOptions = if (code) {
            KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        } else {
            KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused && !focused) {
                    onFocused(TextRange((sel.start - 1).coerceAtLeast(0), (sel.end - 1).coerceAtLeast(0)))
                }
                focused = state.isFocused
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
                val mark = when (e.key) {
                    Key.B -> Mark.Bold
                    Key.I -> Mark.Italic
                    Key.U -> Mark.Underline
                    else -> null
                }
                if (mark != null) onShortcut(mark)
                mark != null
            },
        decorationBox = { inner ->
            Box {
                if (modelText.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
                    )
                }
                inner()
            }
        },
    )
}

/** Hides the sentinel and paints marks onto the visible text. */
private class RichTransformation(
    private val rich: RichText,
    private val colors: MarkColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isEmpty() || text.text[0] != SENTINEL) return TransformedText(text, OffsetMapping.Identity)
        val visible = text.text.substring(1)
        val b = AnnotatedString.Builder(visible)
        if (visible == rich.text) {
            for ((start, end, marks) in rich.runs()) b.addStyle(spanStyleFor(marks, colors), start, end)
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = (offset - 1).coerceIn(0, visible.length)
            override fun transformedToOriginal(offset: Int) = (offset + 1).coerceIn(1, text.length)
        }
        return TransformedText(b.toAnnotatedString(), mapping)
    }

    override fun equals(other: Any?) = other is RichTransformation && other.rich == rich && other.colors == colors
    override fun hashCode() = rich.hashCode() * 31 + colors.hashCode()
}

fun spanStyleFor(marks: Set<Mark>, colors: MarkColors): SpanStyle {
    val decorations = buildList {
        if (Mark.Underline in marks || marks.any { it is Mark.Link }) add(TextDecoration.Underline)
        if (Mark.Strikethrough in marks) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = if (Mark.Bold in marks) FontWeight.Bold else null,
        fontStyle = if (Mark.Italic in marks) FontStyle.Italic else null,
        fontFamily = if (Mark.Code in marks) FontFamily.Monospace else null,
        background = if (Mark.Code in marks) colors.codeBackground else Color.Unspecified,
        color = if (marks.any { it is Mark.Link }) colors.link else Color.Unspecified,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}
