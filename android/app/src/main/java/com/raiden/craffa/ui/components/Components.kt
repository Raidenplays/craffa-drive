package com.raiden.craffa.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raiden.craffa.data.api.ApiException

/** Which expressive shape an empty state sits in — each screen gets its own. */
enum class BlobShape { Cookie, Sunny, Flower, Clover, Pill }

/**
 * Playful empty state: an icon in a Material expressive shape that springs
 * in (scale + a little spin), then a title and a hint.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    shape: BlobShape = BlobShape.Cookie,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    action: (@Composable () -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "emptyScale",
    )
    val spin by animateFloatAsState(
        targetValue = if (shown) 0f else -40f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "emptySpin",
    )
    val polygon = when (shape) {
        BlobShape.Cookie -> MaterialShapes.Cookie9Sided
        BlobShape.Sunny -> MaterialShapes.Sunny
        BlobShape.Flower -> MaterialShapes.Flower
        BlobShape.Clover -> MaterialShapes.Clover4Leaf
        BlobShape.Pill -> MaterialShapes.Pill
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(136.dp)
                .scale(scale)
                .rotate(spin)
                .clip(polygon.toShape())
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(56.dp).rotate(-spin))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(72.dp))
    }
}

/** Error state with retry and, for auth/config problems, a jump to Settings. */
@Composable
fun ErrorState(
    error: Throwable,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsSettings = error is ApiException.Unauthorized || error is ApiException.NotConfigured
    EmptyState(
        icon = Icons.Outlined.CloudOff,
        title = if (needsSettings) "Let's get you connected" else "Can't reach your drive",
        message = error.message ?: "Something went wrong.",
        shape = BlobShape.Clover,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (needsSettings) {
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                } else {
                    Button(onClick = onRetry) { Text("Retry") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
        },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))
