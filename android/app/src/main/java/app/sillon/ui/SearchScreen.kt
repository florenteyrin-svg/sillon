package app.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sillon.data.CatalogAlbum
import app.sillon.data.CatalogSearch
import app.sillon.data.SillonApi
import app.sillon.data.appleArtwork
import app.sillon.player.toQueueTrack
import kotlinx.coroutines.delay

/** Download arrows tapped during this session: Apple id -> state (the server tells when it is done). */
object Downloads {
    val states = mutableStateMapOf<String, DownloadState>()
}

@Composable
fun SearchScreen() {
    val ctx = LocalApp.current
    var query by rememberSaveable { mutableStateOf("") }
    var appleMusic by rememberSaveable { mutableStateOf(true) }
    var library by remember { mutableStateOf<SillonApi.LibrarySearch?>(null) }
    var catalog by remember { mutableStateOf<CatalogSearch?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, appleMusic) {
        error = null
        if (query.isBlank()) { library = null; catalog = null; return@LaunchedEffect }
        delay(350) // wait for the end of typing
        runCatching { ctx.api.search(query) }.onSuccess { library = it }
        if (appleMusic) runCatching { ctx.api.catalogSearch(query) }.onSuccess { catalog = it }.onFailure { error = it.message }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenTitle("Recherche")
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Artistes, titres, albums") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                SegmentedButton(appleMusic, { appleMusic = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Partout") }
                SegmentedButton(!appleMusic, { appleMusic = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Ma bibliothèque") }
            }
        }

        val lib = library
        if (lib != null && (lib.songs.isNotEmpty() || lib.albums.isNotEmpty() || lib.artists.isNotEmpty())) {
            item { SectionTitle("Dans ta bibliothèque") }
            items(lib.artists.take(3), key = { "ar-" + it.id }) { a ->
                TrackRow(a.name, "Artiste", ctx.api.coverUrl(a.id, 150)) { ctx.openArtist(a.id) }
            }
            items(lib.albums.take(6), key = { "al-" + it.id }) { a ->
                TrackRow(a.name, "Album · ${a.artist}", ctx.api.coverUrl(a.id, 150), a.quality) { ctx.openAlbum(a.id) }
            }
            itemsIndexed(lib.songs.take(15), key = { _, s -> "s-" + s.id }) { i, s ->
                TrackRow(s.title, "Titre · ${s.artist}", ctx.api.coverUrl(s.albumId, 150), s.quality) {
                    ctx.play(lib.songs.map { it.toQueueTrack() }, i)
                }
            }
        }

        val cat = catalog
        if (appleMusic && cat != null) {
            item {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SectionTitle("Apple Music") }
                    Text("Appuie pour écouter", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (cat.albums.isNotEmpty()) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cat.albums.take(12), key = { it.id }) { a ->
                            AlbumTile(a.name, a.artistName, appleArtwork(a.artworkTemplate, 300), width = 140.dp) {
                                if (a.inLibrary && a.libraryId != null) ctx.openAlbum(a.libraryId) else ctx.openCatalogAlbum(a.id)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            itemsIndexed(cat.songs, key = { _, s -> "c-" + s.id }) { i, s ->
                val state = when {
                    s.inLibrary -> DownloadState.InLibrary
                    else -> Downloads.states[s.id] ?: DownloadState.None
                }
                TrackRow(s.name, "Titre · ${s.artistName}", appleArtwork(s.artworkTemplate, 150), download = state,
                    onDownload = {
                        Downloads.states[s.id] = DownloadState.Pending
                        ctx.download("song", s.id, s.albumId) { ok -> if (!ok) Downloads.states.remove(s.id) }
                    },
                ) { ctx.playCatalog(cat.songs, i) }
            }
        }
        if (error != null) item { Message("Apple Music : $error") }
        if (query.isBlank()) item { Message("Cherche dans ta bibliothèque et dans tout Apple Music.") }
    }
}

@Composable
fun CatalogAlbumScreen(id: String) {
    val ctx = LocalApp.current
    var album by remember { mutableStateOf<CatalogAlbum?>(null) }
    LaunchedEffect(id) { runCatching { ctx.api.catalogAlbum(id) }.onSuccess { album = it }.onFailure { ctx.toast.value = it.message } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar("Apple Music") }
        val a = album ?: run { item { Message("Chargement…") }; return@LazyColumn }
        val songs = a.tracks.map { it.copy(albumId = it.albumId ?: a.id, albumName = it.albumName.ifEmpty { a.name },
            artworkTemplate = it.artworkTemplate ?: a.artworkTemplate) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(appleArtwork(a.artworkTemplate, 800), Modifier.fillMaxWidth().aspectRatioSquare(), corner = 12.dp)
                Spacer(Modifier.height(14.dp))
                Text(a.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(a.artistName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(a.year, "${songs.size} titres", "Apple Music").joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            PlayButtons(onPlay = { ctx.playCatalog(songs, 0) }, onShuffle = { ctx.playCatalog(songs.shuffled(), 0) })
            val albumState = if (a.inLibrary) DownloadState.InLibrary else Downloads.states[a.id] ?: DownloadState.None
            OutlinedButton(
                onClick = {
                    if (albumState == DownloadState.None) {
                        Downloads.states[a.id] = DownloadState.Pending
                        ctx.download("album", a.id) { ok -> if (!ok) Downloads.states.remove(a.id) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(12.dp),
            ) {
                Text(when (albumState) {
                    DownloadState.None -> "Télécharger l'album dans ma bibliothèque"
                    DownloadState.Pending -> "Téléchargement en cours…"
                    DownloadState.InLibrary -> "Déjà dans ma bibliothèque"
                })
            }
            Spacer(Modifier.height(8.dp))
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            val state = if (s.inLibrary) DownloadState.InLibrary else Downloads.states[s.id] ?: DownloadState.None
            TrackRow(s.name, s.artistName, null, number = s.trackNumber ?: (i + 1), download = state,
                onDownload = {
                    Downloads.states[s.id] = DownloadState.Pending
                    ctx.download("song", s.id, a.id) { ok -> if (!ok) Downloads.states.remove(s.id) }
                }) { ctx.playCatalog(songs, i) }
        }
    }
}
