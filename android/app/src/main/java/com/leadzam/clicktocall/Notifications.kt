package com.leadzam.clicktocall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val CHANNEL_ID = "call_requests"
    const val EXTRA_REQUEST_ID = "request_id"
    const val ACTION_DISMISS = "com.leadzam.clicktocall.DISMISS"
    private const val AUTO_EXPIRE_MS = 10 * 60 * 1000L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH, // heads-up: the user is waiting for this
            ).apply { description = context.getString(R.string.channel_description) }
        )
    }

    /** Stable per-request id so several requests stack instead of replacing each other. */
    fun notificationId(requestId: String): Int = requestId.hashCode()

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Shows a heads-up notification for a request received while the app is in
     * the background. Returns false if notifications are blocked (the request
     * is still in the store and visible in the app).
     *
     * - Tapping the body opens MainActivity, which shows who/what/from-where.
     * - "Call" goes through [DialActivity] (a trampoline *activity*; Android 12+
     *   blocks starting activities from broadcast receivers/services).
     * - "Dismiss" and swipe-away go to [NotificationActionReceiver].
     */
    fun show(context: Context, request: CallRequest): Boolean {
        if (!canPostNotifications(context)) return false
        ensureChannel(context)
        val nid = notificationId(request.requestId)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val open = PendingIntent.getActivity(
            context, nid * 4,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_REQUEST_ID, request.requestId),
            flags
        )
        val call = PendingIntent.getActivity(
            context, nid * 4 + 1,
            Intent(context, DialActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, request.requestId),
            flags
        )
        val dismiss = PendingIntent.getBroadcast(
            context, nid * 4 + 2,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .putExtra(EXTRA_REQUEST_ID, request.requestId),
            flags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title, request.displayName))
            .setContentText(context.getString(R.string.notif_text, request.phoneNumber, request.source))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notif_text, request.phoneNumber, request.source)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // Android would otherwise add its own "Call number" smart action next to ours.
            .setAllowSystemGeneratedContextualActions(false)
            .setTimeoutAfter(AUTO_EXPIRE_MS)
            .setContentIntent(open)
            .setDeleteIntent(dismiss) // swipe-away counts as dismissed
            .addAction(0, context.getString(R.string.action_call), call)
            .addAction(0, context.getString(R.string.action_dismiss), dismiss)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(nid, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context, requestId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(requestId))
    }
}
