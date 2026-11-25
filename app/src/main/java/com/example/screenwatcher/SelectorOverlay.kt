package com.example.screenwatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lớp phủ toàn màn hình để người dùng kéo chọn vùng (nhiều vùng) / ảnh mẫu / điểm chạm
 * ngay trên màn hình thật của ứng dụng khác.
 *
 * mode: "REGIONS" (nhiều vùng) | "TEMPLATE" (1 vùng -> cắt làm ảnh mẫu) | "POINT" (1 điểm chạm)
 *       | "SWIPE" (kéo từ điểm đầu tới điểm cuối của cú vuốt)
 */
class SelectorOverlay(
    private val ctx: Context,
    private val mode: String,
    private val onFinish: (List<Region>, Point?, Pair<Point, Point>?) -> Unit,
    private val onCancel: () -> Unit,
    /** Các khung đã lưu trước đó: hiện sẵn trên lớp phủ để chỉ cần kéo chỉnh (REGIONS: mọi vùng; TEMPLATE: khung ảnh mẫu). */
    initial: List<Region> = emptyList(),
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rects = mutableListOf<Region>()
    private var cur: Region? = null
    private var point: Point? = null
    private var sw1: Point? = null
    private var sw2: Point? = null
    private var drawing = true

    init { rects.addAll(initial) }

    private val dv = DrawView(ctx)
    private val bar = LinearLayout(ctx)
    private val handle = DragHandle(ctx) { setBarVisible(true) }

    private val dvLp = params(
        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 0,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    private val barLp = params(
        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
    ).apply { y = 100 }

    private fun params(w: Int, h: Int, g: Int, flags: Int) = LayoutParams(
        w, h, LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = g
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun show() {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
        }
        val toggle = Button(ctx)
        fun add(b: View) = row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun mk(t: String, f: () -> Unit) = Button(ctx).apply {
            text = t; isAllCaps = false; textSize = 13f
            minWidth = 0; minimumWidth = 0
            setPadding((4 * ctx.resources.displayMetrics.density).toInt(), 0, (4 * ctx.resources.displayMetrics.density).toInt(), 0)
            setOnClickListener { f() }
        }
        toggle.apply {
            text = "✋ Thao tác"; isAllCaps = false; textSize = 13f
            minWidth = 0; minimumWidth = 0
            setPadding((4 * ctx.resources.displayMetrics.density).toInt(), 0, (4 * ctx.resources.displayMetrics.density).toInt(), 0)
            setOnClickListener {
                drawing = !drawing
                dvLp.flags = if (drawing) dvLp.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else dvLp.flags or LayoutParams.FLAG_NOT_TOUCHABLE
                text = if (drawing) "✋ Thao tác" else "✏ Vẽ"
                runCatching { wm.updateViewLayout(dv, dvLp) }
                toast(if (drawing) "Đang ở chế độ vẽ" else "Có thể chuyển sang ứng dụng/màn hình khác, xong bấm ✏ Vẽ")
            }
        }
        add(mk("✔ Xong") { done() })
        add(toggle)
        add(mk("⌫ Xoá") { rects.clear(); cur = null; point = null; sw1 = null; sw2 = null; dv.invalidate() })
        add(mk("✖ Huỷ") { hide(); onCancel() })
        add(mk("▾ Ẩn") { setBarVisible(false) })
        bar.orientation = LinearLayout.VERTICAL
        bar.addView(row)

        wm.addView(dv, dvLp)
        wm.addView(bar, barLp)
    }

    /** Ẩn / hiện thanh công cụ (khi ẩn có nút ☰ nhỏ để hiện lại, giữ và kéo để dời chỗ). */
    private fun setBarVisible(v: Boolean) {
        bar.visibility = if (v) View.VISIBLE else View.GONE
        if (v) handle.hide() else handle.show()
    }

    fun hide() {
        handle.hide()
        runCatching { wm.removeView(dv) }
        runCatching { wm.removeView(bar) }
    }

    private fun done() {
        val ok = when (mode) {
            "POINT" -> point != null
            "SWIPE" -> sw1 != null && sw2 != null
            else -> rects.isNotEmpty()
        }
        if (!ok) {
            toast("Chưa chọn gì cả"); return
        }
        hide()
        val a = sw1
        val b = sw2
        onFinish(rects.toList(), point, if (a != null && b != null) a to b else null)
    }

    private inner class DrawView(c: Context) : View(c) {
        private val fill = Paint().apply { color = 0x3300AAFF; style = Paint.Style.FILL }
        private val stroke = Paint().apply { color = 0xFF00AAFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
        private val solid = Paint().apply { color = 0xFF00AAFF.toInt(); style = Paint.Style.FILL }
        private val txt = Paint().apply { color = Color.WHITE; textSize = 40f; isFakeBoldText = true }
        private val loc = IntArray(2)
        private var sx = 0
        private var sy = 0

        // Chỉnh vùng đã vẽ: bit 1 = cạnh trái, 2 = trên, 4 = phải, 8 = dưới, 16 = dời cả vùng
        private var dragIdx = -1
        private var dragMask = 0
        private var dragX = 0
        private var dragY = 0
        private var startRegion: Region? = null

        private fun hitRegion(x: Int, y: Int): Pair<Int, Int>? {
            val tol = (28 * resources.displayMetrics.density).toInt()
            for (i in rects.indices.reversed()) {
                val r = rects[i]
                if (x < r.l - tol || x > r.r + tol || y < r.t - tol || y > r.b + tol) continue
                var mask = 0
                val dl = abs(x - r.l)
                val dr = abs(x - r.r)
                val dt = abs(y - r.t)
                val db = abs(y - r.b)
                if (dl <= tol || dr <= tol) mask = mask or (if (dl <= dr) 1 else 4)
                if (dt <= tol || db <= tol) mask = mask or (if (dt <= db) 2 else 8)
                if (mask != 0) return i to mask
                if (x >= r.l && x <= r.r && y >= r.t && y <= r.b) return i to 16
            }
            return null
        }

        private fun applyDrag(x: Int, y: Int) {
            val s = startRegion ?: return
            if (dragIdx < 0 || dragIdx >= rects.size) return
            val dx = x - dragX
            val dy = y - dragY
            val minSz = (24 * resources.displayMetrics.density).toInt()
            val mw = min(minSz, s.w)
            val mh = min(minSz, s.h)
            var l = s.l
            var t = s.t
            var r = s.r
            var b = s.b
            if (dragMask == 16) {
                l = (s.l + dx).coerceIn(0, max(0, width - s.w))
                t = (s.t + dy).coerceIn(0, max(0, height - s.h))
                r = l + s.w
                b = t + s.h
            } else {
                if ((dragMask and 1) != 0) l = (s.l + dx).coerceIn(0, max(0, s.r - mw))
                if ((dragMask and 4) != 0) r = (s.r + dx).coerceIn(min(s.l + mw, width), width)
                if ((dragMask and 2) != 0) t = (s.t + dy).coerceIn(0, max(0, s.b - mh))
                if ((dragMask and 8) != 0) b = (s.b + dy).coerceIn(min(s.t + mh, height), height)
            }
            rects[dragIdx] = Region(l, t, r, b)
        }

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            c.drawColor(0x44000000)
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            (rects + listOfNotNull(cur)).forEachIndexed { i, r ->
                val rf = RectF(r.l.toFloat(), r.t.toFloat(), r.r.toFloat(), r.b.toFloat())
                c.drawRect(rf, fill)
                c.drawRect(rf, stroke)
                c.drawText("${i + 1}", r.l + 10f, r.t + 44f, txt)
                if (i < rects.size) {
                    // chấm điều khiển ở 4 góc và giữa 4 cạnh
                    val hr = 1.5f * resources.displayMetrics.density
                    val cx = (rf.left + rf.right) / 2
                    val cy = (rf.top + rf.bottom) / 2
                    for (hx in floatArrayOf(rf.left, cx, rf.right)) {
                        for (hy in floatArrayOf(rf.top, cy, rf.bottom)) {
                            if (hx == cx && hy == cy) continue
                            c.drawCircle(hx, hy, hr, solid)
                        }
                    }
                }
            }
            point?.let {
                c.drawCircle(it.x.toFloat(), it.y.toFloat(), 16f, stroke)
                c.drawLine(it.x - 30f, it.y.toFloat(), it.x + 30f, it.y.toFloat(), stroke)
                c.drawLine(it.x.toFloat(), it.y - 30f, it.x.toFloat(), it.y + 30f, stroke)
            }
            val a = sw1
            val b = sw2
            if (a != null && b != null) {
                c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), stroke)
                c.drawCircle(a.x.toFloat(), a.y.toFloat(), 14f, stroke)
                c.drawCircle(b.x.toFloat(), b.y.toFloat(), 6f, solid)
                val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                for (d in doubleArrayOf(2.6, -2.6)) {
                    c.drawLine(
                        b.x.toFloat(), b.y.toFloat(),
                        b.x + (30 * cos(ang + d)).toFloat(), b.y + (30 * sin(ang + d)).toFloat(), stroke,
                    )
                }
            }
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.rawX.toInt()
            val y = e.rawY.toInt()
            when (mode) {
                "POINT" -> if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE ||
                    e.action == MotionEvent.ACTION_UP
                ) point = Point(x, y)
                "SWIPE" -> when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sw1 = Point(x, y); sw2 = null }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        sw2 = Point(x, y)
                        val a = sw1
                        if (e.action == MotionEvent.ACTION_UP && a != null && hypot((x - a.x).toDouble(), (y - a.y).toDouble()) < 30) {
                            sw2 = null // quá ngắn, coi như chưa vuốt
                        }
                    }
                }
                else -> when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        cur = null
                        val hit = hitRegion(x, y)
                        if (hit != null) {
                            // chạm vào cạnh / góc / bên trong vùng đã vẽ: chỉnh hoặc dời vùng đó
                            dragIdx = hit.first; dragMask = hit.second
                            startRegion = rects[hit.first]
                            dragX = x; dragY = y
                        } else {
                            dragIdx = -1; sx = x; sy = y
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragIdx >= 0) applyDrag(x, y)
                        else cur = Region(min(sx, x), min(sy, y), max(sx, x), max(sy, y))
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragIdx >= 0) {
                            applyDrag(x, y)
                            dragIdx = -1
                            startRegion = null
                        } else {
                            cur?.let {
                                if (it.w > 20 && it.h > 20) {
                                    if (mode == "TEMPLATE") rects.clear()
                                    rects.add(it)
                                }
                            }
                        }
                        cur = null
                    }
                }
            }
            invalidate()
            return true
        }
    }
}
