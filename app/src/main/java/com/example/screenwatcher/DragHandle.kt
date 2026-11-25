package com.example.screenwatcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * Nút ☰ nhỏ hiện ra khi thanh công cụ của lớp phủ bị ẩn.
 * Chạm để hiện lại thanh; giữ và kéo để dời sang chỗ khác cho khỏi vướng khi vẽ vùng / chọn điểm.
 * Vị trí được nhớ cho đến khi lớp phủ đóng.
 */
class DragHandle(private val ctx: Context, private val onTap: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val view = TextView(ctx)
    private var added = false

    // gravity BOTTOM|END: x, y là khoảng cách tới mép phải / mép dưới màn hình
    private val lp = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = dp(12); y = dp(100)
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    init {
        view.apply {
            text = "☰"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(22).toFloat() }

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                        if (moved) {
                            val (w, h) = screen()
                            // kéo sang phải -> x giảm, kéo xuống -> y giảm (vì gravity END / BOTTOM)
                            lp.x = (startX - dx.toInt()).coerceIn(0, max(0, w - width))
                            lp.y = (startY - dy.toInt()).coerceIn(0, max(0, h - height))
                            runCatching { wm.updateViewLayout(this, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onTap()
                }
                true
            }
        }
    }

    fun show() {
        if (!added) added = runCatching { wm.addView(view, lp) }.isSuccess
    }

    fun hide() {
        if (added) {
            runCatching { wm.removeView(view) }
            added = false
        }
    }
}
