package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

open class SafeNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    init {
        // Disable framework scrollbars by default to avoid OEM/framework crashes
        // in ScrollBarDrawable code paths.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
    }

    private var scrollBarCrashOccurred = false

    override fun draw(canvas: Canvas) {
        try {
            super.draw(canvas)
        } catch (e: NullPointerException) {
            if (!isFrameworkScrollBarCrash(e)) {
                throw e
            }
            if (!scrollBarCrashOccurred) {
                // First crash: disable scroll bars and request a fresh draw.
                // Do NOT retry within the same frame — the scrollbar state hasn't
                // been cleared yet, so a retry would crash again.
                disableScrollBarsAfterCrash()
                postInvalidate()
            }
            // Subsequent crashes (or the re-entrant retry): silently skip this frame.
        }
    }

    override fun onDrawForeground(canvas: Canvas) {
        // Some Android/OEM builds can crash inside View.onDrawScrollBars()
        // when the internal ScrollBarDrawable is null. SafeNestedScrollView never
        // relies on framework scrollbars, so skip the foreground scrollbar path.
    }

    private fun isFrameworkScrollBarCrash(error: NullPointerException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("ScrollBarDrawable", ignoreCase = true)
    }

    private fun disableScrollBarsAfterCrash() {
        scrollBarCrashOccurred = true
        AppLogger.log(
            "SafeNestedScrollView",
            "Suppressed framework scrollbar crash and disabled scrollbars"
        )
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
}
