package app.sillon.data

import android.content.Context
import android.content.SharedPreferences

/** Server address and API key, read synchronously so the player service can start without waiting. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sillon", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v.trimEnd('/')).apply()

    var userName: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) = prefs.edit().putString("apiKey", v).apply()

    /** DJ mix button (playlists and all songs, never albums). */
    var djEnabled: Boolean
        get() = prefs.getBoolean("dj", false)
        set(v) = prefs.edit().putBoolean("dj", v).apply()

    val isSignedIn: Boolean get() = serverUrl.isNotEmpty() && apiKey.isNotEmpty()

    fun signOut() = prefs.edit().remove("apiKey").apply()
}
