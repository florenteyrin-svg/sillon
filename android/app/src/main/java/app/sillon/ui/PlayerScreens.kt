package app.sillon.ui

import android.os.Build
import android.view.ViewGroup
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.sillon.data.formatDuration
import app.sillon.player.QueueTrack
import coil3.compose.AsyncImage

private fun artFor(ctx: AppContext, t: QueueTrack, size: Int) = t.artwork ?: ctx.api.coverUrl(t.albumId.ifEmpty { t.id }, size)

@Composable
fun MiniPlayer() {
    val ctx = LocalApp.current
    val s by ctx.player.state.collectAsState()
    val t = s.current ?: return
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth().shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { ctx.showPlayer.value = true }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(artFor(ctx, t, 150), Modifier.size(44.dp), corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (t.isCatalog) "${t.artist} · Apple Music" else t.artist, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        QualityBadge(t.quality)
        if (s.isBuffering && !s.isPlaying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        IconButton(onClick = { ctx.player.toggle() }) {
            Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.isPlaying) "Pause" else "Lecture")
        }
        IconButton(onClick = { ctx.player.next() }) { Icon(Icons.Filled.SkipNext, "Suivant") }
    }
}

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val ctx = LocalApp.current
    val s by ctx.player.state.collectAsState()
    val t = s.current
    var showQueue by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    val art = t?.let { artFor(ctx, it, 1200) }

    var showOutput by remember { mutableStateOf(false) }

    // Like Apple Music: the cover across the top, melting into a blurred, darkened copy of itself behind
    // the controls
    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1F))) {
        if (art != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(90.dp))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        Column(Modifier.fillMaxSize()) {
            if (!showQueue) Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            androidx.compose.ui.graphics.Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
            ) {
                if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (t != null && t.animatedCover && !t.isCatalog && t.albumId.isNotEmpty()) AnimatedCover(ctx.api.animatedCoverUrl(t.albumId))
                // The cover opens the album, like Apple Music
                if (t != null) Box(Modifier.fillMaxSize().clickableNoRipple { ctx.showAlbumOf(t) })
            }
            Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp)) {
            if (t == null) { Message("Rien en lecture"); return@Column }

            if (showQueue) {
                Spacer(Modifier.statusBarsPadding().height(56.dp))
                Text("À suivre", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(s.queue) { i, q ->
                        Row(Modifier.fillMaxWidth().clickable { ctx.player.skipTo(i) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Artwork(artFor(ctx, q, 150), Modifier.size(44.dp), corner = 6.dp)
                            Column(Modifier.weight(1f)) {
                                Text(q.title, color = if (i == s.index) MaterialTheme.colorScheme.primary else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(q.artist, color = Color.White.copy(alpha = 0.6f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Title: the album; artist: the artist page. Nothing here changes the song playing.
                    Text(t.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickableNoRipple { ctx.showAlbumOf(t) })
                    Text(t.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickableNoRipple { ctx.showArtistOf(t) })
                }
                QualityBadge(t.quality, onDark = true, large = true)
                if (!t.isCatalog) {
                    val fav = t.id in ctx.favorites.value
                    IconButton(onClick = { ctx.toggleFavorite(t.id) }) {
                        Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (fav) "Retirer des Favoris" else "Ajouter aux Favoris",
                            tint = if (fav) Color(0xFFFF4D6D) else Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }

            val dur = s.durationMs.coerceAtLeast(1)
            val pos = seeking ?: (s.positionMs.toFloat() / dur).coerceIn(0f, 1f)
            Slider(
                value = pos, onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { ctx.player.seekTo((it * dur).toLong()) }; seeking = null },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration((pos * dur / 1000).toInt()), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (s.remote != null) Text("Sur ${s.remote}", color = Color(0xFFD6BCFA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                else if (t.isCatalog) Text("Apple Music", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("-" + formatDuration(((dur - pos * dur) / 1000).toInt()), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ctx.player.setShuffle(!s.shuffle) }) {
                    Icon(Icons.Filled.Shuffle, "Aléatoire", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f))
                }
                IconButton(onClick = { ctx.player.previous() }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Précédent", tint = Color.White, modifier = Modifier.size(44.dp))
                }
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    if (s.isBuffering && !s.isPlaying) CircularProgressIndicator(Modifier.size(76.dp), color = Color.White.copy(alpha = 0.5f), strokeWidth = 2.dp)
                    IconButton(onClick = { ctx.player.toggle() }, modifier = Modifier.size(76.dp)) {
                        Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.isPlaying) "Pause" else "Lecture",
                            tint = Color.White, modifier = Modifier.size(56.dp))
                    }
                }
                IconButton(onClick = { ctx.player.next() }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.SkipNext, "Suivant", tint = Color.White, modifier = Modifier.size(44.dp))
                }
                IconButton(onClick = { ctx.player.cycleRepeat() }) {
                    Icon(if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Répéter",
                        tint = if (s.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f))
                }
            }

            // Album / Artist: the album in order, or all the artist's songs shuffled, right after this song
            if (!t.isCatalog) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QueueAfterButton("Album à la suite", Icons.Filled.Album, Modifier.weight(1f)) {
                        ctx.player.queueAfter("album") { ok -> ctx.toast.value = if (ok) "Album « ${t.album} » ajouté à la suite" else "Album introuvable sur le serveur" }
                    }
                    QueueAfterButton("Artiste à la suite", Icons.Filled.Person, Modifier.weight(1f)) {
                        ctx.player.queueAfter("artist") { ok -> ctx.toast.value = if (ok) "Titres de ${t.artist} ajoutés à la suite (aléatoire)" else "Artiste introuvable sur le serveur" }
                    }
                }
            }
            if (t.mixable) DjButton()
            Spacer(Modifier.height(8.dp))
            }
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Fermer", tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showOutput = true }) { Icon(Icons.Filled.Cast, "Écouter sur Music Assistant", tint = Color.White) }
            IconButton(onClick = { showQueue = !showQueue }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "File d'attente", tint = if (showQueue) MaterialTheme.colorScheme.primary else Color.White)
            }
        }
        if (showOutput) OutputSheet { showOutput = false }
    }
}

@Composable
private fun QueueAfterButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Color.White)
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun DjButton() {
    val ctx = LocalApp.current
    var on by remember { mutableStateOf(ctx.app.settings.djEnabled) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (on) Color(0xFF8B5CF6).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f))
            .clickable {
                on = !on
                ctx.app.settings.djEnabled = on
                ctx.toast.value = if (on) "Mix DJ activé : les transitions se préparent sur le serveur" else "Mix DJ désactivé"
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Tune, null, tint = if (on) Color(0xFFD6BCFA) else Color.White)
        Column(Modifier.weight(1f)) {
            Text("Mix DJ", color = Color.White, fontWeight = FontWeight.Bold)
            Text(if (on) "La voix passe d'un titre à l'autre, comme un DJ" else "Enchaîne les titres comme un DJ",
                color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = null)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun AnimatedCover(url: String) {
    val context = LocalContext.current
    val ctx = LocalApp.current
    val exo = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(ctx.api.http)))
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }
    var ready by remember(url) { mutableStateOf(false) }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    // A TextureView (not a SurfaceView) so the video melts into the background like the still cover
    AndroidView(
        factory = { c -> android.view.TextureView(c).also { exo.setVideoTextureView(it) } },
        // Shown only once the first frame is drawn, so the still cover never flashes black
        modifier = Modifier.fillMaxSize().then(if (ready) Modifier else Modifier.width(0.dp)),
    )
}
