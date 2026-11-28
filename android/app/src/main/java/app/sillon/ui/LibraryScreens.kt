package app.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sillon.data.Album
import app.sillon.data.Artist
import app.sillon.data.LibraryStatus
import app.sillon.data.Playlist
import app.sillon.data.Song
import app.sillon.data.formatDuration
import app.sillon.player.toQueueTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import androidx.compose.runtime.collectAsState

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun BackBar(title: String = "") {
    val ctx = LocalApp.current
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { ctx.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = onPlay, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.padding(3.dp)); Text("Lecture", fontWeight = FontWeight.SemiBold)
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.padding(3.dp)); Text("Aléatoire", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun LibraryScreen() {
    val ctx = LocalApp.current
    val status by cached("library-status", LibraryStatus.serializer()) { ctx.api.status() }
    val newest by cached("home-newest", ListSerializer(Album.serializer())) { ctx.api.albumList("newest", 20) }
    var showImport by remember { mutableStateOf(false) }
    if (showImport) JellyfinImportDialog { showImport = false }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val s = status
            ScreenTitle("Bibliothèque", s?.let { "${it.tracks} titres · ${it.hires} Hi-Res · ${it.cd} CD" })
            NavRow("Playlists") { ctx.nav.navigate("playlists") }
            NavRow("Artistes") { ctx.nav.navigate("artists") }
            NavRow("Albums") { ctx.nav.navigate("albums") }
            NavRow("Titres") { ctx.nav.navigate("songs") }
            NavRow("Favoris") { ctx.openPlaylist(app.sillon.data.FAVORITES_ID) }
            NavRow("Importer mes favoris Jellyfin") { showImport = true }
            Spacer(Modifier.height(16.dp))
            SectionTitle("Ajoutés récemment")
        }
        items(newest.orEmpty().chunked(2)) { pair ->
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { a -> Box(Modifier.weight(1f)) { AlbumTile(a.name, a.artist, ctx.api.coverUrl(a.id, 400), a.quality) { ctx.openAlbum(a.id) } } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun AlbumsScreen() {
    val ctx = LocalApp.current
    val albums by cached("albums-all", ListSerializer(Album.serializer())) {
        val all = mutableListOf<Album>()
        var offset = 0
        while (true) {
            val page = ctx.api.albumList("alphabeticalByName", 500, offset)
            all += page
            if (page.size < 500) break
            offset += 500
        }
        all
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(); ScreenTitle("Albums", albums?.let { "${it.size} albums" }) }
        items(albums.orEmpty().chunked(2), key = { it.first().id }) { pair ->
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { a -> Box(Modifier.weight(1f)) { AlbumTile(a.name, a.artist, ctx.api.coverUrl(a.id, 400), a.quality) { ctx.openAlbum(a.id) } } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ArtistsScreen() {
    val ctx = LocalApp.current
    val artists by cached("artists-all", ListSerializer(Artist.serializer())) { ctx.api.artists() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(); ScreenTitle("Artistes") }
        items(artists.orEmpty(), key = { it.id }) { a ->
            TrackRow(a.name, if (a.albumCount > 1) "${a.albumCount} albums" else "1 album", ctx.api.coverUrl(a.id, 150)) { ctx.openArtist(a.id) }
        }
    }
}

@Composable
fun PlaylistsScreen() {
    val ctx = LocalApp.current
    val playlists by cached("home-playlists", ListSerializer(Playlist.serializer())) { ctx.api.playlists() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(); ScreenTitle("Playlists") }
        items(playlists.orEmpty(), key = { it.id }) { p ->
            TrackRow(p.name, (if (p.smart) "Intelligente · " else "") + "${p.songCount} titres", ctx.api.coverUrl(p.id, 150)) { ctx.openPlaylist(p.id) }
        }
    }
}

/** All songs, loaded page by page as you scroll (22 000+ titles). */
@Composable
fun SongsScreen() {
    val ctx = LocalApp.current
    val songs = remember { mutableStateListOf<Song>() }
    var loading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > songs.size - 40 } }
    LaunchedEffect(nearEnd, songs.size) {
        if (nearEnd && !loading && !done) {
            loading = true
            runCatching { ctx.api.songs(songs.size, 500) }.onSuccess { page -> songs += page; if (page.size < 500) done = true }
                .onFailure { ctx.toast.value = it.message }
            loading = false
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            BackBar(); ScreenTitle("Titres")
            PlayButtons(
                onPlay = { ctx.play(songs.map { it.toQueueTrack() }, mixable = true) },
                onShuffle = { ctx.scope.launchShuffleAll(ctx) },
            )
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            TrackRow(s.title, "${s.artist} · ${s.album}", ctx.api.coverUrl(s.albumId, 150), s.quality) {
                ctx.play(songs.map { it.toQueueTrack() }, i, mixable = true)
            }
        }
    }
}

/** Shuffle the whole library: a random selection from the server, so it starts at once. */
private fun CoroutineScope.launchShuffleAll(ctx: AppContext) {
    launch {
        runCatching { ctx.api.randomSongs(300) }.onSuccess { ctx.play(it.map { s -> s.toQueueTrack() }, mixable = true) }
            .onFailure { ctx.toast.value = it.message }
    }
}

@Composable
fun StarredScreen() {
    val ctx = LocalApp.current
    val songs by cached("starred", ListSerializer(Song.serializer())) { ctx.api.starred() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(); ScreenTitle("Favoris") }
        val list = songs.orEmpty()
        if (list.isEmpty()) item { Message("Pas encore de favoris") }
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            TrackRow(s.title, s.artist, ctx.api.coverUrl(s.albumId, 150), s.quality) { ctx.play(list.map { it.toQueueTrack() }, i, mixable = true) }
        }
    }
}

