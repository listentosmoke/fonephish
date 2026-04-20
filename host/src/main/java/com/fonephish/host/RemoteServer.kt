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

    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastW = 0
    @Volatile private var lastH = 0
    @Volatile private var lastEditable = false
    @Volatile private var lastMultiline = false
    @Volatile private var lastText: String? = null
    @Volatile private var lastSelectionStart = 0
    @Volatile private var lastSelectionEnd = 0

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    override fun onStart() {
        Log.i(TAG, "WS server up on ${address.port}")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        clients.add(conn)
        Log.e(TAG, "onOpen client=${conn.remoteSocketAddress} totalClients=${clients.size}")
        val jpeg = lastJpeg
        if (jpeg != null) {
            Log.e(TAG, "onOpen sending cached frame ${lastW}x${lastH} jpeg=${jpeg.size}")
            sendFrameTo(conn, jpeg, lastW, lastH)
        } else {
            Log.e(TAG, "onOpen no cached frame yet")
        }
        sendEditorStateTo(
            conn,
            lastEditable,
            lastMultiline,
            lastText,
            lastSelectionStart,
            lastSelectionEnd
        )
        ui.post {
            val wv = HostBus.webView
            Log.e(TAG, "onOpen invalidating webView=$wv")
            wv?.invalidate()
            if (wv != null) requestEditorState(wv)
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        clients.remove(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.w(TAG, "ws error", ex)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = try { JSONObject(message) } catch (t: Throwable) { return }
        Log.d(TAG, "onMessage type=${json.optString("t")} raw=$message")
        when (json.optString("t")) {
            "touch" -> handleTouch(json)
            "edit" -> handleEdit(json)
            "text" -> handleText(json.optString("v"))
            "key" -> handleKey(json.optString("k"))
            "scroll" -> handleScroll(json)
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) { /* unused */ }

    private var frameCount = 0
    fun broadcastFrame(jpeg: ByteArray, w: Int, h: Int) {
        lastJpeg = jpeg
        lastW = w
        lastH = h
        frameCount++
        if (frameCount % 15 == 1) Log.e(TAG, "broadcastFrame #$frameCount ${w}x${h} jpeg=${jpeg.size} clients=${clients.size}")
        if (clients.isEmpty()) return
        val snapshot = synchronized(clients) { clients.toList() }
        for (c in snapshot) sendFrameTo(c, jpeg, w, h)
    }

    private fun sendFrameTo(conn: WebSocket, jpeg: ByteArray, w: Int, h: Int) {
        val buf = ByteBuffer.allocate(jpeg.size + 8)
        buf.putShort((w and 0xFFFF).toShort())
        buf.putShort((h and 0xFFFF).toShort())
        buf.putInt(0)
        buf.put(jpeg)
        buf.flip()
        try {
            conn.send(buf)
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrameTo failed", t)
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
            if (webView is NoImeWebView) webView.suppressIme()
            if (a == "up" || a == "cancel") {
                requestEditorState(webView)
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
        Log.d(TAG, "handleText len=${text.length}")
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
              function dispatchKey(target, type, key) {
                try {
                  return target.dispatchEvent(new KeyboardEvent(type, {
                    key: key,
                    code: key === 'Backspace' ? 'Backspace' : key,
                    bubbles: true,
                    cancelable: true
                  }));
                } catch (_) {
                  return true;
                }
              }
              function dispatchInput(target, inputType, data) {
                try {
                  target.dispatchEvent(new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: inputType,
                    data: data
                  }));
                } catch (_) {}
                try {
                  target.dispatchEvent(new InputEvent('input', {
                    bubbles: true,
                    inputType: inputType,
                    data: data
                  }));
                } catch (_) {
                  target.dispatchEvent(new Event('input', { bubbles: true }));
                }
              }
              function setControlValue(target, nextValue) {
                var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                var prev = target.value || '';
                if (desc && desc.set) desc.set.call(target, nextValue);
                else target.value = nextValue;
                var tracker = target._valueTracker;
                if (tracker) tracker.setValue(prev);
              }
              if (tag==='input' || tag==='textarea') {
                var start = el.selectionStart != null ? el.selectionStart : (el.value||'').length;
                var end = el.selectionEnd != null ? el.selectionEnd : start;
                var v = el.value || '';
                dispatchKey(el, 'keydown', 'Unidentified');
                setControlValue(el, v.slice(0,start) + '$escaped' + v.slice(end));
                var pos = start + '$escaped'.length;
                try { el.setSelectionRange(pos,pos); } catch(e) {}
                dispatchInput(el, 'insertText', '$escaped');
                dispatchKey(el, 'keyup', 'Unidentified');
              } else if (el.isContentEditable) {
                document.execCommand('insertText', false, '$escaped');
              }
            })();
        """.trimIndent()
        ui.post {
            webView.evaluateJavascript(js) {
                if (webView is NoImeWebView) webView.suppressIme()
                requestEditorState(webView)
            }
        }
    }

    private fun handleEdit(json: JSONObject) {
        val webView = HostBus.webView ?: return
        val start = json.optInt("s", 0).coerceAtLeast(0)
        val end = json.optInt("e", start).coerceAtLeast(start)
        val text = json.optString("v", "")
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        Log.d(TAG, "handleEdit start=$start end=$end len=${text.length}")
        val js = """
            (function(){
              var el = document.activeElement;
              if (!el) return;
              var tag = (el.tagName||'').toLowerCase();
              function dispatchKey(target, type, key) {
                try {
                  return target.dispatchEvent(new KeyboardEvent(type, {
                    key: key,
                    code: key === 'Backspace' ? 'Backspace' : key,
                    bubbles: true,
                    cancelable: true
                  }));
                } catch (_) {
                  return true;
                }
              }
              function dispatchInput(target, inputType, data) {
                try {
                  target.dispatchEvent(new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: inputType,
                    data: data
                  }));
                } catch (_) {}
                try {
                  target.dispatchEvent(new InputEvent('input', {
                    bubbles: true,
                    inputType: inputType,
                    data: data
                  }));
                } catch (_) {
                  target.dispatchEvent(new Event('input', { bubbles: true }));
                }
              }
              if (tag === 'input' || tag === 'textarea') {
                var value = el.value || '';
                var s = Math.max(0, Math.min($start, value.length));
                var e = Math.max(s, Math.min($end, value.length));
                var inputType = '$escaped'.length === 0 ? 'deleteContentBackward' : 'insertText';
                var prev = value;
                var key = '$escaped'.length === 0 ? 'Backspace' : 'Unidentified';
                var allowed = dispatchKey(el, 'keydown', key);
                if (allowed === false) return;
                var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                var next = value.slice(0, s) + '$escaped' + value.slice(e);
                if (desc && desc.set) desc.set.call(el, next);
                else el.value = next;
                var caret = s + '$escaped'.length;
                try { el.setSelectionRange(caret, caret); } catch (_) {}
                var tracker = el._valueTracker;
                if (tracker) tracker.setValue(prev);
                dispatchInput(el, inputType, '$escaped');
                dispatchKey(el, 'keyup', key);
              } else if (el.isContentEditable) {
                if ('$escaped'.length === 0) {
                  dispatchKey(el, 'keydown', 'Backspace');
                  document.execCommand('delete');
                  dispatchKey(el, 'keyup', 'Backspace');
                } else {
                  document.execCommand('insertText', false, '$escaped');
                }
              }
            })();
        """.trimIndent()
        ui.post {
            webView.evaluateJavascript(js) {
                if (webView is NoImeWebView) webView.suppressIme()
                requestEditorState(webView)
            }
        }
    }

    private fun handleKey(key: String) {
        val webView = HostBus.webView ?: return
        Log.d(TAG, "handleKey key=$key lastEditable=$lastEditable sel=$lastSelectionStart..$lastSelectionEnd textLen=${lastText?.length ?: -1}")
        val js = when (key) {
            "Backspace" -> """
                (function(){
                  var el=document.activeElement;
                  if(!el) return;
                  var tag=(el.tagName||'').toLowerCase();
                  function dispatchKey(target, type, key) {
                    try {
                      return target.dispatchEvent(new KeyboardEvent(type, {
                        key: key,
                        code: key,
                        bubbles: true,
                        cancelable: true
                      }));
                    } catch (_) {
                      return true;
                    }
                  }
                  function dispatchInput(target, inputType) {
                    try {
                      target.dispatchEvent(new InputEvent('beforeinput', {
                        bubbles: true,
                        cancelable: true,
                        inputType: inputType,
                        data: null
                      }));
                    } catch (_) {}
                    try {
                      target.dispatchEvent(new InputEvent('input', {
                        bubbles: true,
                        inputType: inputType,
                        data: null
                      }));
                    } catch (_) {
                      target.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                  }
                  function setControlValue(target, nextValue) {
                    var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    var prev = target.value || '';
                    if (desc && desc.set) desc.set.call(target, nextValue);
                    else target.value = nextValue;
                    var tracker = target._valueTracker;
                    if (tracker) tracker.setValue(prev);
                  }
                  if(tag==='input'||tag==='textarea'){
                    var s=el.selectionStart,e=el.selectionEnd,v=el.value||'';
                    var allowed = dispatchKey(el, 'keydown', 'Backspace');
                    if (allowed === false) return;
                    if(s===e){
                      if(s<=0) return;
                      setControlValue(el, v.slice(0,s-1)+v.slice(e));
                      try{el.setSelectionRange(s-1,s-1);}catch(_){}
                    } else {
                      setControlValue(el, v.slice(0,s)+v.slice(e));
                      try{el.setSelectionRange(s,s);}catch(_){}
                    }
                    dispatchInput(el, 'deleteContentBackward');
                    dispatchKey(el, 'keyup', 'Backspace');
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
        ui.post {
            webView.evaluateJavascript(js) {
                if (webView is NoImeWebView) webView.suppressIme()
                requestEditorState(webView)
            }
        }
    }

    private fun requestEditorState(webView: WebView) {
        val js = """
            (function(){
              var el = document.activeElement;
              function isEditable(target) {
                if (!target || target.disabled || target.readOnly) return false;
                if (target.isContentEditable) return true;
                var tag = (target.tagName || '').toLowerCase();
                if (tag === 'textarea') return true;
                if (tag !== 'input') return false;
                var type = (target.type || 'text').toLowerCase();
                return ['button','checkbox','color','date','datetime-local','file','hidden','image','month','radio','range','reset','submit','time','week'].indexOf(type) === -1;
              }
              var editable = isEditable(el);
              var tag = el ? (el.tagName || '').toLowerCase() : '';
              var multiline = !!(editable && (tag === 'textarea' || (el && el.isContentEditable)));
              var text = null;
              var selectionStart = 0;
              var selectionEnd = 0;
              if (editable && tag === 'input') {
                var inputType = (el.type || 'text').toLowerCase();
                var rawValue = el.value || '';
                text = inputType === 'password' ? '*'.repeat(rawValue.length) : rawValue;
                selectionStart = el.selectionStart != null ? el.selectionStart : text.length;
                selectionEnd = el.selectionEnd != null ? el.selectionEnd : selectionStart;
              } else if (editable && tag === 'textarea') {
                text = el.value || '';
                selectionStart = el.selectionStart != null ? el.selectionStart : text.length;
                selectionEnd = el.selectionEnd != null ? el.selectionEnd : selectionStart;
              } else if (editable && el && el.isContentEditable) {
                text = el.innerText || '';
                selectionStart = text.length;
                selectionEnd = text.length;
              }
              return JSON.stringify({
                editable: editable,
                multiline: multiline,
                text: text,
                selectionStart: selectionStart,
                selectionEnd: selectionEnd
              });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val payload = try {
                if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                JSONObject("""{"v":$raw}""").getString("v")
            } catch (_: Throwable) {
                return@evaluateJavascript
            }
            val json = try { JSONObject(payload) } catch (_: Throwable) { return@evaluateJavascript }
            val editable = json.optBoolean("editable", false)
            val multiline = json.optBoolean("multiline", false)
            val text = if (json.has("text") && !json.isNull("text")) json.optString("text") else null
            val selectionStart = json.optInt("selectionStart", 0).coerceAtLeast(0)
            val selectionEnd = json.optInt("selectionEnd", selectionStart).coerceAtLeast(selectionStart)
            Log.d(TAG, "requestEditorState editable=$editable multiline=$multiline textLen=${text?.length ?: -1} sel=$selectionStart..$selectionEnd")
            lastEditable = editable
            lastMultiline = multiline
            lastText = text
            lastSelectionStart = selectionStart
            lastSelectionEnd = selectionEnd
            if (!editable && webView is NoImeWebView) {
                webView.suppressIme()
                webView.clearFocus()
                webView.post { webView.requestFocus() }
            }
            broadcastEditorState(editable, multiline, text, selectionStart, selectionEnd)
        }
    }

    private fun broadcastEditorState(
        editable: Boolean,
        multiline: Boolean,
        text: String?,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        if (clients.isEmpty()) return
        val snapshot = synchronized(clients) { clients.toList() }
        for (client in snapshot) {
            sendEditorStateTo(client, editable, multiline, text, selectionStart, selectionEnd)
        }
    }

    private fun sendEditorStateTo(
        conn: WebSocket,
        editable: Boolean,
        multiline: Boolean,
        text: String?,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        val payload = JSONObject()
            .put("t", "focus")
            .put("editable", editable)
            .put("multiline", multiline)
            .put("text", text)
            .put("selectionStart", selectionStart)
            .put("selectionEnd", selectionEnd)
            .toString()
        try {
            conn.send(payload)
        } catch (t: Throwable) {
            Log.e(TAG, "sendEditorStateTo failed", t)
        }
    }

    companion object { private const val TAG = "RemoteSrv" }
}
