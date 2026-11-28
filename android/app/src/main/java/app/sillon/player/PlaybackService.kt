package app.sillon.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.sillon.R
import app.sillon.SillonApp
import app.sillon.data.SillonApi
import app.sillon.data.appleArtwork
import app.sillon.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Plays in the background, keeps the queue on the phone (restored before anything else, so pressing play in
 * the car starts at once), and serves the Android Auto browsing tree.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var api: SillonApi
    private lateinit var store: QueueStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /** Tracks seen while browsing (Android Auto sends back only ids): id -> track, and the list it came from. */
    private val known = HashMap<String, QueueTrack>()
    private val lists = HashMap<String, List<QueueTrack>>()

    override fun onCreate() {
        super.onCreate()
        val app = application as SillonApp
        api = app.api
        store = QueueStore(this)

        val renderers = DefaultRenderersFactory(this)
            // FFmpeg decoders for ALAC, APE, WavPack... when the phone has none
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api.http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        restoreQueue()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveQueue()
                prefetchNextCatalog()
                scheduleScrobble(mediaItem)
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = saveQueue()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveQueue()
                if (!isPlaying) saveQueueToServer()
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveQueue()
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = recoverFromError()
            override fun onRepeatModeChanged(repeatMode: Int) = saveQueue()
        })
        handler.post(periodicSave)

        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(listOf(
                androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_ALBUM)
                    .setDisplayName("Album à la suite").setSessionCommand(CMD_ALBUM).build(),
                androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_ARTIST)
                    .setDisplayName("Artiste à la suite").setSessionCommand(CMD_ARTIST).build(),
            ))
            .build()
        Widget.attach(this, player, api, scope)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_notification)
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveQueue()
        handler.removeCallbacksAndMessages(null)
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A song played from Apple Music arrives while it downloads; if the stream breaks (network, file
     * finished being written...), ask for it again and carry on from the same place, up to 3 times per song.
     */
    private var retries = 0
    private var retriedId: String? = null

    private fun recoverFromError() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id != retriedId) { retriedId = id; retries = 0 }
        if (retries >= 3) return
        retries++
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        handler.postDelayed({
            if (player.currentMediaItem?.mediaId != id) return@postDelayed
            player.seekTo(index, position)
            player.prepare()
            player.play()
        }, 1500L * retries)
    }

    // ---- queue kept on the phone ----

    private fun restoreQueue() {
        val saved = store.load() ?: return
        if (saved.tracks.isEmpty()) return
        val items = saved.tracks.map { it.toMediaItem(api) }
        player.setMediaItems(items, saved.index.coerceIn(0, items.size - 1), saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        player.prepare() // ready to play the moment play is pressed
    }

    private fun saveQueue() {
        if (!::player.isInitialized) return
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val cur = player.currentMediaItemIndex
        val index = items.take(cur.coerceAtLeast(0)).count { !it.isTransition }
        val onTransition = items.getOrNull(cur)?.isTransition == true
        val tracks = items.filter { !it.isTransition }.map { it.toQueueTrack() }
        store.save(SavedQueue(tracks, index, if (onTransition) 0 else player.currentPosition, player.shuffleModeEnabled, player.repeatMode))
    }

    private val periodicSave = object : Runnable {
        override fun run() {
            if (player.isPlaying) saveQueue()
            handler.postDelayed(this, 10_000)
        }
    }

    /** The server keeps it too, to resume on another device. */
    private fun saveQueueToServer() {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            .filter { !it.startsWith(CATALOG_PREFIX) && !it.startsWith(MIX_PREFIX) }
        val current = player.currentMediaItem?.mediaId ?: return
        if (current.startsWith(CATALOG_PREFIX) || current.startsWith(MIX_PREFIX) || ids.isEmpty()) return
        val pos = player.currentPosition
        scope.launch(Dispatchers.IO) { runCatching { api.savePlayQueue(ids, current, pos) } }
    }

    /**
     * The next song comes from Apple Music: ask the server to fetch it now, so it starts at once when its
     * turn comes (opening its stream address starts the download on the server).
     */
    private fun prefetchNextCatalog() {
        val next = player.nextMediaItemIndex.takeIf { it >= 0 } ?: return
        val item = player.getMediaItemAt(next)
        if (!item.mediaId.startsWith(CATALOG_PREFIX)) return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                api.http.newCall(okhttp3.Request.Builder().url(uri).header("Range", "bytes=0-0").build()).execute().close()
            }
        }
    }

    /** A library song counts as played once it has played 30 seconds. */
    private fun scheduleScrobble(item: MediaItem?) {
        val id = item?.mediaId ?: return
        if (id.startsWith(CATALOG_PREFIX) || id.startsWith(MIX_PREFIX)) return
        handler.postDelayed({
            if (player.currentMediaItem?.mediaId == id && player.currentPosition >= 25_000) {
                scope.launch(Dispatchers.IO) { runCatching { api.scrobble(id) } }
            }
        }, 30_000)
    }

    // ---- Android Auto and other controllers ----

    private fun browsable(id: String, title: String, subtitle: String? = null, art: String? = null,
                          mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, group: String? = null) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle)
                .setArtworkUri(art?.let { android.net.Uri.parse(it) })
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType)
                // Android Auto shows items with the same group under one heading
                .setExtras(group?.let { Bundle().apply { putString(androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } })
                .build()
        ).build()

    private fun playable(parent: String, tracks: List<QueueTrack>): List<MediaItem> {
        lists[parent] = tracks
        tracks.forEach { known[it.id] = it }
        return tracks.map { it.toMediaItem(api) }
    }

    private suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            browsable(RECENT, "Récents", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browsable(PLAYLISTS, "Playlists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            browsable(ALBUMS, "Albums", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browsable(ARTISTS, "Artistes", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        )
        parentId == RECENT -> playable(RECENT, api.recent(40).map {
            if (it.kind == "catalog") QueueTrack(CATALOG_PREFIX + it.id, it.title, it.artist, it.album, it.albumId ?: "",
                appleArtwork(it.artwork), it.duration.toInt())
            else QueueTrack(it.id, it.title, it.artist, it.album, it.albumId ?: "", null, it.duration.toInt(), it.quality ?: "")
        })
        parentId == PLAYLISTS -> api.playlists().map {
            browsable("pl:${it.id}", it.name, "${it.songCount} titres", api.coverUrl(it.id, 300), MediaMetadata.MEDIA_TYPE_PLAYLIST)
        }
        // Favourite albums first (no searching while driving), then the newest ones
        parentId == ALBUMS -> {
            val favs = runCatching { api.starredAlbums() }.getOrDefault(emptyList())
            val ids = favs.map { it.id }.toSet()
            favs.map {
                browsable("al:${it.id}", it.name, listOfNotNull(it.artist, qualityLabel(it.quality)).joinToString(" · "),
                    api.coverUrl(it.id, 300), MediaMetadata.MEDIA_TYPE_ALBUM, group = "Albums préférés")
            } + api.albumList("newest", 100).filter { it.id !in ids }.map {
                browsable("al:${it.id}", it.name, listOfNotNull(it.artist, qualityLabel(it.quality)).joinToString(" · "),
                    api.coverUrl(it.id, 300), MediaMetadata.MEDIA_TYPE_ALBUM, group = if (favs.isEmpty()) null else "Ajoutés récemment")
            }
        }
        parentId == ARTISTS -> api.artists().map {
            browsable("ar:${it.id}", it.name, null, api.coverUrl(it.id, 300), MediaMetadata.MEDIA_TYPE_ARTIST)
        }
        parentId.startsWith("pl:") -> playable(parentId, api.playlist(parentId.removePrefix("pl:")).entry.map { it.toQueueTrack() })
        parentId.startsWith("al:") -> playable(parentId, api.album(parentId.removePrefix("al:")).song.map { it.toQueueTrack() })
        parentId.startsWith("ar:") -> api.artist(parentId.removePrefix("ar:")).album.map {
            browsable("al:${it.id}", it.name, it.year.takeIf { y -> y > 0 }?.toString(), api.coverUrl(it.id, 300), MediaMetadata.MEDIA_TYPE_ALBUM)
        }
        else -> emptyList()
    }

    /** Rebuilds playable items (the stream address is not passed between app parts). */
    private suspend fun resolve(item: MediaItem): MediaItem {
        if (item.mediaMetadata.title != null) return item.toQueueTrack().toMediaItem(api)
        known[item.mediaId]?.let { return it.toMediaItem(api) }
        if (item.mediaId.startsWith(CATALOG_PREFIX)) return item
        return api.song(item.mediaId).toQueueTrack().also { known[it.id] = it }.toMediaItem(api)
    }

    /**
     * Album button: the playing song's album, in order, right after it. Artist button: all the artist's songs,
     * shuffled, right after it. Library songs only (not Apple Music ones).
     */
    private suspend fun queueAfterCurrent(kind: String): Boolean {
        val cur = player.currentMediaItem ?: return false
        if (cur.mediaId.startsWith(CATALOG_PREFIX)) return false
        val e = cur.mediaMetadata.extras
        val mixable = e?.getBoolean(Extras.MIXABLE) ?: false
        val songs = kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (kind == "album") {
                val albumId = e?.getString(Extras.ALBUM_ID).orEmpty()
                if (albumId.isEmpty()) emptyList() else api.album(albumId).song
            } else {
                val artistId = e?.getString(Extras.ARTIST_ID).orEmpty()
                val name = cur.mediaMetadata.artist?.toString().orEmpty()
                val byId = runCatching { api.artist(artistId).album.flatMap { api.album(it.id).song } }.getOrDefault(emptyList())
                (byId.ifEmpty { api.search(name).songs.filter { it.artist.equals(name, true) } }).shuffled()
            }
        }.filter { it.id != cur.mediaId }
        if (songs.isEmpty()) return false
        player.addMediaItems(player.currentMediaItemIndex + 1, songs.map { it.toQueueTrack(mixable).toMediaItem(api) })
        return true
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(CMD_ALBUM).add(CMD_ARTIST).build()
                ).build()

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                     customCommand: androidx.media3.session.SessionCommand, args: Bundle):
            ListenableFuture<androidx.media3.session.SessionResult> = scope.future {
            val kind = if (customCommand.customAction == CMD_ALBUM.customAction) "album" else "artist"
            val ok = runCatching { queueAfterCurrent(kind) }.getOrDefault(false)
            androidx.media3.session.SessionResult(
                if (ok) androidx.media3.session.SessionResult.RESULT_SUCCESS else androidx.media3.session.SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?):
            ListenableFuture<LibraryResult<MediaItem>> =
            com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(browsable(ROOT, "Sillon"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                   page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future {
                runCatching { children(parentId) }.fold(
                    { all ->
                        val from = (page * pageSize).coerceAtMost(all.size)
                        val to = (from + pageSize).coerceAtMost(all.size)
                        LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
                    },
                    { LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO) },
                )
            }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String):
            ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            runCatching { LibraryResult.ofItem(resolve(MediaItem.Builder().setMediaId(mediaId).build()), null) }
                .getOrElse { LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE) }
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?):
            ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = runCatching { api.search(query).songs.size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                       page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future {
                val items = runCatching { playable("search:$query", api.search(query).songs.map { it.toQueueTrack() }) }.getOrDefault(emptyList())
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>):
            ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.map { runCatching { resolve(it) }.getOrDefault(it) }.toMutableList()
        }

        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
                                     startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            // Voice: "play <something> on Sillon"
            val query = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
            if (query != null) {
                val found = runCatching { api.search(query).songs.map { it.toQueueTrack() } }.getOrDefault(emptyList())
                return@future MediaSession.MediaItemsWithStartPosition(found.map { it.toMediaItem(api) }, 0, C.TIME_UNSET)
            }
            // One song picked in the car from an album or playlist: play that list from the song
            val single = mediaItems.singleOrNull()
            if (single != null && single.mediaMetadata.title == null) {
                val list = lists.values.firstOrNull { l -> l.any { it.id == single.mediaId } }
                if (list != null) {
                    val idx = list.indexOfFirst { it.id == single.mediaId }
                    return@future MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem(api) }, idx, C.TIME_UNSET)
                }
            }
            val resolved = mediaItems.map { runCatching { resolve(it) }.getOrDefault(it) }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo):
            ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = store.load() ?: SavedQueue()
            MediaSession.MediaItemsWithStartPosition(saved.tracks.map { it.toMediaItem(api) },
                saved.index.coerceAtLeast(0), saved.positionMs)
        }
    }

    companion object {
        val CMD_ALBUM = androidx.media3.session.SessionCommand("sillon.album", Bundle.EMPTY)
        val CMD_ARTIST = androidx.media3.session.SessionCommand("sillon.artist", Bundle.EMPTY)
        const val ROOT = "root"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
    }
}
