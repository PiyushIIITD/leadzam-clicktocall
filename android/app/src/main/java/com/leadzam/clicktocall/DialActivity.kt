package com.leadzam.clicktocall

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible trampoline behind the notification's "Call" action. It runs in
 * response to a user tap, opens the native dialer, and finishes immediately.
 */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallRequestStore.init(this)

        val id = intent.getStringExtra(Notifications.EXTRA_REQUEST_ID)
        val request = id?.let { CallRequestStore.get(it) }
        when {
            request == null ->
                Toast.makeText(this, R.string.toast_unknown_request, Toast.LENGTH_SHORT).show()
            !RequestActions.call(this, request) ->
                Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
