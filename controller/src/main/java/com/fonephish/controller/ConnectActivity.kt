package com.fonephish.controller

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ConnectActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        val endpoint = findViewById<EditText>(R.id.endpointInput)
        val connect = findViewById<Button>(R.id.connectBtn)
        val prefs = getSharedPreferences("fonephish", Context.MODE_PRIVATE)
        endpoint.setText(prefs.getString("endpoint", ""))

        connect.setOnClickListener {
            val raw = endpoint.text.toString().trim()
            val url = normalize(raw)
            if (url == null) {
                Toast.makeText(this, "Enter wss://host or ws://host:port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("endpoint", raw).apply()
            startActivity(Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_URL, url))
        }
    }

    private fun normalize(input: String): String? {
        if (input.isEmpty()) return null
        return when {
            input.startsWith("ws://") || input.startsWith("wss://") -> input
            input.startsWith("https://") -> "wss://" + input.removePrefix("https://")
            input.startsWith("http://") -> "ws://" + input.removePrefix("http://")
            else -> "wss://$input"
        }
    }
}
