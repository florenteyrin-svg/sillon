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


    /** Music Assistant (send the music to the house's players). */
    var maUrl: String
        get() = prefs.getString("maUrl", "http://10.0.0.2:8095") ?: ""
        set(v) = prefs.edit().putString("maUrl", v.trim()).apply()

    var maToken: String
        get() = prefs.getString("maToken", "") ?: ""
        set(v) = prefs.edit().putString("maToken", v.trim()).apply()

    val isSignedIn: Boolean get() = serverUrl.isNotEmpty() && apiKey.isNotEmpty()

    fun signOut() = prefs.edit().remove("apiKey").apply()
}
