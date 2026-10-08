package com.vdelaar.mylibby.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.ui.theme.Flame
import com.vdelaar.mylibby.ui.theme.FlameDeep
import com.vdelaar.mylibby.ui.theme.Gold
import kotlin.math.absoluteValue

@Composable
fun coverUrl(book: Book, thumbnail: Boolean = true): Any? {
    if (book.isLocal) return book.localCover?.let { java.io.File(it) }
    val app = LocalContext.current.applicationContext as MyLibbyApp
    return app.container.api.coverUrl(book.id, book.coverVersion, thumbnail)
}

private val placeholderPalettes = listOf(
    Color(0xFF8A4B1F) to Color(0xFFD08A4E),
    Color(0xFF2E4A62) to Color(0xFF6C93B5),
    Color(0xFF4F5B2E) to Color(0xFF9AA864),
    Color(0xFF5E2E4A) to Color(0xFFB26C93),
    Color(0xFF2E5E57) to Color(0xFF6CB2A7),
    Color(0xFF3B3355) to Color(0xFF8B80B8),
)

@Composable
fun GeneratedCover(title: String, author: String, modifier: Modifier = Modifier) {
    val (a, b) = placeholderPalettes[title.hashCode().absoluteValue % placeholderPalettes.size]
    Box(
        modifier.background(Brush.linearGradient(listOf(a, b))).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(author, color = Color.White.copy(alpha = .8f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/**
 * A book cover with a soft shadow, rounded spine-like corners and status badges:
 * a cloud when the book isn't on this device ([downloaded] = false; null hides it),
 * a check when finished, a heart for favorites and a checkmark overlay when [selected].
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    thumbnail: Boolean = true,
    showProgress: Boolean = false,
    downloaded: Boolean? = null,
    favourite: Boolean = false,
    elevation: Dp = 6.dp,
    selected: Boolean? = null,
) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Box(modifier.aspectRatio(2f / 3f)) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(elevation, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            SubcomposeAsyncImage(
                model = coverUrl(book, thumbnail),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { GeneratedCover(book.title, book.authorLine, Modifier.fillMaxSize()) },
                error = { GeneratedCover(book.title, book.authorLine, Modifier.fillMaxSize()) },
            )
            // Spine highlight
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = .18f),
                            .04f to Color.White.copy(alpha = .10f),
                            .08f to Color.Transparent,
                        )
                    )
            )
            if (showProgress && (book.progress ?: 0f) > 0f) {
                LinearProgressIndicator(
                    progress = { ((book.progress ?: 0f) / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Black.copy(alpha = .35f),
                    drawStopIndicator = {},
                )
            }
        }
        val finished = book.readStatus == "READ" || (book.progress ?: 0f) >= 99.5f
        Row(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (favourite) CoverBadge(Icons.Rounded.Favorite, Color(0xFFE5484D), stringResource(R.string.favorite))
            if (book.isLocal) CoverBadge(Icons.Rounded.PhoneAndroid, Color(0xFF7B4FBF), stringResource(R.string.local_only))
            else if (downloaded == false) CoverBadge(Icons.Outlined.Cloud, Color(0xFF5F6B7A), stringResource(R.string.not_downloaded))
        }
        if (finished) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF2E9E5B),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White),
                shadowElevation = 2.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = if (showProgress) 10.dp else 6.dp).size(22.dp),
            ) {
                Icon(Icons.Rounded.Check, stringResource(R.string.status_read), tint = Color.White, modifier = Modifier.padding(3.dp))
            }
        }
        if (selected != null) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp, topStart = 3.dp, bottomStart = 3.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else Color.Black.copy(alpha = .08f))
            )
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .9f),
                border = androidx.compose.foundation.BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0xFF5F6B7A)),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp),
            ) {
                if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.selected), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp))
            }
        }
    }
}

@Composable
private fun CoverBadge(icon: ImageVector, tint: Color, description: String) {
    Surface(shape = CircleShape, color = Color.White.copy(alpha = .92f), shadowElevation = 2.dp) {
        Icon(icon, description, tint = tint, modifier = Modifier.padding(3.dp).size(13.dp))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun EmptyState(
    emoji: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, fontSize = 52.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_try_again))
        }
    }
}

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Circular progress ring used for daily goal and book progress. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    stroke: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(size.width - s, size.height - s))
            drawArc(color, -90f, 360f * animated, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(size.width - s, size.height - s))
        }
        content()
    }
}

/** Animated streak flame; grey when the streak is not alive. */
@Composable
fun StreakFlame(active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "flame")
    val flicker by t.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "flicker")
    val outer = if (active) listOf(Gold, Flame, FlameDeep) else listOf(Color(0xFFBDB6AC), Color(0xFF8F8880), Color(0xFF6F6962))
    Canvas(modifier.scale(if (active) flicker else 1f)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .5f, 0f)
            cubicTo(w * .62f, h * .22f, w * .98f, h * .38f, w * .92f, h * .68f)
            cubicTo(w * .86f, h * .92f, w * .66f, h, w * .5f, h)
            cubicTo(w * .34f, h, w * .14f, h * .92f, w * .08f, h * .68f)
            cubicTo(w * .02f, h * .46f, w * .24f, h * .34f, w * .32f, h * .14f)
            cubicTo(w * .40f, h * .30f, w * .44f, h * .36f, w * .5f, h * .40f)
            cubicTo(w * .52f, h * .26f, w * .46f, h * .10f, w * .5f, 0f)
            close()
        }
        drawPath(path, Brush.verticalGradient(outer))
        val inner = Path().apply {
            moveTo(w * .5f, h * .48f)
            cubicTo(w * .64f, h * .62f, w * .72f, h * .74f, w * .68f, h * .84f)
            cubicTo(w * .64f, h * .95f, w * .36f, h * .95f, w * .32f, h * .84f)
            cubicTo(w * .28f, h * .72f, w * .42f, h * .62f, w * .5f, h * .48f)
            close()
        }
        drawPath(inner, Color.White.copy(alpha = if (active) .55f else .3f))
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            if (icon != null) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(8.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun ClickableRow(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clickable(onClick = onClick)) { content() }
}
