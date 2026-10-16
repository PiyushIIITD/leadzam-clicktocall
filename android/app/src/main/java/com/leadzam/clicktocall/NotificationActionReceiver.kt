package com.leadzam.clicktocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles "Dismiss" and swipe-away on a call-request notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_DISMISS) return
        val id = intent.getStringExtra(Notifications.EXTRA_REQUEST_ID) ?: return
        CallRequestStore.init(context)
        // Swipe-away fires this too; don't downgrade a request already acted on.
        if (CallRequestStore.get(id)?.status == RequestStatus.PENDING) {
            RequestActions.dismiss(context, id)
        }
    }
}
