package com.fonephish.controller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ViewerActivity : AppCompatActivity(), RemoteClient.Listener {

    private lateinit var surface: ScreenSurfaceView
    private lateinit var statusCard: View
    private lateinit var statusText: TextView
    private var client: RemoteClient? = null
    private var hasRenderedFrame = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectScheduled = false
    private lateinit var endpointUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        surface = findViewById(R.id.surface)
        statusCard = findViewById(R.id.connectionStatusCard)
        statusText = findViewById(R.id.connectionStatusText)

        endpointUrl = intent.getStringExtra(EXTRA_URL) ?: ControllerConfig.FIXED_ENDPOINT

        surface.onRemoteTouch = { action, nx, ny -> client?.sendTouch(action, nx, ny) }
        surface.onKeyboardText = { text -> client?.sendText(text) }
        surface.onRemoteEdit = { start, end, text -> client?.sendEdit(start, end, text) }
        surface.onKeyboardKey = { key -> client?.sendKey(key) }
        surface.setRemoteEditorState(editable = false, multiline = false)

        showStatus(R.string.status_connecting)
        connectCurrentEndpoint()
    }

    override fun onDestroy() {
        reconnectHandler.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        super.onDestroy()
    }

    override fun onConnected() {
        runOnUiThread {
            reconnectScheduled = false
            if (!hasRenderedFrame) showStatus(R.string.status_connected)
        }
    }

    override fun onDisconnected(reason: String?) {
        runOnUiThread {
            client?.close()
            client = null
            hasRenderedFrame = false
            showStatus(R.string.status_disconnected)
            if (!isFinishing && !isDestroyed) {
                scheduleReconnect()
            }
        }
    }

    override fun onFrame(jpeg: ByteArray, width: Int, height: Int) {
        runOnUiThread {
            hasRenderedFrame = true
            statusCard.visibility = View.GONE
            surface.updateFrame(jpeg, width, height)
        }
    }

    override fun onEditorState(
        editable: Boolean,
        multiline: Boolean,
        text: String?,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        runOnUiThread {
            surface.setRemoteEditorState(editable, multiline, text, selectionStart, selectionEnd)
        }
    }

    override fun onBackPressed() {
        // Keep the controller pinned inside the viewer.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showStatus(messageResId: Int) {
        statusCard.visibility = View.VISIBLE
        statusText.setText(messageResId)
    }

    private fun connectCurrentEndpoint() {
        reconnectScheduled = false
        client?.close()
        client = RemoteClient(endpointUrl, this).also { it.connect() }
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        reconnectHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            showStatus(R.string.status_connecting)
            connectCurrentEndpoint()
        }, RECONNECT_DELAY_MS)
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val RECONNECT_DELAY_MS = 3_000L
    }
}
