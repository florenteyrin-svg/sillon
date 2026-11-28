package app.sillon.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.sillon.data.CatalogSong
import app.sillon.data.SillonApi
import app.sillon.data.Song
import app.sillon.data.appleArtwork
import kotlinx.serialization.Serializable

const val CATALOG_PREFIX = "apple:"

/** What the player needs to know about a track, also what is saved to restore the queue instantly. */
@Serializable
data class QueueTrack(
    val id: String, // Sillon track id, or "apple:<id>" for a catalog song
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String = "", // Sillon album id, or Apple album id for a catalog song
    val artwork: String? = null, // catalog songs: Apple artwork URL
    val duration: Int = 0,
    val quality: String = "",
    val animatedCover: Boolean = false,
    val mixable: Boolean = false, // played from a playlist or all songs: the DJ mix may join it to the next
    val artistId: String = "",
) {
    val isCatalog: Boolean get() = id.startsWith(CATALOG_PREFIX)
    val appleId: String get() = id.removePrefix(CATALOG_PREFIX)
}

fun Song.toQueueTrack(mixable: Boolean = false) =
    QueueTrack(id, title, artist, album, albumId, null, duration, quality, animatedCover, mixable, artistId)

fun CatalogSong.toQueueTrack() = QueueTrack(
    CATALOG_PREFIX + id, name, artistName, albumName, albumId ?: "", appleArtwork(artworkTemplate),
    ((durationMs ?: 0) / 1000).toInt(), "",
)

object Extras {
    const val QUALITY = "sillon.quality"
    const val ANIMATED = "sillon.animated"
    const val ALBUM_ID = "sillon.albumId"
    const val CATALOG = "sillon.catalog"
    const val MIXABLE = "sillon.mixable"
    const val TRANSITION = "sillon.transition"
    const val ARTIST_ID = "sillon.artistId"
    const val DJ_PREV = "sillon.djPrev" // DJ segment: song whose transition opens this one
    const val DJ_NEXT = "sillon.djNext" // DJ segment: song this one ends into
}

fun QueueTrack.toMediaItem(api: SillonApi): MediaItem {
    val extras = Bundle().apply {
        putString(Extras.QUALITY, quality)
        putBoolean(Extras.ANIMATED, animatedCover)
        putString(Extras.ALBUM_ID, albumId)
        putBoolean(Extras.CATALOG, isCatalog)
        putBoolean(Extras.MIXABLE, mixable)
        putString(Extras.ARTIST_ID, artistId)
    }
    val art = artwork ?: api.coverUrl(albumId.ifEmpty { id }, 600)
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(art?.let { Uri.parse(it) })
        .setDurationMs(if (duration > 0) duration * 1000L else null)
        // Android Auto shows the subtitle: "Artist · Hi-Res"
        .setSubtitle(listOfNotNull(artist, qualityLabel(quality)).joinToString(" · "))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setExtras(extras)
        .build()
    val uri = if (isCatalog) api.catalogStreamUrl(appleId, albumId) else api.streamUrl(id)
    return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(meta).build()
}

fun MediaItem.toQueueTrack(): QueueTrack {
    val m = mediaMetadata
    val e = m.extras
    return QueueTrack(
        id = mediaId,
        title = m.title?.toString() ?: "",
        artist = m.artist?.toString() ?: "",
        album = m.albumTitle?.toString() ?: "",
        albumId = e?.getString(Extras.ALBUM_ID) ?: "",
        artwork = if (mediaId.startsWith(CATALOG_PREFIX)) m.artworkUri?.toString() else null,
        duration = ((m.durationMs ?: 0L) / 1000).toInt(),
        quality = e?.getString(Extras.QUALITY) ?: "",
        animatedCover = e?.getBoolean(Extras.ANIMATED) ?: false,
        mixable = e?.getBoolean(Extras.MIXABLE) ?: false,
        artistId = e?.getString(Extras.ARTIST_ID) ?: "",
    )
}

const val MIX_PREFIX = "mix:"

val MediaItem.isTransition: Boolean get() = mediaId.startsWith(MIX_PREFIX)

fun qualityLabel(q: String?): String? = when (q) {
    "hires" -> "Hi-Res"
    "cd" -> "CD"
    "atmos" -> "Dolby Atmos"
    "lossy" -> "MP3"
    else -> null
}
