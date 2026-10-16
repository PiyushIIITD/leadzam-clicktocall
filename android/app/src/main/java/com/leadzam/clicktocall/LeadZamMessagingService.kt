package com.leadzam.clicktocall

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Entry point for FCM.
 *
 * The backend sends *data-only* messages (no `notification` block). For those,
 * Android always calls [onMessageReceived] — in foreground and background —
 * so our code, not the system tray, decides how to present the request.
 * (With a `notification` block, a backgrounded app would get a system-tray
 * notification whose tap just opens the launcher activity, and none of our
 * code, including the dial action, would run.)
 *
 * [onMessageReceived] runs on a background thread, so blocking network calls
 * for the ack are fine (FCM allows ~20 s).
 */
class LeadZamMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        IncomingRequestHandler.handle(this, message.data)
    }

    override fun onNewToken(token: String) {
        // Token rotated (reinstall, data clear, etc). Keep the backend in sync.
        AppState.setFcmToken(this, token)
        if (AppState.isRegistered(this)) {
            val error = ServerClient.register(this, token)
            if (error != null) Log.w("LeadZamFcm", "Re-register failed: $error")
        }
    }
}
