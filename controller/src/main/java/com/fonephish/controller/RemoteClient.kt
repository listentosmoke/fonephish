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
        fun onEditorState(
            editable: Boolean,
            multiline: Boolean,
            text: String?,
            selectionStart: Int,
            selectionEnd: Int
        )
    }

    private var ws: WebSocketClient? = null

    fun connect() {
        Log.e(TAG, "connect() called url=$url")
        val uri = try { URI.create(url) } catch (e: Exception) { Log.e(TAG, "bad URI", e); return }
        ws = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.e(TAG, "onOpen connected!")
                listener.onConnected()
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.e(TAG, "onClose code=$code reason=$reason remote=$remote")
                listener.onDisconnected(reason)
            }
            override fun onError(ex: Exception?) {
                Log.e(TAG, "onError", ex)
                listener.onDisconnected(ex?.message)
            }
            override fun onMessage(message: String?) {
                Log.e(TAG, "text msg: $message")
                val jsonText = message ?: return
                val json = try { JSONObject(jsonText) } catch (_: Throwable) { return }
                when (json.optString("t")) {
                    "focus" -> listener.onEditorState(
                        json.optBoolean("editable", false),
                        json.optBoolean("multiline", false),
                        if (json.has("text") && !json.isNull("text")) json.optString("text") else null,
                        json.optInt("selectionStart", -1),
                        json.optInt("selectionEnd", -1)
                    )
                }
            }
            override fun onMessage(bytes: ByteBuffer) {
                Log.e(TAG, "binary msg ${bytes.remaining()} bytes")
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
        Log.e(TAG, "connect() dispatched ws thread")
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
        Log.d(TAG, "sendText len=${text.length}")
        send(JSONObject().apply { put("t", "text"); put("v", text) })
    }

    fun sendEdit(start: Int, end: Int, text: String) {
        Log.d(TAG, "sendEdit start=$start end=$end len=${text.length}")
        send(JSONObject().apply {
            put("t", "edit")
            put("s", start)
            put("e", end)
            put("v", text)
        })
    }

    fun sendKey(key: String) {
        Log.d(TAG, "sendKey key=$key")
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
