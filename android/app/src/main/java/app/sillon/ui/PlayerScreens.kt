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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
    val art = t?.let { artFor(ctx, it, 1000) }

    Box(Modifier.fillMaxSize().background(Color(0xFF1C1C22))) {
        // Background: the cover, blurred (Android 12+), under a dark veil
        if (art != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(60.dp))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Fermer", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "File d'attente", tint = if (showQueue) MaterialTheme.colorScheme.primary else Color.White)
                }
            }
            if (t == null) { Message("Rien en lecture"); return@Column }

            if (showQueue) {
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
            } else {
                Spacer(Modifier.weight(0.4f))
                Box(Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))) {
                    Artwork(art, Modifier.fillMaxSize(), corner = 14.dp)
                    // Animated cover, like Apple Music: a silent looping video over the still cover
                    if (t.animatedCover && !t.isCatalog && t.albumId.isNotEmpty()) AnimatedCover(ctx.api.animatedCoverUrl(t.albumId))
                }
                Spacer(Modifier.weight(0.4f))
            }

            Spacer(Modifier.height(16.dp))
            Text(t.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Color.White.copy(alpha = 0.72f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

            val dur = s.durationMs.coerceAtLeast(1)
            val pos = seeking ?: (s.positionMs.toFloat() / dur).coerceIn(0f, 1f)
            Slider(
                value = pos, onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { ctx.player.seekTo((it * dur).toLong()) }; seeking = null },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration((pos * dur / 1000).toInt()), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                QualityBadge(t.quality, onDark = true, large = true)
                if (t.isCatalog) Text("Apple Music", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("-" + formatDuration(((dur - pos * dur) / 1000).toInt()), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
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
            Spacer(Modifier.height(12.dp))
        }
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
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = exo
            }
        },
        // Shown only once the first frame is drawn, so the still cover never flashes black
        modifier = Modifier.fillMaxSize().then(if (ready) Modifier else Modifier.width(0.dp)),
    )
}
