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
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    onPhone: Boolean = false,
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
                    if (onPhone) Icon(Icons.Filled.PhoneAndroid, "Sur le téléphone", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    if (quality == "hires" || quality == "atmos") QualityBadge(quality)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (download) {
                DownloadState.None -> IconButton(onClick = { onDownload?.invoke() }) {
                    // Apple Music: downloaded to the server (into the music library)
                    Icon(Icons.Outlined.Dns, "Télécharger sur le serveur", tint = MaterialTheme.colorScheme.primary)
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

/**
 * Phone in a circle: keeps these server songs on the phone, to play them without network.
 * Empty circle = not on the phone, ring turning = downloading, filled circle = on the phone (tap to remove).
 */
@Composable
fun OfflineButton(tracks: List<app.sillon.player.QueueTrack>, label: String, tint: Color = MaterialTheme.colorScheme.primary) {
    val ctx = LocalApp.current
    val list = tracks.filter { !it.isCatalog }
    if (list.isEmpty()) return
    val onPhone = list.count { app.sillon.data.Offline.has(it.id) }
    val waiting = list.count { app.sillon.data.Offline.pending.containsKey(it.id) }
    val all = onPhone == list.size
    var confirm by remember { mutableStateOf(false) }
    Box(Modifier.size(48.dp).clip(CircleShape).clickable {
        when {
            all -> confirm = true
            waiting > 0 -> {}
            else -> {
                app.sillon.data.Offline.download(list)
                ctx.toast.value = "Téléchargement sur le téléphone : ${list.size - onPhone} titre(s)"
            }
        }
    }, contentAlignment = Alignment.Center) {
        when {
            all -> Box(Modifier.size(34.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PhoneAndroid, "$label : sur le téléphone", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            waiting > 0 -> {
                CircularProgressIndicator(progress = { onPhone.toFloat() / list.size }, modifier = Modifier.size(34.dp), color = tint, strokeWidth = 2.5.dp)
                Icon(Icons.Filled.PhoneAndroid, "Téléchargement…", tint = tint, modifier = Modifier.size(18.dp))
            }
            else -> Box(Modifier.size(34.dp).border(2.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PhoneAndroid, "$label : télécharger sur le téléphone", tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Retirer du téléphone ?") },
        text = { Text("$label ne sera plus disponible sans réseau. Rien n'est supprimé du serveur.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            app.sillon.data.Offline.remove(list.map { it.id }); confirm = false
        }) { Text("Retirer") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Annuler") } },
    )
}
