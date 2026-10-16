package com.leadzam.clicktocall

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small persisted list of received call requests (newest first).
 *
 * Shared by the FCM service (which may run while no Activity exists), the
 * notification actions and MainActivity. Persisting in SharedPreferences means
 * a request received while the app process was dead is still visible when the
 * user opens the app.
 */
object CallRequestStore {
    private const val PREFS = "call_requests"
    private const val KEY = "items"
    private const val MAX_ITEMS = 20

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var items = mutableListOf<CallRequest>()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        items = load().toMutableList()
    }

    /** Returns false (and stores nothing) if this requestId was already seen — FCM can redeliver. */
    @Synchronized
    fun add(request: CallRequest): Boolean {
        if (items.any { it.requestId == request.requestId }) return false
        items.add(0, request)
        while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        persistAndNotify()
        return true
    }

    @Synchronized
    fun get(requestId: String): CallRequest? = items.firstOrNull { it.requestId == requestId }

    @Synchronized
    fun setStatus(requestId: String, status: RequestStatus) {
        val i = items.indexOfFirst { it.requestId == requestId }
        if (i < 0) return
        items[i] = items[i].copy(status = status)
        persistAndNotify()
    }

    @Synchronized
    fun all(): List<CallRequest> = items.toList()

    @Synchronized
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun persistAndNotify() {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.requestId)
                    .put("phone", it.phoneNumber)
                    .put("name", it.contactName)
                    .put("source", it.source)
                    .put("at", it.receivedAt)
                    .put("status", it.status.name)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        val snapshot = listeners.toList()
        main.post { snapshot.forEach { it() } }
    }

    private fun load(): List<CallRequest> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CallRequest(
                requestId = o.getString("id"),
                phoneNumber = o.getString("phone"),
                contactName = o.optString("name"),
                source = o.optString("source", "LeadZam CRM"),
                receivedAt = o.optLong("at"),
                status = runCatching { RequestStatus.valueOf(o.optString("status")) }
                    .getOrDefault(RequestStatus.PENDING),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
