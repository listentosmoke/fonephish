package com.fonephish.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var portInput: EditText
    private lateinit var status: TextView
    private lateinit var startBtn: Button

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchKiosk(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        urlInput = findViewById(R.id.urlInput)
        portInput = findViewById(R.id.portInput)
        status = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)

        val prefs = getSharedPreferences("fonephish", Context.MODE_PRIVATE)
        urlInput.setText(prefs.getString("url", ""))
        portInput.setText(prefs.getString("port", "8080"))

        startBtn.setOnClickListener {
            val url = urlInput.text.toString().trim().ifEmpty {
                Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val port = portInput.text.toString().toIntOrNull() ?: 8080
            prefs.edit().putString("url", url).putString("port", port.toString()).apply()

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun launchKiosk(resultCode: Int, data: Intent) {
        val url = urlInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 8080

        val intent = Intent(this, KioskActivity::class.java).apply {
            putExtra(KioskActivity.EXTRA_URL, url)
            putExtra(KioskActivity.EXTRA_PORT, port)
            putExtra(KioskActivity.EXTRA_MP_RESULT, resultCode)
            putExtra(KioskActivity.EXTRA_MP_DATA, data)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        status.text = getString(R.string.status_running, port)
    }
}
