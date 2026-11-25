package com.example.screenwatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.TextView

/** Dữ liệu để vẽ kết quả Thử: các vùng quét, vùng khác biệt, chỗ khớp và dòng tóm tắt. */
class TestViz(
    val title: String,
    val regions: List<RegionViz>,
    val hit: Hit?,
    /** Khung bao chỗ khớp (kiểu hình ảnh); null thì chỉ vẽ dấu thập tại chỗ khớp. */
    val hitBox: Rect?,
    val summary: String,
    /** Ngưỡng của luật (0..1) để tô màu vùng đạt/không đạt. */
    val threshold: Double,
    val isChange: Boolean,
)

/**
 * Lớp phủ toàn màn hình (toạ độ trùng toạ độ màn hình thật) hiện kết quả Thử:
 *  - khung xanh dương = vùng quét, kèm điểm số của vùng;
 *  - ô đỏ = chỗ khác ảnh gốc (đậm hơn = khác nhiều hơn);
 *  - dấu thập/khung xanh lá = chỗ khớp hoặc chỗ thay đổi nhiều nhất.
 * Chạm vào bất kỳ đâu để đóng; tự đóng sau [AUTO_CLOSE_MS].
 */
class TestOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null

    companion object {
        private const val AUTO_CLOSE_MS = 10_000L
    }

    fun show(v: TestViz) {
        hide()
        val d = ctx.resources.displayMetrics.density
        val sd = ctx.resources.displayMetrics.scaledDensity
        val r = FrameLayout(ctx)
        r.addView(Viz(ctx, v, d, sd), FrameLayout.LayoutParams(-1, -1))
        r.addView(
            TextView(ctx).apply {
                text = v.summary + "\n(chạm để đóng)"
                setTextColor(Color.WHITE)
                textSize = 14f
                setBackgroundColor(0xCC000000.toInt())
                val p = (10 * d).toInt()
                setPadding(p, p + (28 * d).toInt(), p, p)
            },
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP),
        )
        r.setOnClickListener { hide() }
        val lp = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = 0
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(r, lp) }.onSuccess {
            root = r
            r.postDelayed({ if (root === r) hide() }, AUTO_CLOSE_MS)
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private class Viz(ctx: Context, private val v: TestViz, private val d: Float, private val sd: Float) : View(ctx) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * sd; color = Color.WHITE }
        private val labelBg = Paint().apply { style = Paint.Style.FILL }

        override fun onDraw(c: Canvas) {
            // ô khác biệt (vẽ trước để khung vùng nằm đè lên trên)
            for (rg in v.regions) for (b in rg.blocks) {
                val strong = b.fill >= v.threshold
                fill.color = if (strong) Color.argb(120 + (b.fill * 100).toInt().coerceAtMost(100), 255, 40, 40)
                else Color.argb(60, 255, 170, 0)
                c.drawRect(b.rect, fill)
            }
            for (rg in v.regions) {
                val ok = rg.score != null && rg.score >= v.threshold
                stroke.color = when {
                    rg.score == null -> 0xFF888888.toInt()
                    ok -> 0xFF00C853.toInt()
                    else -> 0xFF2979FF.toInt()
                }
                c.drawRect(rg.box, stroke)
                val txt = buildString {
                    if (rg.score != null) append("${"%.0f".format(rg.score * 100)}%").append(" / ngưỡng ${"%.0f".format(v.threshold * 100)}%")
                    if (rg.note.isNotEmpty()) { if (isNotEmpty()) append(" • "); append(rg.note) }
                }
                if (txt.isNotEmpty()) {
                    labelBg.color = (stroke.color and 0x00FFFFFF) or 0xE6000000.toInt()
                    val w = label.measureText(txt) + 10 * d
                    val h = label.textSize + 8 * d
                    val x = rg.box.left.toFloat()
                    val y = (rg.box.top - h).coerceAtLeast(0f)
                    c.drawRect(x, y, x + w, y + h, labelBg)
                    c.drawText(txt, x + 5 * d, y + h - 6 * d, label)
                }
            }
            val h = v.hit ?: return
            stroke.color = 0xFF00E676.toInt()
            val hb = v.hitBox
            if (hb != null) c.drawRect(hb, stroke)
            val r = 10 * d
            c.drawCircle(h.x.toFloat(), h.y.toFloat(), r, stroke)
            c.drawLine(h.x - r * 1.7f, h.y.toFloat(), h.x + r * 1.7f, h.y.toFloat(), stroke)
            c.drawLine(h.x.toFloat(), h.y - r * 1.7f, h.x.toFloat(), h.y + r * 1.7f, stroke)
        }
    }
}
