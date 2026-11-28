package app.sillon.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Library (Subsonic API of the Sillon server) ----

@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    val artistId: String = "",
    val coverArt: String = "",
    val duration: Int = 0,
    val track: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val suffix: String = "",
    val bitRate: Int = 0,
    val bitDepth: Int = 0,
    val samplingRate: Int = 0,
    val quality: String = "",
    val animatedCover: Boolean = false,
    val starred: String? = null,
)

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    val coverArt: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int = 0,
    val quality: String = "",
    val animatedCover: Boolean = false,
    val song: List<Song> = emptyList(),
)

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val albumCount: Int = 0,
    val album: List<Album> = emptyList(),
)

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val smart: Boolean = false,
    val coverArt: String = "",
    val entry: List<Song> = emptyList(),
)

@Serializable
data class PlayQueue(
    val current: String = "",
    val position: Long = 0,
    val entry: List<Song> = emptyList(),
)

// ---- Apple Music catalog, through à la carte ----

@Serializable
data class CatalogSong(
    val id: String,
    val name: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val albumId: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val artworkTemplate: String? = null,
    val inLibrary: Boolean = false,
    val libraryId: String? = null,
)

@Serializable
data class CatalogAlbum(
    val id: String,
    val name: String = "",
    val artistName: String = "",
    val year: String? = null,
    val trackCount: Int? = null,
    val artworkTemplate: String? = null,
    val inLibrary: Boolean = false,
    val libraryId: String? = null,
    val tracks: List<CatalogSong> = emptyList(),
)

@Serializable
data class CatalogArtist(val id: String, val name: String = "")

@Serializable
data class CatalogSearch(
    val songs: List<CatalogSong> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val artists: List<CatalogArtist> = emptyList(),
)

@Serializable
data class CatalogAlbumResponse(val album: CatalogAlbum? = null)

@Serializable
data class DownloadJob(
    val id: String = "",
    val status: String = "",
    val albumTitle: String? = null,
    val albumId: String? = null,
    val songId: String? = null,
    val progress: Double? = null,
    val percent: Double? = null,
)

@Serializable
data class RecentItem(
    val kind: String, // library | catalog
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val artwork: String? = null,
    val quality: String? = null,
    val duration: Double = 0.0,
    val playedAt: Long = 0,
    val cached: Boolean = false,
)

@Serializable
data class MixTransition(
    val status: String = "",
    val aOut: Double = 0.0,
    val bIn: Double = 0.0,
    val length: Double = 0.0,
    val style: String = "",
    val url: String? = null,
    val error: String? = null,
)

@Serializable
data class Me(val name: String, val admin: Boolean = false, val apiKey: String)

@Serializable
data class LibraryStatus(
    val tracks: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val hires: Int = 0,
    val cd: Int = 0,
    val lossy: Int = 0,
    val scanning: Boolean = false,
)

/** Apple artwork templates look like ".../{w}x{h}bb.jpg". */
fun appleArtwork(template: String?, size: Int = 600): String? =
    template?.replace("{w}", size.toString())?.replace("{h}", size.toString())?.replace("{f}", "jpg")

/** "Mes Playlists" etc.: smaller helpers used across screens. */
fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Serializable
internal data class SubsonicError(val code: Int = 0, val message: String = "")

@Serializable
internal data class SubsonicEnvelope(@SerialName("subsonic-response") val response: kotlinx.serialization.json.JsonObject)

@Serializable
data class JellyfinImport(val total: Int = 0, val imported: Int = 0, val missing: List<String> = emptyList())

/** The "Favoris" playlist the server lists with the others: the songs marked with the heart. */
const val FAVORITES_ID = "pl-favoris"
