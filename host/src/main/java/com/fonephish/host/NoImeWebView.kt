package com.fonephish.host

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * WebView that never becomes an IME target. Text input is injected
 * directly into the focused DOM element by [RemoteServer] via JS, so
 * the host device's soft keyboard must never appear.
 */
class NoImeWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : WebView(context, attrs) {
    init {
        disableSoftInputOnFocus()
    }

    override fun onCheckIsTextEditor(): Boolean = false
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
            post { suppressIme() }
        }
        return handled
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) post { suppressIme() }
    }

    fun suppressIme() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun disableSoftInputOnFocus() {
        try {
            javaClass.getMethod("setShowSoftInputOnFocus", Boolean::class.javaPrimitiveType)
                .invoke(this, false)
        } catch (_: Throwable) {
        }
    }
}
