package app.sillon.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class MaPlayer(val id: String, val name: String, val available: Boolean)
data class MaNowPlaying(val songId: String?, val title: String, val positionSec: Double, val playing: Boolean)

/**
 * Sends the music to Music Assistant players (Apple TV, speakers... and so Home Assistant). Music Assistant
 * reads the songs from Sillon through its Open Subsonic provider, with the same song ids.
 */
class MusicAssistant(private val settings: Settings, private val http: OkHttpClient) {

    private class Connection(http: OkHttpClient, url: String) : WebSocketListener() {
        val ready = CompletableDeferred<Unit>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
        private val ids = AtomicInteger(1)
        val socket: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), this)

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val id = msg["message_id"]?.jsonPrimitive?.content
            if (id == null) { ready.complete(Unit); return } // first message: server info
            val p = pending.remove(id) ?: return
            if (msg.containsKey("error_code")) {
                p.completeExceptionally(ApiException((msg["details"] ?: msg["message"] ?: msg["error_code"]).toString().trim('"')))
            } else p.complete(msg["result"])
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ready.completeExceptionally(t)
            pending.values.forEach { it.completeExceptionally(t) }
        }

        suspend fun call(command: String, args: JsonObject? = null): JsonElement? {
            val id = ids.getAndIncrement().toString()
            val d = CompletableDeferred<JsonElement?>()
            pending[id] = d
            socket.send(buildJsonObject {
                put("message_id", id); put("command", command); if (args != null) put("args", args)
            }.toString())
            return withTimeout(20_000) { d.await() }
        }
    }

    val configured: Boolean get() = settings.maUrl.isNotBlank()

    private suspend fun <T> session(block: suspend (Connection) -> T): T {
        val base = settings.maUrl.trimEnd('/')
        val ws = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        val c = Connection(http, ws)
        try {
            withTimeout(10_000) { c.ready.await() }
            if (settings.maToken.isNotBlank()) c.call("auth", buildJsonObject { put("token", settings.maToken) })
            return block(c)
        } finally {
            c.socket.close(1000, null)
        }
    }

    suspend fun players(): List<MaPlayer> = session { c ->
        (c.call("players/all") as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (p[k] as? JsonPrimitive)?.content
            if (s("enabled") == "false" || s("hidden") == "true" || s("hide_in_ui") == "true") return@mapNotNull null
            MaPlayer(s("player_id") ?: return@mapNotNull null, s("display_name") ?: s("name") ?: "?", s("available") != "false")
        }.sortedBy { it.name.lowercase() }
    }

    /** Replaces the player's queue with these Sillon songs (in order) and starts at [positionSec] of the first. */
    suspend fun play(playerId: String, songIds: List<String>, positionSec: Long) = session { c ->
        c.call("player_queues/play_media", buildJsonObject {
            put("queue_id", playerId)
            put("media", JsonArray(songIds.take(300).map { JsonPrimitive("opensubsonic://track/$it") }))
            put("option", "replace")
        })
        if (positionSec > 3) {
            kotlinx.coroutines.delay(2500) // a start position is refused for a list: seek once it plays
            runCatching { c.call("players/cmd/seek", buildJsonObject { put("player_id", playerId); put("position", positionSec) }) }
        }
    }

    suspend fun command(playerId: String, name: String) = session { c ->
        c.call("players/cmd/$name", buildJsonObject { put("player_id", playerId) })
    }

    suspend fun nowPlaying(playerId: String): MaNowPlaying? = session { c ->
        val q = c.call("player_queues/get", buildJsonObject { put("queue_id", playerId) }) as? JsonObject ?: return@session null
        val item = q["current_item"] as? JsonObject
        val media = item?.get("media_item") as? JsonObject
        val uri = (media?.get("uri") as? JsonPrimitive)?.content.orEmpty()
        val id = uri.substringAfterLast("/").takeIf { uri.contains("://track/") }
        MaNowPlaying(
            songId = id,
            title = (item?.get("name") as? JsonPrimitive)?.content.orEmpty(),
            positionSec = (q["elapsed_time"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
            playing = (q["state"] as? JsonPrimitive)?.content == "playing",
        )
    }
}
