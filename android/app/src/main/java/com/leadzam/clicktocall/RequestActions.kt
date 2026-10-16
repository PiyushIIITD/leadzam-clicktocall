package com.leadzam.clicktocall

import android.content.Context
import java.util.concurrent.Executors

/**
 * The two things a user can do with a request. Shared by the in-app card, the
 * notification actions and the trampoline so behaviour is identical everywhere.
 */
object RequestActions {
    private val io = Executors.newSingleThreadExecutor()

    /** Opens the dialer pre-filled. Returns false if the device has no dialer. */
    fun call(context: Context, request: CallRequest): Boolean {
        val opened = Dialer.open(context, request.phoneNumber)
        if (opened) {
            CallRequestStore.setStatus(request.requestId, RequestStatus.OPENED)
            Notifications.cancel(context, request.requestId)
            ackAsync(context, request.requestId, "opened")
        }
        return opened
    }

    fun dismiss(context: Context, requestId: String) {
        CallRequestStore.setStatus(requestId, RequestStatus.DISMISSED)
        Notifications.cancel(context, requestId)
        ackAsync(context, requestId, "dismissed")
    }

    fun ackAsync(context: Context, requestId: String, event: String) {
        val app = context.applicationContext
        io.execute { ServerClient.ack(app, requestId, event) }
    }
}
