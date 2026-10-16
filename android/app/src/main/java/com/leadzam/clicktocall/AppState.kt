package com.leadzam.clicktocall

import android.content.Context

/**
 * Tracks whether MainActivity is on screen. The FCM service uses this to pick
 * between an in-app card (foreground) and a system notification (background).
 * A single activity makes onStart/onStop an accurate visibility signal.
 */
object AppState {
    @Volatile
    var isForeground: Boolean = false

    private const val PREFS = "settings"
    const val DEFAULT_SERVER_URL = "http://10.0.2.2:3000" // emulator -> host machine

    fun serverUrl(context: Context): String =
        prefs(context).getString("server_url", DEFAULT_SERVER_URL)!!.trim().trimEnd('/')

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString("server_url", url.trim().trimEnd('/')).apply()
    }

    fun fcmToken(context: Context): String? = prefs(context).getString("fcm_token", null)

    fun setFcmToken(context: Context, token: String) {
        prefs(context).edit().putString("fcm_token", token).apply()
    }

    fun isRegistered(context: Context): Boolean = prefs(context).getBoolean("registered", false)

    fun setRegistered(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("registered", value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
