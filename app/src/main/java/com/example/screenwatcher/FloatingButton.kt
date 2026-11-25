package com.example.screenwatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/**
 * Nút nổi kéo thả được. Chạm vào nút để hiện biểu tượng đồng hồ hẹn giờ + nút Run / Stop bên cạnh.
 * Giữ biểu tượng đồng hồ 0,5 giây để mở/đóng bảng cài đặt hẹn giờ (tự bắt đầu / tự dừng lúc / tự dừng sau N phút).
 * Giữ nút SW 0,5 giây để mở/đóng bảng cài đặt: bật/tắt từng luật, chọn & chạy bộ luật đã lưu, thêm luật mới, mở app, ẩn nút.
 * Màu nút: xanh = đang theo dõi, xám = đang tắt.
 */
class FloatingButton(
    private val ctx: Context,
    private val onMonitor: (Boolean) -> Unit,
    private val onAddRule: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onHideButton: () -> Unit,
    private val onTestRule: (String) -> Unit,
    private val onRunProfile: (String) -> Unit,
    private val onMergeProfile: (String) -> Unit,
    private val onRecord: () -> Unit,
) {
    private companion object {
        const val BUBBLE = 48   // dp
        const val ROW_W = 190   // dp, nút + đồng hồ + nút Run/Stop
        const val PANEL_W = 250 // dp
        const val SCHED_W = 250 // dp, bảng hẹn giờ
        const val SCHED_H = 290 // dp, ước lượng chiều cao bảng hẹn giờ
        const val PANEL_H = 380 // dp, ước lượng chiều cao tối đa của bảng
        const val HOLD_MS = 500L // thời gian giữ nút SW để mở bảng
    }

    private val c = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val bubble = TextView(c)
    private val runBtn = TextView(c)
    private val clockBtn = ClockIcon(c)
    private val schedPanel = LinearLayout(c)
    private val topRow = LinearLayout(c)
    private val panel = LinearLayout(c)

    private val lp = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var added = false
    private var expanded = false
    private var showProfiles = false
    private var schedOpen = false
    private val hideRun = Runnable { showRun(false) }

    init {
        bubble.apply {
            text = "SW"; textSize = 14f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(BUBBLE), dp(BUBBLE))
            setOnTouchListener(DragListener())
        }
        runBtn.apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                .apply { leftMargin = dp(4) }
            visibility = View.GONE
            setOnClickListener {
                val start = !AppState.monitoring
                if (start && !AppState.projectionReady) {
                    Toast.makeText(ctx, "Chưa cấp quyền chụp màn hình", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onMonitor(start)
                showRun(false)
            }
        }
        clockBtn.apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
            visibility = View.GONE
            setOnTouchListener(ClockHold())
        }
        topRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubble)
            addView(clockBtn)
            addView(runBtn)
        }
        schedPanel.apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xF2202020.toInt(), dp(12).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(SCHED_W), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            visibility = View.GONE
        }
        panel.apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xF2202020.toInt(), dp(12).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(PANEL_W), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            visibility = View.GONE
        }
        root.addView(topRow)
        root.addView(panel)
        root.addView(schedPanel)
        paintBubble()
    }

    private fun bg(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun clamp() {
        val (w, h) = screen()
        val bw = when {
            expanded -> dp(max(PANEL_W, ROW_W))
            schedOpen -> dp(max(SCHED_W, ROW_W))
            else -> dp(ROW_W)
        }
        val bh = when {
            expanded -> dp(BUBBLE + 6 + PANEL_H)
            schedOpen -> dp(BUBBLE + 6 + SCHED_H)
            else -> dp(BUBBLE)
        }
        lp.x = lp.x.coerceIn(0, max(0, w - bw))
        lp.y = lp.y.coerceIn(0, max(0, h - bh))
    }

    private fun update() {
        if (added) runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun paintBubble() {
        val col = if (AppState.monitoring) 0xE02E7D32.toInt() else 0xE0616161.toInt()
        bubble.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
        if (runBtn.visibility == View.VISIBLE) paintRun()
        paintClock()
    }

    private fun schedActive() =
        RuleStore.schedStartOn(ctx) || RuleStore.schedStopOn(ctx) || RuleStore.autoStopMin(ctx) > 0

    /** Đồng hồ xanh dương = đang có hẹn giờ bật hoặc bảng hẹn giờ đang mở; xám = chưa hẹn giờ. */
    private fun paintClock() {
        clockBtn.tint = if (schedActive() || schedOpen) 0xE01565C0.toInt() else 0xE0616161.toInt()
    }

    private fun paintRun() {
        val running = AppState.monitoring
        runBtn.text = if (running) "■ Stop" else "▶ Run"
        runBtn.background = bg(if (running) 0xE0C62828.toInt() else 0xE02E7D32.toInt(), dp(20).toFloat())
    }

    private fun armHide() {
        main.removeCallbacks(hideRun)
        main.postDelayed(hideRun, 4000)
    }

    /** Hiện / ẩn đồng hồ hẹn giờ + nút Run (đang dừng) hoặc Stop (đang chạy) cạnh nút nổi; tự ẩn sau 4 giây. */
    private fun showRun(show: Boolean) {
        main.removeCallbacks(hideRun)
        val vis = if (show) View.VISIBLE else View.GONE
        runBtn.visibility = vis
        clockBtn.visibility = vis
        if (show) {
            paintRun()
            paintClock()
            if (!schedOpen) armHide()
        } else closeSched()
        clamp()
        update()
    }

    private fun closeSched() {
        if (!schedOpen) return
        schedOpen = false
        schedPanel.visibility = View.GONE
        paintClock()
    }

    /** Giữ biểu tượng đồng hồ 0,5 giây: mở / đóng bảng cài đặt hẹn giờ. */
    private fun toggleSched() {
        if (schedOpen) {
            closeSched()
            armHide()
        } else {
            expanded = false
            showProfiles = false
            panel.visibility = View.GONE
            main.removeCallbacks(hideRun)
            schedOpen = true
            buildSchedPanel()
            schedPanel.visibility = View.VISIBLE
            paintClock()
        }
        clamp()
        update()
    }

    /* ---------------- hiện / ẩn ---------------- */

    fun show() {
        if (added) return
        val (x, y) = RuleStore.bubblePos(ctx)
        lp.x = x; lp.y = y
        expanded = false
        panel.visibility = View.GONE
        runBtn.visibility = View.GONE
        clockBtn.visibility = View.GONE
        schedOpen = false
        schedPanel.visibility = View.GONE
        clamp()
        paintBubble()
        added = runCatching { wm.addView(root, lp) }.isSuccess
    }

    fun hide() {
        if (!added) return
        main.removeCallbacks(hideRun)
        runCatching { wm.removeView(root) }
        added = false
        expanded = false
        schedOpen = false
        schedPanel.visibility = View.GONE
        panel.visibility = View.GONE
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        showProfiles = false
        panel.visibility = View.GONE
        update()
    }

    /** Cập nhật màu nút và bảng (gọi khi trạng thái theo dõi thay đổi). */
    fun refresh() {
        main.post {
            if (!added) return@post
            paintBubble()
            if (expanded) buildPanel()
        }
    }

    private fun toggle() {
        expanded = !expanded
        showProfiles = false
        if (expanded) {
            closeSched()
            buildPanel()
            panel.visibility = View.VISIBLE
        } else panel.visibility = View.GONE
        clamp()
        update()
        RuleStore.setBubblePos(ctx, lp.x, lp.y)
    }

    /* ---------------- bảng nhanh ---------------- */

    private fun action(t: String, f: () -> Unit) = TextView(c).apply {
        text = t; setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 13f
        setPadding(dp(6), dp(10), dp(6), dp(10))
        background = bg(0x33FFFFFF, dp(8).toFloat())
        setOnClickListener { f() }
    }

    /** Danh sách bộ luật đã lưu: chạm một tên để nạp (không chạy luôn, bấm Run để chạy). */
    private fun buildProfilePanel() {
        panel.addView(TextView(c).apply {
            text = "Chạm tên: nạp thay thế (chưa chạy). Chạm ➕: gộp thêm để canh nhiều app cùng lúc."; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f
            setPadding(0, 0, 0, dp(4))
        })
        val profiles = ProfileStore.list(ctx)
        val activeIds = RuleStore.activeProfiles(ctx)
        panel.addView(activeLine())
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (profiles.isEmpty()) {
            list.addView(TextView(c).apply {
                text = "(chưa lưu bộ luật nào — lưu trong app)"; setTextColor(0xFFBBBBBB.toInt())
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (p in profiles) {
            val prow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            prow.addView(
                action((if (p.id in activeIds) "✔ " else "📂 ") + "${p.name} (${p.ruleCount})") { onRunProfile(p.id) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            prow.addView(
                action(if (p.id in activeIds) "✔" else "➕") { onMergeProfile(p.id) },
                LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) },
            )
            list.addView(
                prow,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(4) },
            )
        }
        panel.addView(
            ScrollView(c).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (profiles.size > 5) dp(220) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        row.addView(
            action("← Quay lại") { showProfiles = false; buildPanel(); clamp(); update() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
        )
        panel.addView(row)
    }

    /** Dòng "Đang dùng: A + B + C" — liệt kê tất cả bộ luật đang nạp / gộp. */
    private fun activeLine() = TextView(c).apply {
        val names = ProfileStore.activeNames(ctx)
        text = if (names.isEmpty()) "Đang dùng: (luật chưa lưu thành bộ)" else "Đang dùng: " + names.joinToString(" + ")
        setTextColor(0xFF81C784.toInt()); textSize = 12f
        setPadding(0, 0, 0, dp(4))
    }

    private fun buildPanel() {
        panel.removeAllViews()
        if (showProfiles) { buildProfilePanel(); return }
        panel.addView(activeLine())

        if (AppState.monitoring && AppState.seqStatus.isNotEmpty()) {
            panel.addView(TextView(c).apply {
                text = AppState.seqStatus; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f
            })
        }

        val rules = RuleStore.load(ctx)
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (rules.isEmpty()) {
            list.addView(TextView(c).apply {
                text = "(chưa có luật nào)"; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (r in rules) {
            val line = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(
                CheckBox(c).apply {
                    text = r.name; setTextColor(Color.WHITE); isChecked = r.enabled
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setOnCheckedChangeListener { _, on ->
                        RuleStore.get(ctx, r.id)?.let { it.enabled = on; RuleStore.upsert(ctx, it) }
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            // Thử luật nhanh: thu bảng + nút nổi lại, quét ngay rồi báo kết quả
            line.addView(
                action("Thử") { onTestRule(r.id) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(6) },
            )
            list.addView(line)
        }
        panel.addView(
            ScrollView(c).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (rules.size > 5) dp(220) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        fun newRow() = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        fun LinearLayout.add(t: String, f: () -> Unit) = addView(
            action(t, f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
        )
        val row1 = newRow()
        row1.add("📁 Bộ luật") { showProfiles = true; buildPanel(); clamp(); update() }
        row1.add("+ Luật") { collapse(); onAddRule() }
        row1.add("● Ghi") { collapse(); onRecord() }
        panel.addView(row1)
        val row2 = newRow().apply { setPadding(0, dp(2), 0, 0) }
        row2.add("Mở app") { collapse(); onOpenApp() }
        row2.add("Ẩn nút") { onHideButton() }
        row2.add("✕") { collapse() }
        panel.addView(row2)
    }

    /* ---------------- bảng hẹn giờ ---------------- */

    private fun accel(n: Int) = if (n < 12) 1 else 5

    /** Nút +/−: chạm 1 lần = 1 bước, giữ = lặp lại (nhanh dần). step(n) nhận số lần đã lặp. */
    private fun stepBtn(t: String, step: (Int) -> Unit) = TextView(c).apply {
        text = t; setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = bg(0x33FFFFFF, dp(8).toFloat())
        minWidth = dp(34); setPadding(dp(6), dp(8), dp(6), dp(8))
        var n = 0
        val rep = object : Runnable {
            override fun run() { n++; step(n); main.postDelayed(this, 90) }
        }
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { n = 0; step(0); main.postDelayed(rep, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(rep)
            }
            true
        }
    }

    private fun lbl(t: String, size: Float = 14f) = TextView(c).apply {
        text = t; setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER
    }

    private fun cell(w: Int, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, wt: Float = 0f) =
        LinearLayout.LayoutParams(w, h, wt).apply { setMargins(dp(2), 0, dp(2), 0) }

    /** Một khối hẹn giờ: ô tích bật/tắt + bộ chỉnh giờ : phút. Chỉnh giờ sẽ tự bật ô tích. */
    private fun schedBlock(label: String, on: Boolean, min: Int, save: (Boolean, Int) -> Unit): LinearLayout {
        var curOn = on
        var curMin = min
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        val cb = CheckBox(c).apply { text = label; setTextColor(Color.WHITE); isChecked = on }
        val hh = lbl("", 18f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; minWidth = dp(30) }
        val mm = lbl("", 18f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; minWidth = dp(30) }
        fun refreshTxt() { hh.text = "%02d".format(curMin / 60); mm.text = "%02d".format(curMin % 60) }
        fun commit() {
            refreshTxt()
            if (!curOn) cb.isChecked = true else save(curOn, curMin)   // bật ô tích sẽ tự lưu
        }
        cb.setOnCheckedChangeListener { _, v -> curOn = v; save(curOn, curMin) }
        refreshTxt()
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        box.addView(cb)
        box.addView(row)
        fun bumpH(d: Int) { curMin = ((curMin / 60 + d + 24) % 24) * 60 + curMin % 60; commit() }
        fun bumpM(d: Int) { curMin = (curMin / 60) * 60 + (curMin % 60 + d + 60) % 60; commit() }
        row.addView(stepBtn("−") { bumpH(-1) }, cell(dp(34)))
        row.addView(hh, cell(LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(stepBtn("+") { bumpH(1) }, cell(dp(34)))
        row.addView(lbl(":", 18f), cell(LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(stepBtn("−") { n -> bumpM(-accel(n)) }, cell(dp(34)))
        row.addView(mm, cell(LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(stepBtn("+") { n -> bumpM(accel(n)) }, cell(dp(34)))
        return box
    }

    private fun buildSchedPanel() {
        schedPanel.removeAllViews()
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(
            lbl("Hẹn giờ (lặp mỗi ngày)").apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.START
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(action("✕") { toggleSched() }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
        schedPanel.addView(head)

        schedPanel.addView(schedBlock("Tự bắt đầu lúc", RuleStore.schedStartOn(ctx), RuleStore.schedStartMin(ctx)) { on, m ->
            RuleStore.setSchedStart(ctx, on, m); paintClock()
        })
        schedPanel.addView(schedBlock("Tự dừng lúc", RuleStore.schedStopOn(ctx), RuleStore.schedStopMin(ctx)) { on, m ->
            RuleStore.setSchedStop(ctx, on, m); paintClock()
        })

        // tự dừng sau N phút
        var cur = RuleStore.autoStopMin(ctx)
        val v = lbl("", 15f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; minWidth = dp(64) }
        fun showAs() { v.text = if (cur == 0) "Không" else "$cur phút" }
        fun bumpA(d: Int) {
            cur = (cur + d).coerceIn(0, 1440)
            RuleStore.setAutoStopMin(ctx, cur); showAs(); paintClock()
        }
        showAs()
        val asRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0)
        }
        asRow.addView(lbl("Tự dừng sau").apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        asRow.addView(stepBtn("−") { n -> bumpA(-accel(n)) }, cell(dp(34)))
        asRow.addView(v, cell(LinearLayout.LayoutParams.WRAP_CONTENT))
        asRow.addView(stepBtn("+") { n -> bumpA(accel(n)) }, cell(dp(34)))
        schedPanel.addView(asRow)

        schedPanel.addView(TextView(c).apply {
            text = "Cần dịch vụ đang chạy (thông báo còn hiện) và đã cấp quyền chụp màn hình. Tự bắt đầu không chạy được nếu máy khởi động lại."
            setTextColor(0xFFBBBBBB.toInt()); textSize = 11f; setPadding(0, dp(8), 0, 0)
        })
    }

    /* ---------------- kéo thả / chạm ---------------- */

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var fired = false

        // giữ 0,5 giây (không kéo) -> mở / đóng bảng cài đặt
        private val longRun = Runnable {
            if (!moved) {
                fired = true
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showRun(false)
                toggle()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; fired = false
                    main.postDelayed(longRun, HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        moved = true
                        main.removeCallbacks(longRun)
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        clamp(); update()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longRun)
                    if (moved) RuleStore.setBubblePos(ctx, lp.x, lp.y)
                    else if (!fired && e.action == MotionEvent.ACTION_UP) showRun(runBtn.visibility != View.VISIBLE)
                }
            }
            return true
        }
    }

    /** Chạm: nhắc cách dùng. Giữ 0,5 giây: mở / đóng bảng hẹn giờ. */
    private inner class ClockHold : View.OnTouchListener {
        private var fired = false
        private val longRun = Runnable {
            fired = true
            clockBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggleSched()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    main.removeCallbacks(hideRun)
                    main.postDelayed(longRun, HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longRun)
                    if (!fired && e.action == MotionEvent.ACTION_UP) {
                        Toast.makeText(ctx, "Giữ 0,5 giây để cài đặt hẹn giờ", Toast.LENGTH_SHORT).show()
                    }
                    if (!schedOpen && runBtn.visibility == View.VISIBLE) armHide()
                }
            }
            return true
        }
    }

    /** Biểu tượng đồng hồ vẽ bằng nét mỏng trên nền tròn. */
    private class ClockIcon(ctx: Context) : View(ctx) {
        var tint: Int = 0xE0616161.toInt()
            set(v) { field = v; invalidate() }
        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            bgP.color = tint
            c.drawCircle(cx, cy, width / 2f, bgP)
            fg.strokeWidth = 1.8f * d
            val r = width * 0.27f
            c.drawCircle(cx, cy, r, fg)
            c.drawLine(cx, cy, cx, cy - r * 0.65f, fg)            // kim phút
            c.drawLine(cx, cy, cx + r * 0.5f, cy + r * 0.25f, fg) // kim giờ
        }
    }
}
