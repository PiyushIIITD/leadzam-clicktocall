package com.leadzam.clicktocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * adb shell am broadcast -n com.leadzam.clicktocall/.DebugSimulateReceiver \
 *   --es phoneNumber +919876543210 --es contactName "Rahul Sharma" [--es requestId abc]
 */
class DebugSimulateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val data = mapOf(
            "type" to "dial_request",
            "requestId" to (intent.getStringExtra("requestId") ?: java.util.UUID.randomUUID().toString()),
            "phoneNumber" to (intent.getStringExtra("phoneNumber") ?: ""),
            "contactName" to (intent.getStringExtra("contactName") ?: ""),
            "source" to "LeadZam CRM",
        )
        val pending = goAsync()
        Executors.newSingleThreadExecutor().execute {
            try { IncomingRequestHandler.handle(app, data) } finally { pending.finish() }
        }
    }
}
