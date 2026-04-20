package com.fonephish.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class ScreenSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object { private const val TAG = "SSV" }

    var onRemoteTouch: ((action: String, nx: Float, ny: Float) -> Unit)? = null
    var onRemoteEdit: ((start: Int, end: Int, text: String) -> Unit)? = null
    var onKeyboardText: ((String) -> Unit)? = null
    var onKeyboardKey: ((String) -> Unit)? = null

    @Volatile private var latest: Bitmap? = null
    private val editorBuffer: Editable = Editable.Factory.getInstance().newEditable("")
    private val contentRect = RectF()
    private var surfaceReady = false
    private val paintBg = Paint().apply { color = Color.BLACK }
    private val paintBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    @Volatile private var remoteEditable = false
    @Volatile private var remoteMultiline = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = remoteEditable

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!remoteEditable) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (remoteMultiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = Selection.getSelectionStart(editorBuffer).coerceAtLeast(0)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(editorBuffer).coerceAtLeast(0)
        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = editorBuffer

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val s = text?.toString() ?: return true
                if (s.isEmpty()) return true
                Log.d(TAG, "commitText '$s' cursorPos=$newCursorPosition")
                replaceSelection(s)
                onKeyboardText?.invoke(s)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                Log.d(TAG, "setComposingText text='${text ?: ""}' cursorPos=$newCursorPosition")
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                Log.d(TAG, "deleteSurroundingText before=$beforeLength after=$afterLength")
                deleteSelectionOrPrevious(beforeLength, afterLength)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int
            ): Boolean {
                Log.d(TAG, "deleteSurroundingTextInCodePoints before=$beforeLength after=$afterLength")
                deleteSelectionOrPrevious(beforeLength, afterLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                Log.d(TAG, "sendKeyEvent action=${event.action} keyCode=${event.keyCode}")
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL,
                        KeyEvent.KEYCODE_FORWARD_DEL -> {
                            deleteSelectionOrPrevious(1, 0)
                        }
                        KeyEvent.KEYCODE_ENTER -> onKeyboardKey?.invoke("Enter")
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                Log.d(TAG, "performEditorAction actionCode=$actionCode")
                if (!remoteMultiline) onKeyboardKey?.invoke("Enter")
                return true
            }

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                val cursor = Selection.getSelectionStart(editorBuffer).coerceAtLeast(0)
                val start = (cursor - n).coerceAtLeast(0)
                return editorBuffer.subSequence(start, cursor)
            }

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
                val cursor = Selection.getSelectionEnd(editorBuffer).coerceAtLeast(0)
                val end = (cursor + n).coerceAtMost(editorBuffer.length)
                return editorBuffer.subSequence(cursor, end)
            }

            override fun getSelectedText(flags: Int): CharSequence {
                val start = Selection.getSelectionStart(editorBuffer).coerceAtLeast(0)
                val end = Selection.getSelectionEnd(editorBuffer).coerceAtLeast(0)
                val min = minOf(start, end)
                val max = maxOf(start, end)
                return if (min == max) "" else editorBuffer.subSequence(min, max)
            }

            private fun deleteSelectionOrPrevious(beforeLength: Int, afterLength: Int) {
                val start = Selection.getSelectionStart(editorBuffer).coerceAtLeast(0)
                val end = Selection.getSelectionEnd(editorBuffer).coerceAtLeast(0)
                Log.d(TAG, "deleteSelectionOrPrevious start=$start end=$end textLen=${editorBuffer.length}")
                if (start != end) {
                    val min = minOf(start, end)
                    val max = maxOf(start, end)
                    editorBuffer.delete(min, max)
                    Selection.setSelection(editorBuffer, min)
                    onRemoteEdit?.invoke(min, max, "")
                    return
                }

                val deleteStart = when {
                    beforeLength > 0 -> (start - beforeLength).coerceAtLeast(0)
                    afterLength > 0 -> start
                    else -> (start - 1).coerceAtLeast(0)
                }
                val deleteEnd = when {
                    beforeLength > 0 -> start
                    afterLength > 0 -> (end + afterLength).coerceAtMost(editorBuffer.length)
                    else -> start
                }
                if (deleteStart == deleteEnd) return
                editorBuffer.delete(deleteStart, deleteEnd)
                Selection.setSelection(editorBuffer, deleteStart)
                onRemoteEdit?.invoke(deleteStart, deleteEnd, "")
            }

            private fun replaceSelection(text: String) {
                val start = Selection.getSelectionStart(editorBuffer).coerceAtLeast(0)
                val end = Selection.getSelectionEnd(editorBuffer).coerceAtLeast(start)
                editorBuffer.replace(start, end, text)
                Selection.setSelection(editorBuffer, start + text.length)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { Log.d(TAG, "surfaceCreated"); surfaceReady = true; redraw() }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) { Log.d(TAG, "surfaceChanged ${w}x${h}"); redraw() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { Log.d(TAG, "surfaceDestroyed"); surfaceReady = false }

    fun updateFrame(jpeg: ByteArray, w: Int, h: Int) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        Log.e(TAG, "updateFrame jpeg=${jpeg.size} decoded=${bmp != null} surfaceReady=$surfaceReady")
        if (bmp == null) return
        latest = bmp
        val posted = post { redraw() }
        Log.e(TAG, "post result=$posted")
    }

    private fun redraw() {
        Log.d(TAG, "redraw surfaceReady=$surfaceReady latest=${latest != null}")
        if (!surfaceReady) return
        val bmp = latest ?: return
        val canvas: Canvas = try { holder.lockCanvas() ?: return } catch (e: Throwable) { Log.w(TAG, "lockCanvas failed", e); return }
        try {
            val vw = width
            val vh = height
            val bw = bmp.width
            val bh = bmp.height
            if (vw == 0 || vh == 0 || bw == 0 || bh == 0) {
                canvas.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), paintBg)
                return
            }
            canvas.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), paintBg)

            val scale = maxOf(vw / bw.toFloat(), vh / bh.toFloat())
            val drawnW = bw * scale
            val drawnH = bh * scale
            val left = (vw - drawnW) / 2f
            val top = (vh - drawnH) / 2f
            contentRect.set(left, top, left + drawnW, top + drawnH)
            canvas.drawBitmap(bmp, null, contentRect, paintBitmap)
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
        }
    }

    fun setRemoteEditorState(
        editable: Boolean,
        multiline: Boolean,
        text: String? = null,
        selectionStart: Int = -1,
        selectionEnd: Int = -1
    ) {
        val normalizedText = text ?: ""
        val currentText = editorBuffer.toString()
        val currentSelectionStart = Selection.getSelectionStart(editorBuffer).coerceAtLeast(0)
        val currentSelectionEnd = Selection.getSelectionEnd(editorBuffer).coerceAtLeast(0)
        val desiredSelectionStart = selectionStart.coerceAtLeast(0)
        val desiredSelectionEnd = if (selectionEnd < 0) desiredSelectionStart else selectionEnd.coerceAtLeast(0)
        if (
            remoteEditable == editable &&
            remoteMultiline == multiline &&
            (!editable || (
                currentText == normalizedText &&
                    currentSelectionStart == desiredSelectionStart &&
                    currentSelectionEnd == desiredSelectionEnd
                ))
        ) return
        val imeModeChanged = remoteEditable != editable || remoteMultiline != multiline
        remoteEditable = editable
        remoteMultiline = multiline
        Log.d(TAG, "setRemoteEditorState editable=$editable multiline=$multiline textLen=${normalizedText.length} sel=$selectionStart..$selectionEnd")
        if (editable) {
            syncEditorBuffer(normalizedText, selectionStart, selectionEnd)
        } else {
            clearEditorBuffer()
        }

        val imm = context.getSystemService(InputMethodManager::class.java)
        post {
            if (imeModeChanged) {
                imm.restartInput(this)
                if (editable) {
                    requestFocus()
                    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    imm.hideSoftInputFromWindow(windowToken, 0)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = contentRect
        if (rect.isEmpty) return true
        val nx = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_CANCEL -> "cancel"
            else -> return true
        }
        onRemoteTouch?.invoke(action, nx, ny)
        if (action == "down") requestFocus()
        return true
    }

    private fun clearEditorBuffer() {
        editorBuffer.replace(0, editorBuffer.length, "")
        Selection.setSelection(editorBuffer, 0)
    }

    private fun syncEditorBuffer(text: String, selectionStart: Int, selectionEnd: Int) {
        val value = text
        editorBuffer.replace(0, editorBuffer.length, value)
        val start = selectionStart.coerceIn(0, value.length)
        val end = if (selectionEnd < 0) start else selectionEnd.coerceIn(0, value.length)
        Selection.setSelection(editorBuffer, start, end)
    }
}
