package com.leadzam.clicktocall

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.Executors

/**
 * Single screen: shows this device's FCM token, lets you point the app at the
 * backend and register, and lists call requests.
 *
 * While this activity is started, [AppState.isForeground] is true and the FCM
 * service shows requests here instead of posting notifications.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tokenText: TextView
    private lateinit var statusText: TextView
    private lateinit var serverInput: EditText
    private lateinit var emptyText: TextView
    private lateinit var adapter: RequestAdapter
    private val io = Executors.newSingleThreadExecutor()

    private val onStoreChanged: () -> Unit = { refreshList() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.toast_notifications_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallRequestStore.init(this)
        Notifications.ensureChannel(this)
        setContentView(R.layout.activity_main)

        tokenText = findViewById(R.id.tokenText)
        statusText = findViewById(R.id.statusText)
        serverInput = findViewById(R.id.serverInput)
        emptyText = findViewById(R.id.emptyText)

        serverInput.setText(AppState.serverUrl(this))

        adapter = RequestAdapter(
            onCall = { request ->
                if (!RequestActions.call(this, request)) {
                    Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { request -> RequestActions.dismiss(this, request.requestId) },
        )
        findViewById<RecyclerView>(R.id.requestList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.copyButton).setOnClickListener { copyToken() }
        findViewById<Button>(R.id.registerButton).setOnClickListener { registerWithBackend() }

        requestNotificationPermissionIfNeeded()
        loadToken()
    }

    override fun onStart() {
        super.onStart()
        AppState.isForeground = true
        CallRequestStore.addListener(onStoreChanged)
        refreshList()
    }

    override fun onStop() {
        AppState.isForeground = false
        CallRequestStore.removeListener(onStoreChanged)
        super.onStop()
    }

    private fun refreshList() {
        val items = CallRequestStore.all()
        adapter.submitList(items)
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadToken() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            // google-services.json was missing at build time.
            tokenText.setText(R.string.firebase_not_configured)
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                AppState.setFcmToken(this, token)
                tokenText.text = token
            } else {
                tokenText.text = getString(R.string.token_error, task.exception?.message ?: "unknown")
            }
        }
    }

    private fun copyToken() {
        val token = AppState.fcmToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_no_token, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FCM token", token))
        Toast.makeText(this, R.string.toast_token_copied, Toast.LENGTH_SHORT).show()
    }

    private fun registerWithBackend() {
        val token = AppState.fcmToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_no_token, Toast.LENGTH_SHORT).show()
            return
        }
        val url = serverInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            serverInput.error = getString(R.string.error_server_url)
            return
        }
        AppState.setServerUrl(this, url)
        statusText.setText(R.string.status_registering)
        io.execute {
            val error = ServerClient.register(this, token)
            runOnUiThread {
                if (error == null) {
                    AppState.setRegistered(this, true)
                    statusText.text = getString(R.string.status_registered, AppState.serverUrl(this))
                } else {
                    statusText.text = getString(R.string.status_register_failed, error)
                }
            }
        }
    }
}
