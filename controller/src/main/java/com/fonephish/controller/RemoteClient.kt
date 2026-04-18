package com.fonephish.controller

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

/**
 * WebSocket client for the controller side. Receives binary framed JPEGs and
 * ships input events (touch / text / key / scroll) back to the host.
 */
class RemoteClient(private val url: String, private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onFrame(jpeg: ByteArray, width: Int, height: Int)
    }

    private var ws: WebSocketClient? = null

    fun connect() {
        val uri = URI.create(url)
        ws = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) { listener.onConnected() }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                listener.onDisconnected(reason)
            }
            override fun onError(ex: Exception?) {
                Log.w(TAG, "ws error", ex)
                listener.onDisconnected(ex?.message)
            }
            override fun onMessage(message: String?) { /* no text from host */ }
            override fun onMessage(bytes: ByteBuffer) {
                if (bytes.remaining() < 8) return
                val w = bytes.short.toInt() and 0xFFFF
                val h = bytes.short.toInt() and 0xFFFF
                bytes.int // reserved
                val payload = ByteArray(bytes.remaining())
                bytes.get(payload)
                listener.onFrame(payload, w, h)
            }
        }.apply {
            connectionLostTimeout = 20
            connect()
        }
    }

    fun close() {
        try { ws?.close() } catch (_: Throwable) {}
        ws = null
    }

    fun sendTouch(action: String, nx: Float, ny: Float) {
        send(JSONObject().apply {
            put("t", "touch")
            put("a", action)
            put("x", nx.toDouble())
            put("y", ny.toDouble())
        })
    }

    fun sendText(text: String) {
        send(JSONObject().apply { put("t", "text"); put("v", text) })
    }

    fun sendKey(key: String) {
        send(JSONObject().apply { put("t", "key"); put("k", key) })
    }

    fun sendScroll(dy: Float, nx: Float, ny: Float) {
        send(JSONObject().apply {
            put("t", "scroll"); put("dy", dy.toDouble())
            put("x", nx.toDouble()); put("y", ny.toDouble())
        })
    }

    private fun send(json: JSONObject) {
        val c = ws ?: return
        if (!c.isOpen) return
        try { c.send(json.toString()) } catch (_: Throwable) {}
    }

    companion object { private const val TAG = "RemoteClient" }
}
