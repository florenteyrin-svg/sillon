package app.sillon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                error = { NoteIcon() },
            )
        } else NoteIcon()
    }
}

@Composable
private fun NoteIcon() = Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))

/** HI-RES / CD / ATMOS / MP3 logo. */
@Composable
fun QualityBadge(quality: String?, onDark: Boolean = false, large: Boolean = false) {
    val (label, bg, fg) = when (quality) {
        "hires" -> Triple("HI-RES", HiResGold, Color(0xFF2A1E00))
        "atmos" -> Triple("ATMOS", AtmosBlue, Color.White)
        "cd" -> Triple("CD", if (onDark) Color.White else Color(0xFF111114), if (onDark) Color(0xFF111114) else Color.White)
        "lossy" -> Triple("MP3", Color.Transparent, if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        else -> return
    }
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.clip(shape).background(bg)
            .then(if (bg == Color.Transparent) Modifier.border(1.dp, fg, shape) else Modifier)
            .padding(horizontal = if (large) 9.dp else 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = fg, fontSize = if (large) 13.sp else 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onAction).padding(8.dp))
        }
    }
}

@Composable
fun AlbumTile(title: String, subtitle: String?, artUrl: String?, quality: String? = null, width: Dp? = null, onClick: () -> Unit) {
    Column(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Artwork(artUrl, Modifier.fillMaxWidth().aspectRatio(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (quality == "hires" || quality == "atmos") QualityBadge(quality)
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** State of the small download arrow next to an Apple Music result. */
enum class DownloadState { None, Pending, InLibrary }

@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    artUrl: String?,
    quality: String? = null,
    number: Int? = null,
    playing: Boolean = false,
    download: DownloadState? = null,
    onDownload: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (number != null) {
                Text(if (number > 0) number.toString() else "", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp), fontSize = 15.sp)
            } else {
                Artwork(artUrl, Modifier.size(48.dp), corner = 6.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (quality == "hires" || quality == "atmos") QualityBadge(quality)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (download) {
                DownloadState.None -> IconButton(onClick = { onDownload?.invoke() }) {
                    Icon(Icons.Outlined.ArrowCircleDown, "Télécharger dans ma bibliothèque", tint = MaterialTheme.colorScheme.primary)
                }
                DownloadState.Pending -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
                DownloadState.InLibrary -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CheckCircle, "Dans ma bibliothèque", tint = Color(0xFF1F9D55))
                }
                null -> {}
            }
        }
        HorizontalDivider(Modifier.padding(start = if (number != null) 56.dp else 80.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun NavRow(title: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 19.sp, modifier = Modifier.weight(1f))
            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxWidth().height(160.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun Modifier.aspectRatioSquare(): Modifier = this.aspectRatio(1f)

@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
    indication = null, onClick = onClick,
)
