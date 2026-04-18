package com.fonephish.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Foreground service that:
 *  - Owns the MediaProjection and a VirtualDisplay backed by an ImageReader.
 *  - Encodes each captured frame as JPEG and hands it to [RemoteServer] for
 *    broadcast to all connected controller clients.
 *  - Runs the WebSocket server that receives input events from the controller.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: RemoteServer? = null

    private val imageThread = HandlerThread("cap-img").apply { start() }
    private val imageHandler by lazy { Handler(imageThread.looper) }

    private var width = 720
    private var height = 1280
    private var density = 320

    private var lastSendNs = 0L
    private val frameIntervalNs = 1_000_000_000L / 15 // ~15 fps

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent = intent.getParcelableExtra(EXTRA_DATA) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 8080)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(mainLooper))
        }

        configureMetrics()
        startCapture()
        startServer(port)

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureMetrics()
        rebuildVirtualDisplay()
    }

    private fun configureMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Downscale long side to ~720 to keep bandwidth reasonable over Cloudflare.
        val longSide = 720
        val scale = longSide.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels)
        width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2)
        height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)
        density = metrics.densityDpi
    }

    private fun startCapture() {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(::onImage, imageHandler)
        }
        virtualDisplay = projection?.createVirtualDisplay(
            "fonephish",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, imageHandler
        )
    }

    private fun rebuildVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()
        startCapture()
    }

    private fun onImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        try {
            val now = System.nanoTime()
            if (now - lastSendNs < frameIntervalNs) return
            lastSendNs = now

            val plane = image.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bmpW = image.width + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(bmpW, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)

            val cropped = if (bmpW != image.width) {
                Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            } else bmp

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 55, baos)
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()

            server?.broadcastFrame(baos.toByteArray(), image.width, image.height)
        } catch (t: Throwable) {
            Log.w(TAG, "encode failed", t)
        } finally {
            image.close()
        }
    }

    private fun startServer(port: Int) {
        server = RemoteServer(port).also { it.start() }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "fonephish-share"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, KioskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        server?.stopSafely()
        server = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        imageThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureSvc"
        private const val NOTIF_ID = 42
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PORT = "port"
    }
}
