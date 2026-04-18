package com.fonephish.host

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections

/**
 * WebSocket server. Two kinds of traffic:
 *   - Server → client (binary): raw JPEG frames preceded by an 8-byte header
 *       [0..1] = uint16 BE width, [2..3] = uint16 BE height, [4..7] = reserved
 *   - Client → server (text): JSON input events
 *       {"t":"touch","a":"down|move|up","x":0.0..1.0,"y":0.0..1.0}
 *       {"t":"text","v":"..."}
 *       {"t":"key","k":"Backspace|Enter|..."}
 *       {"t":"scroll","dy":<px>,"x":0..1,"y":0..1}
 *
 * Coordinates are normalized (0..1) so the controller doesn't need to know the
 * host's capture resolution.
 */
class RemoteServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    private val clients = Collections.synchronizedSet(HashSet<WebSocket>())
    private val ui = Handler(Looper.getMainLooper())
    private var activeTouchDown = false
    private var touchStartTime = 0L

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    override fun onStart() {
        Log.i(TAG, "WS server up on ${address.port}")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        clients.add(conn)
        Log.i(TAG, "client connected: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        clients.remove(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.w(TAG, "ws error", ex)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = try { JSONObject(message) } catch (t: Throwable) { return }
        when (json.optString("t")) {
            "touch" -> handleTouch(json)
            "text" -> handleText(json.optString("v"))
            "key" -> handleKey(json.optString("k"))
            "scroll" -> handleScroll(json)
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) { /* unused */ }

    fun broadcastFrame(jpeg: ByteArray, w: Int, h: Int) {
        if (clients.isEmpty()) return
        val buf = ByteBuffer.allocate(jpeg.size + 8)
        buf.putShort((w and 0xFFFF).toShort())
        buf.putShort((h and 0xFFFF).toShort())
        buf.putInt(0)
        buf.put(jpeg)
        buf.flip()
        val snapshot = synchronized(clients) { clients.toList() }
        for (c in snapshot) {
            try { c.send(buf.duplicate()) } catch (_: Throwable) {}
        }
    }

    fun stopSafely() {
        try { stop(500) } catch (_: Throwable) {}
        clients.clear()
    }

    private fun handleTouch(json: JSONObject) {
        val webView = HostBus.webView ?: return
        val a = json.optString("a")
        val nx = json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f)
        val ny = json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
        ui.post {
            val w = webView.width
            val h = webView.height
            if (w == 0 || h == 0) return@post
            val x = nx * w
            val y = ny * h
            val action = when (a) {
                "down" -> {
                    activeTouchDown = true
                    touchStartTime = SystemClock.uptimeMillis()
                    MotionEvent.ACTION_DOWN
                }
                "move" -> MotionEvent.ACTION_MOVE
                "up" -> MotionEvent.ACTION_UP
                "cancel" -> MotionEvent.ACTION_CANCEL
                else -> return@post
            }
            val start = if (touchStartTime == 0L) SystemClock.uptimeMillis() else touchStartTime
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                webView.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            if (a == "up" || a == "cancel") {
                activeTouchDown = false
                touchStartTime = 0L
            }
        }
    }

    private fun handleScroll(json: JSONObject) {
        val webView = HostBus.webView ?: return
        val dy = json.optDouble("dy", 0.0).toFloat()
        ui.post { webView.scrollBy(0, dy.toInt()) }
    }

    private fun handleText(text: String) {
        val webView = HostBus.webView ?: return
        if (text.isEmpty()) return
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        val js = """
            (function(){
              var el = document.activeElement;
              if (!el) return;
              var tag = (el.tagName||'').toLowerCase();
              if (tag==='input' || tag==='textarea') {
                var start = el.selectionStart != null ? el.selectionStart : (el.value||'').length;
                var end = el.selectionEnd != null ? el.selectionEnd : start;
                var v = el.value || '';
                el.value = v.slice(0,start) + '$escaped' + v.slice(end);
                var pos = start + '$escaped'.length;
                try { el.setSelectionRange(pos,pos); } catch(e) {}
                el.dispatchEvent(new Event('input',{bubbles:true}));
                el.dispatchEvent(new Event('change',{bubbles:true}));
              } else if (el.isContentEditable) {
                document.execCommand('insertText', false, '$escaped');
              }
            })();
        """.trimIndent()
        ui.post { webView.evaluateJavascript(js, null) }
    }

    private fun handleKey(key: String) {
        val webView = HostBus.webView ?: return
        val js = when (key) {
            "Backspace" -> """
                (function(){
                  var el=document.activeElement;
                  if(!el) return;
                  var tag=(el.tagName||'').toLowerCase();
                  if(tag==='input'||tag==='textarea'){
                    var s=el.selectionStart,e=el.selectionEnd,v=el.value||'';
                    if(s===e){ if(s>0){ el.value=v.slice(0,s-1)+v.slice(e); try{el.setSelectionRange(s-1,s-1);}catch(_){} } }
                    else { el.value=v.slice(0,s)+v.slice(e); try{el.setSelectionRange(s,s);}catch(_){} }
                    el.dispatchEvent(new Event('input',{bubbles:true}));
                  } else if (el.isContentEditable){ document.execCommand('delete'); }
                })();
            """.trimIndent()
            "Enter" -> """
                (function(){
                  var el=document.activeElement;
                  if(!el) return;
                  if((el.tagName||'').toLowerCase()==='textarea'){
                    var s=el.selectionStart,e=el.selectionEnd,v=el.value||'';
                    el.value=v.slice(0,s)+'\n'+v.slice(e);
                    try{el.setSelectionRange(s+1,s+1);}catch(_){}
                    el.dispatchEvent(new Event('input',{bubbles:true}));
                  } else {
                    var form=el.form;
                    var ev=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',bubbles:true});
                    el.dispatchEvent(ev);
                    if(form && typeof form.requestSubmit==='function'){ try{form.requestSubmit();}catch(_){ form.submit(); } }
                  }
                })();
            """.trimIndent()
            else -> return
        }
        ui.post { webView.evaluateJavascript(js, null) }
    }

    companion object { private const val TAG = "RemoteSrv" }
}
