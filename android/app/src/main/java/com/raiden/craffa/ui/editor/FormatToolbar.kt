package com.raiden.craffa.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raiden.craffa.data.model.Align
import com.raiden.craffa.data.model.ListKind
import com.raiden.craffa.data.model.Mark
import com.raiden.craffa.editor.LineKind

/** Docked formatting bar that rides above the keyboard. */
@Composable
fun FormatToolbar(vm: EditorViewModel, onPickImage: () -> Unit, onLink: () -> Unit) {
    val line = vm.focusedLine
    val enabled = line != null && line.kind.isText
    val kind = line?.kind

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            MarkButton(Icons.Filled.FormatBold, "Bold", vm.isMarkActive(Mark.Bold), enabled) { vm.toggleMark(Mark.Bold) }
            MarkButton(Icons.Filled.FormatItalic, "Italic", vm.isMarkActive(Mark.Italic), enabled) { vm.toggleMark(Mark.Italic) }
            MarkButton(Icons.Filled.FormatUnderlined, "Underline", vm.isMarkActive(Mark.Underline), enabled) { vm.toggleMark(Mark.Underline) }
            MarkButton(Icons.Filled.FormatStrikethrough, "Strikethrough", vm.isMarkActive(Mark.Strikethrough), enabled) { vm.toggleMark(Mark.Strikethrough) }
            MarkButton(Icons.Filled.Code, "Inline code", vm.isMarkActive(Mark.Code), enabled) { vm.toggleMark(Mark.Code) }
            MarkButton(Icons.Filled.Link, "Link", vm.isMarkActive(Mark.Link("x")), enabled) { onLink() }
            Sep()
            for (level in 1..3) {
                TextToggle("H$level", "Heading $level", kind == LineKind.Heading(level), enabled) { vm.toggleHeading(level) }
            }
            Sep()
            MarkButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", (kind as? LineKind.Item)?.list == ListKind.BULLET, enabled) {
                vm.toggleList(ListKind.BULLET)
            }
            MarkButton(Icons.Filled.FormatListNumbered, "Numbered list", (kind as? LineKind.Item)?.list == ListKind.ORDERED, enabled) {
                vm.toggleList(ListKind.ORDERED)
            }
            MarkButton(Icons.Filled.Checklist, "Checklist", (kind as? LineKind.Item)?.list == ListKind.CHECK, enabled) {
                vm.toggleList(ListKind.CHECK)
            }
            MarkButton(Icons.Filled.FormatQuote, "Quote", kind == LineKind.Quote, enabled) { vm.toggleQuote() }
            MarkButton(Icons.Filled.DataObject, "Code block", kind == LineKind.Code, enabled) { vm.toggleCodeBlock() }
            Sep()
            val align = line?.align ?: Align.LEFT
            IconButton(onClick = { vm.cycleAlign() }, enabled = enabled) {
                Icon(
                    when (align) {
                        Align.LEFT -> Icons.AutoMirrored.Filled.FormatAlignLeft
                        Align.CENTER -> Icons.Filled.FormatAlignCenter
                        Align.RIGHT -> Icons.AutoMirrored.Filled.FormatAlignRight
                    },
                    contentDescription = "Alignment: ${align.wire} (tap to change)",
                )
            }
            Sep()
            IconButton(onClick = onPickImage, enabled = !vm.uploading) { Icon(Icons.Filled.Image, "Insert image") }
            IconButton(onClick = { vm.insertDivider() }) { Icon(Icons.Filled.HorizontalRule, "Insert divider") }
        }
    }
}

@Composable
private fun MarkButton(icon: ImageVector, desc: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Icon(icon, contentDescription = desc)
    }
}

@Composable
private fun TextToggle(label: String, desc: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Sep() {
    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
}
