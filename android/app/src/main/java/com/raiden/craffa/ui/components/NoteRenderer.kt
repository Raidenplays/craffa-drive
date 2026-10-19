package com.raiden.craffa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.raiden.craffa.data.model.Align
import com.raiden.craffa.data.model.Block
import com.raiden.craffa.data.model.Doc
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.data.model.Span
import com.raiden.craffa.ui.editor.MarkColors
import com.raiden.craffa.ui.editor.spanStyleFor
import com.raiden.craffa.ui.theme.NoteText

/** Read-only rendering of a document (old versions in history). Same styles as the editor. */
@Composable
fun NoteRenderer(doc: Doc, attachmentUrl: (String) -> String, modifier: Modifier = Modifier) {
    val colors = MarkColors(
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        if (doc.blocks.isEmpty()) {
            Text("(empty)", style = NoteText.paragraph, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (b in doc.blocks) {
            val align = when (b.align) {
                Align.LEFT -> TextAlign.Start
                Align.CENTER -> TextAlign.Center
                Align.RIGHT -> TextAlign.End
            }
            fun styled(spans: List<Span>) = annotated(spans, colors)
            when (b) {
                is Block.Paragraph -> Text(
                    styled(b.spans), style = NoteText.paragraph.copy(textAlign = align, color = onSurface),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                )
                is Block.Heading -> Text(
                    styled(b.spans),
                    style = when (b.level) { 1 -> NoteText.h1; 2 -> NoteText.h2; else -> NoteText.h3 }.copy(textAlign = align, color = onSurface),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                )
                is Block.Quote -> Row(Modifier.padding(vertical = 3.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        styled(b.spans),
                        style = NoteText.paragraph.copy(textAlign = align, color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.weight(1f),
                    )
                }
                is Block.CodeBlock -> Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text(styled(b.spans), style = NoteText.code.copy(color = onSurface), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                is Block.ListBlock -> Column(Modifier.padding(vertical = 2.dp)) {
                    b.items.forEachIndexed { i, item ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 1.dp)) {
                            when (b.kind) {
                                ListKind.BULLET -> Text("•", style = NoteText.paragraph, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                                ListKind.ORDERED -> Text("${i + 1}.", style = NoteText.paragraph, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 28.dp).padding(end = 4.dp))
                                ListKind.CHECK -> Icon(
                                    if (item.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = if (item.checked) "Done" else "Not done",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp, end = 8.dp).size(22.dp),
                                )
                            }
                            val done = b.kind == ListKind.CHECK && item.checked
                            Text(
                                styled(item.spans),
                                style = NoteText.paragraph.copy(
                                    textAlign = align,
                                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else onSurface,
                                    textDecoration = if (done) TextDecoration.LineThrough else null,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is Block.Image -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = when (b.align) {
                        Align.LEFT -> Alignment.CenterStart
                        Align.CENTER -> Alignment.Center
                        Align.RIGHT -> Alignment.CenterEnd
                    },
                ) {
                    AsyncImage(
                        model = attachmentUrl(b.attachmentId),
                        contentDescription = b.alt,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(min = 160.dp)
                            .heightIn(min = 120.dp, max = 360.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
                is Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 18.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
                is Block.Unknown -> Text(
                    "[${b.raw["type"]} block]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

private fun annotated(spans: List<Span>, colors: MarkColors): AnnotatedString {
    val b = AnnotatedString.Builder()
    for (s in spans) {
        val start = b.length
        b.append(s.text)
        if (s.marks.isNotEmpty()) b.addStyle(spanStyleFor(s.marks, colors), start, b.length)
    }
    return b.toAnnotatedString()
}
