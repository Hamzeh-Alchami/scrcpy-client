package com.hamzeh.scrcpyclient

import android.content.Context
import android.net.Uri

/** Small wrapper around SharedPreferences for the user's settings and recent URLs. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var hideToolbar: Boolean
        get() = sp.getBoolean(KEY_HIDE_TOOLBAR, true)
        set(v) = sp.edit().putBoolean(KEY_HIDE_TOOLBAR, v).apply()

    var reloadOnResize: Boolean
        get() = sp.getBoolean(KEY_RELOAD_ON_RESIZE, true)
        set(v) = sp.edit().putBoolean(KEY_RELOAD_ON_RESIZE, v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(v) = sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean(KEY_AUTO_CONNECT, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_CONNECT, v).apply()

    var showMenuButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_MENU_BUTTON, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_MENU_BUTTON, v).apply()

    val recentUrls: List<String>
        get() = sp.getString(KEY_RECENT, "")!!.split('\n').filter { it.isNotBlank() }

    val lastUrl: String?
        get() = recentUrls.firstOrNull()

    fun addRecent(url: String) {
        val list = (listOf(url) + recentUrls.filter { it != url }).take(MAX_RECENT)
        sp.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    fun removeRecent(url: String) {
        sp.edit().putString(KEY_RECENT, recentUrls.filter { it != url }.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY_HIDE_TOOLBAR = "hide_toolbar"
        private const val KEY_RELOAD_ON_RESIZE = "reload_on_resize"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_SHOW_MENU_BUTTON = "show_menu_button"
        private const val KEY_RECENT = "recent_urls"
        private const val MAX_RECENT = 8

        /** Adds http:// when no scheme was typed; returns null when the URL is unusable. */
        fun normalizeUrl(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val uri = Uri.parse(s)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            return s
        }
    }
}
