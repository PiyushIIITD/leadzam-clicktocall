package com.leadzam.clicktocall

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTP client for the two backend endpoints the phone uses.
 * Callers must be off the main thread. Everything is best effort: a failed ack
 * never prevents the user from dialing.
 */
object ServerClient {
    private const val TIMEOUT_MS = 5_000

    /** Returns null on success, or a human-readable error. */
    fun register(context: Context, token: String): String? {
        val body = JSONObject()
            .put("token", token)
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        return post(context, "/api/register", body)
    }

    fun ack(context: Context, requestId: String, event: String): String? =
        post(context, "/api/ack", JSONObject().put("requestId", requestId).put("event", event))

    private fun post(context: Context, path: String, body: JSONObject): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AppState.serverUrl(context) + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) null else "Server returned HTTP $code"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            conn?.disconnect()
        }
    }
}
