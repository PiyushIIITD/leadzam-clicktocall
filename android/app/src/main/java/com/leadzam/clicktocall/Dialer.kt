package com.leadzam.clicktocall

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object Dialer {
    /**
     * Builds the intent that opens the native dialer with [phoneNumber] typed in.
     *
     * ACTION_DIAL (not ACTION_CALL) on purpose: it needs no CALL_PHONE
     * permission and never places the call by itself — the user presses the
     * green button. That is exactly the behaviour the CRM flow asks for.
     */
    fun dialIntent(phoneNumber: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phoneNumber, null))

    /** Returns false when no dialer app can handle the intent (e.g. a tablet). */
    fun open(context: Context, phoneNumber: String): Boolean = try {
        context.startActivity(dialIntent(phoneNumber).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
