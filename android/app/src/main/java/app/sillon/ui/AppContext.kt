package app.sillon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import app.sillon.SillonApp
import app.sillon.data.CatalogSong
import app.sillon.data.RecentItem
import app.sillon.data.appleArtwork
import app.sillon.player.CATALOG_PREFIX
import app.sillon.player.QueueTrack
import app.sillon.player.toQueueTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer

class AppContext(
    val app: SillonApp,
    val player: PlayerConnection,
    val nav: NavHostController,
    val scope: CoroutineScope,
    val showPlayer: MutableState<Boolean>,
    val toast: MutableState<String?>,
) {
    val api get() = app.api

    fun openAlbum(id: String) = nav.navigate("album/$id")
    fun openArtist(id: String) = nav.navigate("artist/$id")
    fun openPlaylist(id: String) = nav.navigate("playlist/$id")
    fun openCatalogAlbum(id: String) = nav.navigate("catalog-album/$id")

    /** Plays Apple Music songs right away: the server starts fetching the first one while the player opens. */
    fun playCatalog(songs: List<CatalogSong>, start: Int) {
        val first = songs.getOrNull(start) ?: return
        val tracks = songs.map { s -> if (s.inLibrary && s.libraryId != null) s.toQueueTrack().copy(id = s.libraryId) else s.toQueueTrack() }
        scope.launch {
            if (!(first.inLibrary && first.libraryId != null)) {
                runCatching { api.catalogPlay(first) }.onFailure { toast.value = "Apple Music : ${it.message}" }
            }
        }
        player.play(tracks, start)
        showPlayer.value = true
    }

    fun play(tracks: List<QueueTrack>, start: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        player.play(tracks, start, shuffle)
    }

    fun playRecent(items: List<RecentItem>, index: Int) {
        val tracks = items.map {
            if (it.kind == "catalog") QueueTrack(CATALOG_PREFIX + it.id, it.title, it.artist, it.album, it.albumId ?: "",
                appleArtwork(it.artwork), it.duration.toInt())
            else QueueTrack(it.id, it.title, it.artist, it.album, it.albumId ?: "", null, it.duration.toInt(), it.quality ?: "")
        }
        player.play(tracks, index)
    }

    fun download(type: String, id: String, albumId: String? = null, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            runCatching { api.download(type, id, albumId) }
                .onSuccess { toast.value = "Téléchargement lancé : il arrivera dans ta bibliothèque"; onDone(true) }
                .onFailure { toast.value = "Téléchargement impossible : ${it.message}"; onDone(false) }
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppContext> { error("no app context") }

/** Shows the copy kept on the phone at once, then the fresh answer from the server. */
@Composable
fun <T> cached(key: String, serializer: KSerializer<T>, load: suspend () -> T): MutableState<T?> {
    val ctx = LocalApp.current
    val state = remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        ctx.app.cache.read(key, serializer)?.let { if (state.value == null) state.value = it }
        runCatching { load() }.onSuccess {
            state.value = it
            ctx.app.cache.write(key, serializer, it)
        }.onFailure { if (state.value == null) ctx.toast.value = "Serveur injoignable : ${it.message}" }
    }
    return state
}
