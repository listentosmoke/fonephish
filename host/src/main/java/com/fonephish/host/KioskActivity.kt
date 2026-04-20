package com.fonephish.host

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Fullscreen, pinned Activity that hosts the WebView users see shared.
 * Hides system bars, keeps the screen on, and (when started with screen pinning
 * enabled by the user) prevents leaving the page.
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pageUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        setContentView(R.layout.activity_kiosk)

        pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 8080)
        val mpResult = intent.getIntExtra(EXTRA_MP_RESULT, 0)
        val mpData: Intent? = intent.getParcelableExtra(EXTRA_MP_DATA)

        setupWebView()
        webView.loadUrl(pageUrl)

        hideSystemBars()

        if (mpData != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, mpResult)
                putExtra(ScreenCaptureService.EXTRA_DATA, mpData)
                putExtra(ScreenCaptureService.EXTRA_PORT, port)
            }
            startForegroundService(svc)
        }

        // Register this Activity's WebView as the input target.
        HostBus.webView = webView
        HostBus.activity = this

        // Opt into app pinning so the user cannot leave the page.
        // User must approve the prompt that appears.
        try { startLockTask() } catch (_: Throwable) { /* not allowed without setup */ }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        (webView as? NoImeWebView)?.suppressIme()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            (webView as? NoImeWebView)?.suppressIme()
        }
    }

    override fun onDestroy() {
        if (HostBus.webView === webView) {
            HostBus.webView = null
            HostBus.activity = null
        }
        stopService(Intent(this, ScreenCaptureService::class.java))
        try { stopLockTask() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // Block the usual ways out of the page.
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        // Otherwise swallow — user can't leave the page with back.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onUserLeaveHint() {
        // Swallow — screen pinning (lock task) is what actually enforces this.
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString + " FonephishHost/1.0"
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Keep navigation inside the WebView — never punt to an external app.
                view.loadUrl(request.url.toString())
                return true
            }
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    fun dispatchRemoteTouch(event: MotionEvent) {
        webView.dispatchTouchEvent(event)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PORT = "port"
        const val EXTRA_MP_RESULT = "mp_result"
        const val EXTRA_MP_DATA = "mp_data"
    }
}

/** Tiny in-process bridge so the service can reach the Activity's WebView. */
object HostBus {
    @Volatile var webView: WebView? = null
    @Volatile var activity: KioskActivity? = null
}
