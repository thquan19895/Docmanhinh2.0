package com.example.screenwatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Lớp phủ chỉnh các điểm chạm / giữ / vuốt của một luật ngay trên màn hình thật.
 *  - Hiện sẵn các thao tác đã có dưới dạng số 1, 2, 3 ... (xanh dương = chạm, cam = giữ, xanh lá = vuốt)
 *  - Giữ ngón tay ~0,5 giây ở chỗ trống để THÊM điểm chạm mới
 *  - Chạm vào một điểm để chọn và đổi kiểu / thời gian / số lần / nghỉ giữa các lần, kéo để di chuyển
 */
class ActionEditorOverlay(
    private val ctx: Context,
    private val acts: MutableList<TouchAct>,
    private val onDone: (List<TouchAct>) -> Unit,
    private val onCancel: () -> Unit,
    private val onSave: ((List<TouchAct>) -> Unit)? = null, // lưu ngay vào luật (không đóng màn hình chỉnh), gọi khi bấm Dừng
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Int) = v * density

    private var sel: TouchAct? = null
    private var drawing = true
    private var panelOpen = false   // cửa sổ cài đặt chỉ mở khi giữ 0,8 giây vào một điểm
    private val handle = DragHandle(ctx) { setBarVisible(true) }

    // ---- ghi thao tác: bắt cú chạm / vuốt của người dùng, lưu vào danh sách rồi phát lại cho ứng dụng bên dưới ----
    private var recording = false
    private var recCount = 0
    private var lastEndAt = 0L
    private var dispatching = false // đang phát lại cú vừa ghi cho ứng dụng: bỏ qua mọi cảm ứng trong lúc này (giống AutoClicker)
    private var recBtn: Button? = null
    private var toggleBtn: Button? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val dv = CanvasView(ctx)
    private val bar = LinearLayout(ctx)
    private val panel = LinearLayout(ctx)

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

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    private fun colorOf(kind: String) = when (kind) {
        "longtap" -> 0xFFFB8C00.toInt()
        "swipe" -> 0xFF43A047.toInt()
        else -> 0xFF1E88E5.toInt()
    }

    /* ---------------- hiện / ẩn ---------------- */

    fun show() {
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xE6202020.toInt())
        panel.setPadding(dp(12), dp(6), dp(12), dp(6))
        panel.visibility = View.GONE

        fun mk(t: String, f: () -> Unit) = Button(ctx).apply {
            text = t; isAllCaps = false; textSize = 13f
            maxLines = 1
            minWidth = 0; minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { f() }
        }
        val toggle = mk("✋ Thao tác") {}
        toggleBtn = toggle
        toggle.setOnClickListener {
            if (recording) stopRecording()
            drawing = !drawing
            setDvTouchable(drawing)
            toggle.text = if (drawing) "✋ Thao tác" else "✏ Chỉnh"
            toast(if (drawing) "Đang ở chế độ chỉnh" else "Có thể chuyển sang ứng dụng/màn hình khác, xong bấm ✏ Chỉnh")
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
        }
        // nút chữ (Xong / Thao tác / Chạm) rộng hơn, nút biểu tượng (Ghi / Huỷ / Ẩn) thu nhỏ để "Thao tác" vừa một dòng
        fun add(b: View, w: Float) = row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w))
        add(mk("✔ Xong") { finish() }, 1.2f)
        add(toggle, 1.7f)
        add(mk("＋ Chạm") { addAtCenter() }, 1.3f)
        // Ghi / Huỷ / Ẩn: giữ nguyên tên, chỉ thu hẹp nút (chữ nhỏ hơn một chút, lề hẹp)
        fun small(b: Button) = b.apply { textSize = 12f; setPadding(dp(2), 0, dp(2), 0) }
        val rec = small(mk("⏺ Ghi") { toggleRecording() })
        recBtn = rec
        add(rec, 0.8f)
        add(small(mk("✖ Huỷ") { hide(); onCancel() }), 0.8f)
        add(small(mk("▾ Ẩn") { setBarVisible(false) }), 0.8f)

        bar.orientation = LinearLayout.VERTICAL
        bar.addView(panel)
        bar.addView(row)

        wm.addView(dv, dvLp)
        wm.addView(bar, barLp)
        refreshPanel()
    }

    /** Ẩn / hiện thanh thao tác phía dưới (khi ẩn có nút ☰ nhỏ để hiện lại, giữ và kéo để dời chỗ). */
    private fun setBarVisible(v: Boolean) {
        bar.visibility = if (v) View.VISIBLE else View.GONE
        if (v) handle.hide() else handle.show()
    }

    private fun setDvTouchable(t: Boolean) {
        dvLp.flags = if (t) dvLp.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else dvLp.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(dv, dvLp) }
    }

    /* ---------------- ghi thao tác màn hình ---------------- */

    private fun toggleRecording() = if (recording) stopRecording() else startRecording()

    private fun startRecording() {
        if (AutoClickService.instance == null) {
            toast("Cần bật Dịch vụ trợ năng để ghi (cú chạm được phát lại cho ứng dụng)"); return
        }
        if (!drawing) { // đang ở chế độ thao tác xuyên màn hình: bật lại lớp bắt cảm ứng
            drawing = true
            toggleBtn?.text = "✋ Thao tác"
            setDvTouchable(true)
        }
        sel = null; panelOpen = false; refreshPanel()
        recording = true
        recCount = 0
        lastEndAt = 0L
        dispatching = false
        recBtn?.text = "⏹ Dừng"
        toast("Đang ghi: cứ thao tác trên màn hình như bình thường, xong bấm ⏹ Dừng")
        dv.invalidate()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        dispatching = false
        recBtn?.text = "⏺ Ghi"
        setDvTouchable(drawing)
        if (recCount > 0) onSave?.invoke(acts.toList()) // bấm Dừng cũng lưu thao tác vừa ghi
        toast("Đã ghi và lưu $recCount thao tác")
        dv.invalidate()
    }

    /**
     * Lưu một cú chạm / giữ / vuốt vừa ghi (giống cách AutoClicker ghi) rồi phát lại nó cho ứng dụng bên dưới.
     *  - Giữ cả đường đi của ngón tay (tối đa 40 điểm); di chuyển < 8dp thì coi là một cú chạm.
     *  - Thời gian giữ / vuốt lấy đúng như lúc bấm (tối thiểu 30 ms), không bị chặn trên.
     *  - Thời gian chờ = từ lúc nhấc ngón trước tới lúc chạm xuống lần này (cú đầu tiên = 0).
     */
    private fun recorded(px: List<Float>, py: List<Float>, startAt: Long, endAt: Long) {
        val dur = (endAt - startAt).coerceIn(30L, 60_000L)
        val wait = if (recCount == 0) 0L else (startAt - lastEndAt).coerceAtLeast(0L)
        val (xs, ys) = simplify(px, py)
        val act = if (xs.size == 1) {
            if (dur >= 450) TouchAct("longtap", xs[0].toInt(), ys[0].toInt(), ms = dur, wait = wait, rec = true)
            else TouchAct("tap", xs[0].toInt(), ys[0].toInt(), ms = dur, wait = wait, rec = true)
        } else {
            val n = xs.size
            TouchAct(
                "swipe", xs[0].toInt(), ys[0].toInt(), xs[n - 1].toInt(), ys[n - 1].toInt(), ms = dur, wait = wait,
                pts = FloatArray(n * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }, rec = true,
            )
        }
        acts.add(act)
        recCount++
        lastEndAt = endAt
        dv.invalidate()
        // phát lại cho ứng dụng: tạm cho cảm ứng xuyên qua lớp phủ, chờ phát xong mới bắt cảm ứng lại
        val svc = AutoClickService.instance ?: return
        dispatching = true
        scope.launch {
            setDvTouchable(false)
            delay(80)
            val route = act.pts
            when {
                act.kind == "swipe" && route != null -> svc.path(route, act.ms)
                act.kind == "swipe" -> svc.swipe(act.x1.toFloat(), act.y1.toFloat(), act.x2.toFloat(), act.y2.toFloat(), act.ms)
                else -> svc.tap(act.x1.toFloat(), act.y1.toFloat(), act.ms)
            }
            dispatching = false
            if (recording && drawing) setDvTouchable(true)
        }
    }

    /** Gộp chuyển động nhỏ thành một lần chạm và giảm số điểm của đường vuốt (tối đa 40 điểm). */
    private fun simplify(px: List<Float>, py: List<Float>): Pair<FloatArray, FloatArray> {
        val x0 = px[0]
        val y0 = py[0]
        var maxD = 0f
        for (i in px.indices) {
            val d = hypot((px[i] - x0).toDouble(), (py[i] - y0).toDouble()).toFloat()
            if (d > maxD) maxD = d
        }
        if (maxD < dpf(8)) return floatArrayOf(x0) to floatArrayOf(y0)
        val n = px.size
        val maxPts = 40
        val idx: List<Int> = if (n <= maxPts) (0 until n).toList() else (0 until maxPts).map { it * (n - 1) / (maxPts - 1) }
        return FloatArray(idx.size) { px[idx[it]] } to FloatArray(idx.size) { py[idx[it]] }
    }

    fun hide() {
        recording = false
        scope.cancel()
        handle.hide()
        runCatching { wm.removeView(dv) }
        runCatching { wm.removeView(bar) }
    }

    private fun finish() {
        hide()
        onDone(acts.toList())
    }

    private fun addAtCenter() {
        val (w, h) = screen()
        val a = TouchAct("tap", w / 2 + (acts.size % 5) * dp(30) - dp(60), h / 2, size = 20)
        acts.add(a)
        sel = a
        refreshPanel()
    }

    /* ---------------- bảng cài đặt của điểm đang chọn ---------------- */

    private fun chip(t: String, selected: Boolean, f: () -> Unit) = TextView(ctx).apply {
        text = t
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        textSize = 15f
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            setColor(if (selected) 0xFF1976D2.toInt() else 0x44FFFFFF)
            cornerRadius = dpf(8)
        }
        setOnClickListener { f() }
    }

    private fun stepper(label: String, value: Long, step: (Long) -> Long, lo: Long, hi: Long, set: (Long) -> Unit): View {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        r.addView(
            TextView(ctx).apply { text = label; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        r.addView(chip("−", false) { set((value - step(value - 1)).coerceIn(lo, hi)); refreshPanel() })
        r.addView(TextView(ctx).apply {
            text = value.toString(); setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = dp(72)
        })
        r.addView(chip("+", false) { set((value + step(value)).coerceIn(lo, hi)); refreshPanel() })
        return r
    }

    private fun setKind(a: TouchAct, k: String) {
        val old = a.kind
        if (old == k) return
        if (a.ms == MacroEdit.defaultMs(old)) a.ms = MacroEdit.defaultMs(k)
        a.kind = k
        a.pts = null
        if (k == "swipe") {
            a.x2 = a.x1
            a.y2 = if (a.y1 - dp(150) > 0) a.y1 - dp(150) else a.y1 + dp(150)
        }
        refreshPanel()
    }

    private fun refreshPanel() {
        panel.removeAllViews()
        val a = sel
        if (a == null || !acts.contains(a)) {
            sel = null
            panelOpen = false
        }
        if (!panelOpen || a == null) {
            panel.visibility = View.GONE
            dv.invalidate()
            return
        }
        panel.visibility = View.VISIBLE
        val no = acts.indexOf(a) + 1
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(ctx).apply { text = "Điểm $no"; setTextColor(Color.WHITE); textSize = 16f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(chip("✕ Đóng", false) { panelOpen = false; refreshPanel() })
        panel.addView(head)
        val kinds = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, name) in listOf("tap" to "Chạm", "longtap" to "Chạm giữ", "swipe" to "Vuốt")) {
            kinds.addView(
                chip(name, a.kind == k) { setKind(a, k) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
        }
        panel.addView(kinds)
        val msLabel = when (a.kind) {
            "longtap" -> "Thời gian giữ (ms)"
            "swipe" -> "Thời gian vuốt (ms)"
            else -> "Thời gian chạm (ms)"
        }
        panel.addView(
            stepper(msLabel, a.ms, { v -> if (v < 100) 10L else if (v < 1000) 50L else 100L }, 1L, 60_000L) { a.ms = it },
        )
        panel.addView(
            stepper("Số lần", a.times.toLong(), { v -> if (v < 10) 1L else if (v < 100) 5L else 50L }, 1L, 100_000L) {
                a.times = it.toInt()
            },
        )
        if (a.times > 1) {
            panel.addView(
                stepper("Nghỉ giữa các lần (ms)", a.gap, { v -> if (v < 1000) 50L else 100L }, 0L, 600_000L) { a.gap = it },
            )
        }
        panel.addView(
            stepper("Chờ rồi chạm (ms)", a.wait, { v -> if (v < 1000) 50L else 100L }, 0L, 600_000L) { a.wait = it },
        )
        panel.addView(
            stepper("Kích thước nút (dp)", a.size.toLong(), { _ -> 4L }, 16L, 120L) { a.size = it.toInt() },
        )
        panel.addView(
            chip("🗑 Xoá điểm này", false) { acts.remove(a); sel = null; refreshPanel() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) },
        )
        dv.invalidate()
    }

    /* ---------------- vùng vẽ + cảm ứng ---------------- */

    private inner class CanvasView(c: Context) : View(c) {
        private val dim = Paint().apply { color = 0x33000000 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        private var dragAct: TouchAct? = null
        private var dragEnd = false
        private var downX = 0f
        private var downY = 0f
        private var offX = 0f
        private var offY = 0f
        private var moved = false
        private var longFired = false

        private val longRun = Runnable {
            if (dragAct == null && !moved) {
                longFired = true
                val a = TouchAct("tap", downX.toInt(), downY.toInt(), size = 20)
                acts.add(a)
                sel = a
                dragAct = a
                dragEnd = false
                offX = 0f; offY = 0f
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                refreshPanel()
            }
        }

        private val openRun = Runnable {
            val a = dragAct
            if (a != null && !moved) {
                sel = a
                panelOpen = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                refreshPanel()
            }
        }

        private val recTint = Paint().apply { color = 0x22FF0000 }

        override fun onDraw(c: Canvas) {
            if (recording) { // giống AutoClicker: chỉ phủ đỏ nhạt, không hiện thao tác đã ghi, không viền, không nhãn
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), recTint)
                return
            }
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            // chỉ vẽ điểm thêm tay bằng ＋ Chạm; thao tác ghi bằng nút Ghi thì không hiện
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            var no = 0
            for (a in acts) {
                if (a.rec) continue
                no++
                drawAct(c, a, no, a === sel)
            }
            c.restore()
        }

        private fun drawAct(c: Canvas, a: TouchAct, no: Int, selected: Boolean) {
            val col = colorOf(a.kind)
            val x1 = a.x1.toFloat()
            val y1 = a.y1.toFloat()
            val r = dpf(a.size) / 2f
            if (a.kind == "swipe") {
                val x2 = a.x2.toFloat()
                val y2 = a.y2.toFloat()
                ring.color = col; ring.strokeWidth = dpf(3)
                val route = a.pts
                if (route != null && route.size >= 6 && route.size % 2 == 0 &&
                    route[0].toInt() == a.x1 && route[1].toInt() == a.y1 &&
                    route[route.size - 2].toInt() == a.x2 && route[route.size - 1].toInt() == a.y2
                ) {
                    for (i in 0 until route.size - 2 step 2) c.drawLine(route[i], route[i + 1], route[i + 2], route[i + 3], ring)
                } else {
                    c.drawLine(x1, y1, x2, y2, ring)
                }
                val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                for (s in doubleArrayOf(2.6, -2.6)) {
                    c.drawLine(
                        x2, y2,
                        x2 + (r * 1.4f * cos(ang + s)).toFloat(), y2 + (r * 1.4f * sin(ang + s)).toFloat(), ring,
                    )
                }
                fillP.color = col; fillP.alpha = 230
                c.drawCircle(x2, y2, r * 0.6f, fillP)
            }
            fillP.color = col; fillP.alpha = 215
            c.drawCircle(x1, y1, r, fillP)
            if (a.kind == "longtap") {
                ring.color = col; ring.strokeWidth = dpf(3)
                c.drawCircle(x1, y1, r + dpf(7), ring)
            }
            if (selected) {
                ring.color = Color.WHITE; ring.strokeWidth = dpf(3)
                c.drawCircle(x1, y1, r + dpf(13), ring)
            }
            txt.textSize = r * 0.8f
            c.drawText(no.toString(), x1, y1 + txt.textSize / 3, txt)
            small.textSize = dpf(12)
            val info = (if (a.times > 1) "${a.ms}ms ×${a.times}" else "${a.ms}ms") +
                (if (a.wait != 120L) " ⏱${a.wait}" else "")
            c.drawText(info, x1, y1 + r + dpf(18), small)
        }

        private fun hitTest(x: Float, y: Float): Pair<TouchAct, Boolean>? {
            for (a in acts.asReversed()) {
                if (a.rec) continue
                val rad = max(dpf(30), dpf(a.size) / 2f + dpf(6))
                if (a.kind == "swipe" && hypot(x - a.x2, y - a.y2) < rad) return a to true
                if (hypot(x - a.x1, y - a.y1) < rad) return a to false
            }
            return null
        }

        private var recDown = false
        private var recT0 = 0L
        private val recX = ArrayList<Float>()
        private val recY = ArrayList<Float>()

        private fun recordTouch(e: MotionEvent): Boolean {
            if (dispatching) return true // đang phát lại cú trước: bỏ qua
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    recDown = true
                    recX.clear(); recY.clear()
                    recX.add(e.rawX); recY.add(e.rawY)
                    recT0 = e.eventTime
                }
                MotionEvent.ACTION_MOVE -> if (recDown) { recX.add(e.rawX); recY.add(e.rawY) }
                MotionEvent.ACTION_UP -> if (recDown) {
                    recDown = false
                    recX.add(e.rawX); recY.add(e.rawY)
                    recorded(ArrayList(recX), ArrayList(recY), recT0, e.eventTime)
                    recX.clear(); recY.clear()
                }
                MotionEvent.ACTION_CANCEL -> { recDown = false; recX.clear(); recY.clear() }
            }
            return true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (recording) return recordTouch(e)
            val x = e.rawX
            val y = e.rawY
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; moved = false; longFired = false; dragAct = null
                    val hit = hitTest(x, y)
                    if (hit != null) {
                        val a = hit.first
                        dragAct = a
                        dragEnd = hit.second
                        sel = a
                        offX = (if (dragEnd) a.x2 else a.x1) - x
                        offY = (if (dragEnd) a.y2 else a.y1) - y
                        refreshPanel()
                        postDelayed(openRun, 800)
                    } else {
                        postDelayed(longRun, 500)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(x - downX, y - downY) > dpf(8)) {
                        moved = true
                        removeCallbacks(longRun)
                        removeCallbacks(openRun)
                    }
                    val a = dragAct
                    if (a != null && (moved || longFired)) {
                        val (w, h) = screen()
                        val nx = (x + offX).toInt().coerceIn(0, w)
                        val ny = (y + offY).toInt().coerceIn(0, h)
                        if (dragEnd) { a.x2 = nx; a.y2 = ny } else { a.x1 = nx; a.y1 = ny }
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longRun)
                    removeCallbacks(openRun)
                    if (dragAct == null && !longFired && !moved) {
                        sel = null
                        panelOpen = false
                        refreshPanel() // chạm vào chỗ trống: bỏ chọn và đóng cửa sổ cài đặt
                    }
                    dragAct = null
                    invalidate()
                }
            }
            return true
        }
    }
}
