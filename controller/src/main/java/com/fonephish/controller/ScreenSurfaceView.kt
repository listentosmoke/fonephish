package com.fonephish.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Displays the remote host's frames (JPEG) and forwards touch events
 * back as normalized [0..1] coordinates via [onRemoteTouch].
 *
 * Scaling uses "contain" (letterbox) so the host's full page is always visible
 * without cropping, regardless of the two devices' aspect ratios.
 */
class ScreenSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var onRemoteTouch: ((action: String, nx: Float, ny: Float) -> Unit)? = null

    private var latest: Bitmap? = null
    private var latestW = 0
    private var latestH = 0
    private var contentRect: Rect = Rect()
    private var surfaceReady = false
    private val paintBg = Paint().apply { color = Color.BLACK }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; redraw() }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) { redraw() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }

    fun updateFrame(jpeg: ByteArray, w: Int, h: Int) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        synchronized(this) {
            latest?.recycle()
            latest = bmp
            latestW = w
            latestH = h
        }
        post { redraw() }
    }

    private fun redraw() {
        if (!surfaceReady) return
        val bmp = synchronized(this) { latest } ?: return
        val canvas: Canvas = try { holder.lockCanvas() ?: return } catch (_: Throwable) { return }
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)
            val vw = width
            val vh = height
            val bw = bmp.width
            val bh = bmp.height
            if (vw == 0 || vh == 0 || bw == 0 || bh == 0) return
            val scale = minOf(vw.toFloat() / bw, vh.toFloat() / bh)
            val dw = (bw * scale).toInt()
            val dh = (bh * scale).toInt()
            val left = (vw - dw) / 2
            val top = (vh - dh) / 2
            contentRect = Rect(left, top, left + dw, top + dh)
            canvas.drawBitmap(bmp, null, contentRect, null)
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = contentRect
        if (rect.isEmpty) return true
        val localX = (event.x - rect.left).coerceIn(0f, rect.width().toFloat())
        val localY = (event.y - rect.top).coerceIn(0f, rect.height().toFloat())
        val nx = localX / rect.width()
        val ny = localY / rect.height()
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_CANCEL -> "cancel"
            else -> return true
        }
        onRemoteTouch?.invoke(action, nx, ny)
        return true
    }
}
