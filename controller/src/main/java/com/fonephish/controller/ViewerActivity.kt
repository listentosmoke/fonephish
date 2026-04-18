package com.fonephish.controller

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ViewerActivity : AppCompatActivity(), RemoteClient.Listener {

    private lateinit var surface: ScreenSurfaceView
    private lateinit var typeField: EditText
    private lateinit var status: TextView
    private var client: RemoteClient? = null
    private var suppressWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        surface = findViewById(R.id.surface)
        typeField = findViewById(R.id.typeField)
        status = findViewById(R.id.statusBar)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        status.text = getString(R.string.status_connecting)

        surface.onRemoteTouch = { action, nx, ny -> client?.sendTouch(action, nx, ny) }

        // Mirror locally-typed characters to the host.
        typeField.addTextChangedListener(object : TextWatcher {
            private var before = ""
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                before = s.toString()
            }
            override fun onTextChanged(s: CharSequence, start: Int, beforeCount: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (suppressWatcher) return
                val now = s.toString()
                val (removed, added) = diff(before, now)
                repeat(removed) { client?.sendKey("Backspace") }
                if (added.isNotEmpty()) client?.sendText(added)
            }
        })

        typeField.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isEnter) {
                client?.sendKey("Enter")
                suppressWatcher = true
                typeField.setText("")
                suppressWatcher = false
                true
            } else false
        }

        client = RemoteClient(url, this).also { it.connect() }
    }

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onConnected() = runOnUiThread {
        status.text = getString(R.string.status_connected)
    }

    override fun onDisconnected(reason: String?) = runOnUiThread {
        status.text = getString(R.string.status_disconnected) + (reason?.let { " — $it" } ?: "")
    }

    override fun onFrame(jpeg: ByteArray, width: Int, height: Int) {
        surface.updateFrame(jpeg, width, height)
    }

    private fun diff(before: String, now: String): Pair<Int, String> {
        var prefix = 0
        val min = minOf(before.length, now.length)
        while (prefix < min && before[prefix] == now[prefix]) prefix++
        val removed = before.length - prefix
        val added = now.substring(prefix)
        return removed to added
    }

    companion object { const val EXTRA_URL = "url" }
}
