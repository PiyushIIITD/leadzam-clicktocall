package com.leadzam.clicktocall

import android.content.Context
import android.util.Log

/**
 * What happens when a dial request arrives: validate, de-duplicate, store, then
 * present it according to whether the app is visible. Kept out of the FCM
 * service so the same code path can be exercised by a debug-only receiver in
 * tests without Firebase.
 *
 * Blocking (does a network ack) — call from a background thread.
 */
object IncomingRequestHandler {
    private const val TAG = "LeadZamFcm"

    fun handle(context: Context, data: Map<String, String>) {
        CallRequestStore.init(context)

        val request = DialRequestParser.parse(data)
        if (request == null) {
            Log.w(TAG, "Ignoring message with invalid/unknown payload: ${data.keys}")
            return
        }

        // FCM can redeliver; the same requestId must not ring twice.
        if (!CallRequestStore.add(request)) {
            Log.i(TAG, "Duplicate request ${request.requestId} ignored")
            return
        }

        if (AppState.isForeground) {
            // App is open: MainActivity observes the store and shows the card.
            // No notification, and never start an activity over the user's screen.
            Log.i(TAG, "Request ${request.requestId} shown in-app")
        } else {
            val shown = Notifications.show(context, request)
            Log.i(TAG, "Request ${request.requestId} notification shown=$shown")
        }

        // Tell the CRM the phone really got it (powers "Received by phone").
        val error = ServerClient.ack(context, request.requestId, "received")
        if (error != null) Log.w(TAG, "Ack failed: $error")
    }
}
