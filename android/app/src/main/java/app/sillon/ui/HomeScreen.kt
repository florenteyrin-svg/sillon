package app.sillon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sillon.data.Album
import app.sillon.data.Playlist
import app.sillon.player.toQueueTrack
import app.sillon.data.RecentItem
import app.sillon.data.appleArtwork
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen() {
    val ctx = LocalApp.current
    val api = ctx.api
    val player by ctx.player.state.collectAsState()
    val recent by cached("home-recent", ListSerializer(RecentItem.serializer())) { api.recent(30) }
    val newest by cached("home-newest", ListSerializer(Album.serializer())) { api.albumList("newest", 20) }
    val playlists by cached("home-playlists", ListSerializer(Playlist.serializer())) { api.playlists() }
    val top by cached("home-most-played", Playlist.serializer()) { api.playlist(app.sillon.data.MOST_PLAYED_ID) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                Text(SimpleDateFormat("EEEE d MMMM", Locale.FRENCH).format(Date()).uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Écouter", style = MaterialTheme.typography.displaySmall)
            }
        }
        val current = player.current
        if (current != null) {
            item {
                SectionTitle("Reprendre")
                Row(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable { ctx.showPlayer.value = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Artwork(current.artwork ?: api.coverUrl(current.albumId.ifEmpty { current.id }, 300), Modifier.size(84.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        QualityBadge(current.quality)
                        if (player.durationMs > 0) {
                            LinearProgressIndicator(
                                progress = { (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                    Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { ctx.player.toggle() },
                        contentAlignment = Alignment.Center) {
                        Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (player.isPlaying) "Pause" else "Lecture", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        val rec = recent.orEmpty()
        if (rec.isNotEmpty()) {
            item {
                SectionTitle("Récemment écoutés")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(rec) { i, it ->
                        val art = if (it.kind == "catalog") appleArtwork(it.artwork, 300) else api.coverUrl(it.albumId ?: it.id, 300)
                        AlbumTile(it.title, if (it.kind == "catalog") "Apple Music · ${it.artist}" else it.artist, art,
                            it.quality, width = 132.dp) { ctx.playRecent(rec, i) }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        // Les plus écoutés: a tap plays from that song, like in a playlist; "Tout voir" opens it as a playlist
        val topSongs = top?.entry.orEmpty()
        if (topSongs.isNotEmpty()) {
            item {
                SectionTitle("Les plus écoutés", "Tout voir") { ctx.openPlaylist(app.sillon.data.MOST_PLAYED_ID) }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(topSongs.take(20)) { i, s ->
                        AlbumTile(s.title, s.artist, api.coverUrl(s.albumId, 300), s.quality, width = 132.dp) {
                            ctx.play(topSongs.map { it.toQueueTrack() }, i, mixable = true)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        val pls = playlists.orEmpty()
        if (pls.isNotEmpty()) {
            item {
                SectionTitle("Playlists")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pls) {
                        AlbumTile(it.name, if (it.smart) "Intelligente · ${it.songCount} titres" else "${it.songCount} titres",
                            api.coverUrl(it.id, 300), width = 150.dp) { ctx.openPlaylist(it.id) }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        val albums = newest.orEmpty()
        if (albums.isNotEmpty()) {
            item { SectionTitle("Ajouts récents") }
            items(albums.chunked(2)) { pair ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { a ->
                        Box(Modifier.weight(1f)) { AlbumTile(a.name, a.artist, api.coverUrl(a.id, 400), a.quality) { ctx.openAlbum(a.id) } }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (current == null && rec.isEmpty() && albums.isEmpty()) item { Message("Chargement de ta bibliothèque…") }
    }
}
