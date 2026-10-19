package com.raiden.craffa.ui.notes

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raiden.craffa.data.model.DocText
import com.raiden.craffa.data.model.Note
import com.raiden.craffa.ui.Format
import java.time.Instant

/** An entry in a card's long-press menu. */
data class CardAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** A note in a list: title, snippet, checklist progress, reminder/folder pills, favorite star. */
@Composable
fun NoteCard(
    note: Note,
    folderName: String?,
    showFolder: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val progress = remember(note.body) { DocText.checklistProgress(note.body) }
    val starScale by animateFloatAsState(
        targetValue = if (note.favorite) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
        label = "star",
    )

    Box(modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (note.pinned) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Row(Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (note.pinned) {
                            Icon(
                                Icons.Filled.PushPin, "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            note.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val snippet = note.snippet
                    if (snippet.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (progress != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearWavyProgressIndicator(
                                progress = { progress.first.toFloat() / progress.second },
                                modifier = Modifier.width(96.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${progress.first}/${progress.second}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        note.reminderAt?.let { at ->
                            val overdue = at.isBefore(Instant.now())
                            Pill(
                                icon = Icons.Outlined.Alarm,
                                text = Format.reminder(at),
                                container = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                content = if (overdue) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        if (showFolder && folderName != null) {
                            Pill(Icons.Outlined.Folder, folderName)
                        }
                        if (progress != null && progress.first == progress.second) {
                            Pill(Icons.Outlined.TaskAlt, "Done")
                        }
                        Text(
                            Format.relative(note.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (note.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (note.favorite) "Unfavorite" else "Favorite",
                        tint = if (note.favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.scale(starScale),
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (a in actions) {
                DropdownMenuItem(
                    text = { Text(a.label) },
                    leadingIcon = { Icon(a.icon, null) },
                    onClick = { menu = false; a.onClick() },
                )
            }
        }
    }
}

@Composable
fun Pill(
    icon: ImageVector,
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(icon, null, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
