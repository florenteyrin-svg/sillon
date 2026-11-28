package app.sillon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

/** Client of the Sillon server: Subsonic API for the library, /api for Apple Music and live data. */
class SillonApi(private val settings: Settings) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val base: String get() = settings.serverUrl

    // ---- sign-in ----

    /** Checks the address and password, then keeps the account's API key (the password is not stored). */
    suspend fun signIn(server: String, user: String, password: String): Me = withContext(Dispatchers.IO) {
        val url = server.trimEnd('/')
        val req = Request.Builder().url("$url/api/me").header("Authorization", Credentials.basic(user, password)).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw ApiException("Utilisateur ou mot de passe incorrect")
            if (!resp.isSuccessful) throw ApiException("Le serveur répond ${resp.code}")
            val me = json.decodeFromString<Me>(resp.body!!.string())
            settings.serverUrl = url
            settings.userName = me.name
            settings.apiKey = me.apiKey
            me
        }
    }

    // ---- URLs the player and images load directly ----

    private fun withKey(b: HttpUrl.Builder): String =
        b.addQueryParameter("apiKey", settings.apiKey).addQueryParameter("c", "sillon-android")
            .addQueryParameter("v", "1.16.1").build().toString()

    fun streamUrl(songId: String): String = withKey("$base/rest/stream".toHttpUrl().newBuilder().addQueryParameter("id", songId))

    fun catalogStreamUrl(appleId: String, albumId: String?): String {
        val b = "$base/api/catalog/stream/$appleId".toHttpUrl().newBuilder()
        if (!albumId.isNullOrEmpty()) b.addQueryParameter("albumId", albumId)
        return withKey(b)
    }

    fun coverUrl(id: String, size: Int = 600): String? {
        if (id.isEmpty() || base.isEmpty()) return null
        return withKey("$base/rest/getCoverArt".toHttpUrl().newBuilder().addQueryParameter("id", id).addQueryParameter("size", size.toString()))
    }

    /** The artist's photo (from Apple Music, kept by the server); 404 when there is none. */
    fun artistImageUrl(id: String): String = withKey("$base/api/artist-image/$id".toHttpUrl().newBuilder())

    fun animatedCoverUrl(id: String): String = withKey("$base/api/animated-cover/$id".toHttpUrl().newBuilder())

    // ---- Subsonic ----

    private suspend fun subsonic(method: String, vararg params: Pair<String, Any?>): JsonObject = withContext(Dispatchers.IO) {
        val b = "$base/rest/$method".toHttpUrl().newBuilder().addQueryParameter("f", "json")
        for ((k, v) in params) {
            when (v) {
                null -> {}
                is List<*> -> v.forEach { b.addQueryParameter(k, it.toString()) }
                else -> b.addQueryParameter(k, v.toString())
            }
        }
        val req = Request.Builder().url(withKey(b)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("Le serveur répond ${resp.code}")
            val root = json.parseToJsonElement(resp.body!!.string()).jsonObject["subsonic-response"]?.jsonObject
                ?: throw ApiException("Réponse inattendue du serveur")
            if (root["status"]?.toString()?.trim('"') == "failed") {
                val err = root["error"]?.let { json.decodeFromJsonElement<SubsonicError>(it) }
                throw ApiException(err?.message ?: "Erreur du serveur")
            }
            root
        }
    }

    private inline fun <reified T> JsonObject.at(vararg path: String): T? {
        var e: JsonElement? = this
        for (p in path) e = (e as? JsonObject)?.get(p) ?: return null
        return e?.let { json.decodeFromJsonElement<T>(it) }
    }

    suspend fun albumList(type: String, size: Int = 50, offset: Int = 0): List<Album> =
        subsonic("getAlbumList2", "type" to type, "size" to size, "offset" to offset).at("albumList2", "album") ?: emptyList()

    suspend fun album(id: String): Album = subsonic("getAlbum", "id" to id).at("album") ?: throw ApiException("Album introuvable")

    suspend fun song(id: String): Song = subsonic("getSong", "id" to id).at("song") ?: throw ApiException("Titre introuvable")

    suspend fun artist(id: String): Artist = subsonic("getArtist", "id" to id).at("artist") ?: throw ApiException("Artiste introuvable")

    suspend fun artists(): List<Artist> {
        val index: List<JsonObject> = subsonic("getArtists").at("artists", "index") ?: emptyList()
        return index.flatMap { group -> group["artist"]?.let { json.decodeFromJsonElement<List<Artist>>(it) } ?: emptyList() }
    }

    suspend fun playlists(): List<Playlist> = subsonic("getPlaylists").at("playlists", "playlist") ?: emptyList()

    suspend fun playlist(id: String): Playlist = subsonic("getPlaylist", "id" to id).at("playlist") ?: throw ApiException("Playlist introuvable")

    suspend fun songs(offset: Int, count: Int = 500): List<Song> =
        subsonic("search3", "query" to "", "songCount" to count, "songOffset" to offset, "albumCount" to 0, "artistCount" to 0)
            .at("searchResult3", "song") ?: emptyList()

    data class LibrarySearch(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>)

    suspend fun search(query: String): LibrarySearch {
        val r = subsonic("search3", "query" to query, "songCount" to 25, "albumCount" to 12, "artistCount" to 8)
        return LibrarySearch(r.at("searchResult3", "artist") ?: emptyList(), r.at("searchResult3", "album") ?: emptyList(),
            r.at("searchResult3", "song") ?: emptyList())
    }

    suspend fun randomSongs(size: Int): List<Song> = subsonic("getRandomSongs", "size" to size).at("randomSongs", "song") ?: emptyList()

    suspend fun starred(): List<Song> = subsonic("getStarred2").at("starred2", "song") ?: emptyList()

    suspend fun star(id: String, on: Boolean) { subsonic(if (on) "star" else "unstar", "id" to id) }

    suspend fun scrobble(id: String) { subsonic("scrobble", "id" to id, "submission" to true) }

    suspend fun playQueue(): PlayQueue? = subsonic("getPlayQueue").at("playQueue")

    suspend fun savePlayQueue(ids: List<String>, current: String, positionMs: Long) {
        subsonic("savePlayQueue", "id" to ids.take(500), "current" to current, "position" to positionMs)
    }

    // ---- Sillon API ----

    private suspend fun api(method: String, path: String, body: JsonObject? = null): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$base$path").header("Authorization", "Bearer ${settings.apiKey}")
        if (body != null) b.method(method, body.toString().toRequestBody("application/json".toMediaType()))
        else b.method(method, null)
        http.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.toString()?.trim('"') }.getOrNull()
                throw ApiException(msg ?: "Le serveur répond ${resp.code}")
            }
            text
        }
    }

    suspend fun recent(limit: Int = 30): List<RecentItem> = json.decodeFromString(api("GET", "/api/recent?limit=$limit"))

    suspend fun status(): LibraryStatus = json.decodeFromString(api("GET", "/api/library/status"))

    suspend fun catalogSearch(q: String): CatalogSearch =
        json.decodeFromString(api("GET", "/api/catalog/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&limit=20"))

    suspend fun catalogAlbum(id: String): CatalogAlbum? =
        json.decodeFromString<CatalogAlbumResponse>(api("GET", "/api/catalog/album/$id")).album

    /** Tells the server a catalog song is about to play: it starts fetching it into the cache right away. */
    suspend fun catalogPlay(s: CatalogSong) {
        api("POST", "/api/catalog/play", buildJsonObject {
            put("id", s.id); put("albumId", s.albumId ?: ""); put("title", s.name); put("artist", s.artistName)
            put("album", s.albumName); put("artwork", s.artworkTemplate ?: ""); put("duration", (s.durationMs ?: 0) / 1000.0)
        })
    }

    /** The small arrow: real download into the music library. */
    suspend fun download(type: String, id: String, albumId: String? = null) {
        api("POST", "/api/catalog/download", buildJsonObject {
            put("type", type); put("id", id); if (albumId != null) put("albumId", albumId)
        })
    }

    /** Marks as favourites the songs that are favourites in Jellyfin (the password only goes to Jellyfin, through the server). */
    suspend fun importJellyfin(url: String, user: String, password: String): JellyfinImport = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("url", url); put("user", user); put("password", password) }
        val req = Request.Builder().url("$base/api/import/jellyfin").header("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newBuilder().readTimeout(3, java.util.concurrent.TimeUnit.MINUTES).build().newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.toString()?.trim('"') }.getOrNull()
                throw ApiException(msg ?: "Le serveur répond ${resp.code}")
            }
            json.decodeFromString(text)
        }
    }

    fun serverUrl(path: String): String = withKey("$base$path".toHttpUrl().newBuilder())

    suspend fun jobs(): List<DownloadJob> {
        val root = json.parseToJsonElement(api("GET", "/api/catalog/jobs")).jsonObject
        return root["jobs"]?.let { json.decodeFromJsonElement<List<DownloadJob>>(it) } ?: emptyList()
    }
}