@Composable
fun AlbumScreen(id: String) {
    val ctx = LocalApp.current
    val album by cached("album-$id", Album.serializer()) { ctx.api.album(id) }
    val player by ctx.player.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar() }
        val a = album ?: run { item { Message("Chargement…") }; return@LazyColumn }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(ctx.api.coverUrl(a.id, 800), Modifier.fillMaxWidth().aspectRatioSquare(), corner = 12.dp)
                Spacer(Modifier.height(14.dp))
                Text(a.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(a.artist, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 2.dp).clickableNoRipple { ctx.openArtist(a.artistId) })
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    QualityBadge(a.quality)
                    Text(listOfNotNull(a.year.takeIf { it > 0 }?.toString(), "${a.songCount} titres", formatDuration(a.duration)).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // An album plays in order, never mixed (DJ mix is for playlists and all songs)
            PlayButtons(onPlay = { ctx.play(a.song.map { it.toQueueTrack() }) }, onShuffle = { ctx.play(a.song.map { it.toQueueTrack() }, shuffle = true) })
        }
        itemsIndexed(a.song, key = { _, s -> s.id }) { i, s ->
            TrackRow(s.title, if (s.artist != a.artist) s.artist else formatDuration(s.duration), null, s.quality.takeIf { it != a.quality },
                number = s.track, playing = player.current?.id == s.id) { ctx.play(a.song.map { it.toQueueTrack() }, i) }
        }
    }
}

@Composable
fun ArtistScreen(id: String) {
    val ctx = LocalApp.current
    val artist by cached("artist-$id", Artist.serializer()) { ctx.api.artist(id) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar() }
        val a = artist ?: run { item { Message("Chargement…") }; return@LazyColumn }
        item {
            ScreenTitle(a.name, "${a.album.size} albums")
            PlayButtons(
                onPlay = { ctx.scope.playAlbums(ctx, a.album, false) },
                onShuffle = { ctx.scope.playAlbums(ctx, a.album, true) },
            )
        }
        items(a.album.chunked(2)) { pair ->
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { al ->
                    Box(Modifier.weight(1f)) {
                        AlbumTile(al.name, al.year.takeIf { it > 0 }?.toString(), ctx.api.coverUrl(al.id, 400), al.quality) { ctx.openAlbum(al.id) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun CoroutineScope.playAlbums(ctx: AppContext, albums: List<Album>, shuffle: Boolean) {
    launch {
        runCatching { albums.flatMap { ctx.api.album(it.id).song } }
            .onSuccess { ctx.play(it.map { s -> s.toQueueTrack() }, shuffle = shuffle) }
            .onFailure { ctx.toast.value = it.message }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val ctx = LocalApp.current
    val playlist by cached("playlist-$id", Playlist.serializer()) { ctx.api.playlist(id) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar() }
        val p = playlist ?: run { item { Message("Chargement…") }; return@LazyColumn }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(ctx.api.coverUrl(p.id, 800), Modifier.fillMaxWidth().aspectRatioSquare(), corner = 12.dp)
                Spacer(Modifier.height(14.dp))
                Text(p.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text((if (p.smart) "Playlist intelligente · " else "") + "${p.entry.size} titres · ${formatDuration(p.duration)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlayButtons(onPlay = { ctx.play(p.entry.map { it.toQueueTrack() }, mixable = true) },
                onShuffle = { ctx.play(p.entry.map { it.toQueueTrack() }, shuffle = true, mixable = true) })
        }
        itemsIndexed(p.entry, key = { i, s -> "$i-${s.id}" }) { i, s ->
            TrackRow(s.title, s.artist, ctx.api.coverUrl(s.albumId, 150), s.quality) { ctx.play(p.entry.map { it.toQueueTrack() }, i, mixable = true) }
        }
    }
}

/** Brings the Jellyfin favourites into the "Favoris" playlist. The password goes to Jellyfin only, nothing is kept. */
@Composable
private fun JellyfinImportDialog(onClose: () -> Unit) {
    val ctx = LocalApp.current
    var url by remember { mutableStateOf("http://10.0.0.2:8096") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Favoris Jellyfin") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result != null) Text(result!!)
                else {
                    Text("Les titres en favori dans Jellyfin sont ajoutés à ta playlist Favoris.",
                        style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.OutlinedTextField(url, { url = it.trim() }, label = { Text("Adresse de Jellyfin") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(user, { user = it.trim() }, label = { Text("Utilisateur Jellyfin") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(password, { password = it }, label = { Text("Mot de passe Jellyfin") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    if (busy) Text("Import en cours…", color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            if (result != null) androidx.compose.material3.TextButton(onClick = onClose) { Text("OK") }
            else androidx.compose.material3.TextButton(enabled = !busy && url.length > 8 && user.isNotEmpty(), onClick = {
                busy = true
                ctx.scope.launch {
                    runCatching { ctx.api.importJellyfin(url, user, password) }
                        .onSuccess { r ->
                            ctx.loadFavorites()
                            result = "${r.imported} favoris sur ${r.total} ajoutés à ta playlist Favoris." +
                                (if (r.missing.isNotEmpty()) "\n\nPas trouvés dans ta bibliothèque :\n" + r.missing.joinToString("\n") else "")
                        }
                        .onFailure { ctx.toast.value = it.message }
                    busy = false
                }
            }) { Text("Importer") }
        },
        dismissButton = { if (result == null) androidx.compose.material3.TextButton(enabled = !busy, onClick = onClose) { Text("Annuler") } },
    )
}
