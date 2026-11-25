package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot

/** Một bước đã ghi: thao tác của người dùng + ảnh mẫu cắt quanh điểm chạm + đánh giá chất lượng ảnh. */
class RecStep(
    val kind: String,        // "tap" | "longtap" | "swipe"
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val durMs: Long,
    val gapBeforeMs: Long,   // thời gian nghỉ trước bước này
    val path: IntArray,      // x0,y0,x1,y1,... (chỉ có ý nghĩa với swipe)
) {
    @Volatile var ready = false          // đã phân tích xong ảnh mẫu
    @Volatile var crop: Bitmap? = null   // ảnh mẫu
    @Volatile var thumb: Bitmap? = null  // hình thu nhỏ để xem trước
    @Volatile var region: Region? = null       // vị trí ảnh mẫu trên màn hình
    @Volatile var searchRegion: Region? = null // vùng sẽ quét khi chạy
    @Volatile var level = 0              // 0 = tốt, 1 = yếu, 2 = rất yếu
    @Volatile var reasons: List<String> = emptyList()
    @Volatile var useImage = true        // true: nhận diện bằng ảnh; false: chạm toạ độ cố định
}

object RecState {
    val steps = java.util.concurrent.CopyOnWriteArrayList<RecStep>()
    @Volatile var recording = false

    fun clear() {
        steps.forEach { s -> s.crop?.recycle(); s.thumb?.recycle() }
        steps.clear()
    }
}

/** Phân tích ảnh mẫu: đủ chi tiết không, có hình giống ở chỗ khác trong vùng quét không. */
object StepAnalyzer {
    private fun lum(p: Int): Int = (((p shr 16) and 255) * 30 + ((p shr 8) and 255) * 59 + (p and 255) * 11) / 100

    fun analyze(step: RecStep, frame: Bitmap, sizePx: Int) {
        val fw = frame.width
        val fh = frame.height
        val size = minOf(sizePx, fw, fh)
        if (size < 16) return
        val l = (step.x1 - size / 2).coerceIn(0, fw - size)
        val t = (step.y1 - size / 2).coerceIn(0, fh - size)
        val crop = Bitmap.createBitmap(frame, l, t, size, size)
        val reasons = ArrayList<String>()
        var level = 0

        // 1) Độ chi tiết: độ lệch chuẩn độ sáng + tỉ lệ điểm có cạnh
        val px = IntArray(size * size)
        crop.getPixels(px, 0, size, 0, 0, size, size)
        val ls = IntArray(px.size) { lum(px[it]) }
        var sum = 0.0
        for (v in ls) sum += v
        val mean = sum / ls.size
        var sq = 0.0
        for (v in ls) sq += (v - mean) * (v - mean)
        val std = Math.sqrt(sq / ls.size)
        var edges = 0
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val i = y * size + x
            if (abs(ls[i + 1] - ls[i]) > 16 || abs(ls[i + size] - ls[i]) > 16) edges++
        }
        val edgeFrac = edges.toDouble() / ((size - 1) * (size - 1))
        if (std < 10 || edgeFrac < 0.02) {
            level = 2; reasons.add("Gần như đơn sắc, rất dễ nhận nhầm")
        } else if (std < 22 || edgeFrac < 0.06) {
            level = 1; reasons.add("Ít chi tiết, có thể nhận nhầm")
        }

        // 2) Có hình giống ở chỗ khác trong vùng sẽ quét không (che chính ảnh mẫu rồi tìm lại)
        val e = size * 2
        val sl = (l - e).coerceAtLeast(0)
        val st = (t - e).coerceAtLeast(0)
        val sr = (l + size + e).coerceAtMost(fw)
        val sb = (t + size + e).coerceAtMost(fh)
        if (level < 2) {
            val sub = Bitmap.createBitmap(frame, sl, st, sr - sl, sb - st).copy(Bitmap.Config.ARGB_8888, true)
            if (sub != null) {
                Canvas(sub).drawRect(
                    (l - sl).toFloat(), (t - st).toFloat(), (l - sl + size).toFloat(), (t - st + size).toFloat(),
                    Paint().apply { color = 0xFF808080.toInt() },
                )
                val hit = try { TemplateMatcher.find(sub, crop, 0.85) } catch (ex: Exception) { null }
                sub.recycle()
                if (hit != null) {
                    if (hit.score >= 0.93) level = 2 else if (level < 1) level = 1
                    reasons.add("Có hình giống ở chỗ khác trong vùng quét (độ giống ${"%.2f".format(hit.score)})")
                }
            }
        }
        if (reasons.isEmpty()) reasons.add("Ảnh rõ, đủ chi tiết")

        // 3) Hình thu nhỏ: khung ngữ cảnh 3x, đánh dấu ảnh mẫu (đỏ) và điểm chạm
        val ctx = minOf(size * 3, fw, fh)
        val tl = (step.x1 - ctx / 2).coerceIn(0, fw - ctx)
        val tt = (step.y1 - ctx / 2).coerceIn(0, fh - ctx)
        val raw = Bitmap.createBitmap(frame, tl, tt, ctx, ctx)
        val out = 240
        val scaled = Bitmap.createScaledBitmap(raw, out, out, true).copy(Bitmap.Config.ARGB_8888, true)
        if (raw !== frame) raw.recycle()
        if (scaled != null) {
            val k = out.toFloat() / ctx
            val c = Canvas(scaled)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.RED }
            c.drawRect(RectF((l - tl) * k, (t - tt) * k, (l - tl + size) * k, (t - tt + size) * k), stroke)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF1E88E5.toInt() }
            if (step.kind == "swipe") {
                val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF43A047.toInt() }
                c.drawLine((step.x1 - tl) * k, (step.y1 - tt) * k, (step.x2 - tl) * k, (step.y2 - tt) * k, ln)
            }
            c.drawCircle((step.x1 - tl) * k, (step.y1 - tt) * k, 7f, dot)
            step.thumb = scaled
        }

        step.crop = crop
        step.region = Region(l, t, l + size, t + size)
        step.searchRegion = Region(sl, st, sr, sb)
        step.level = level
        step.reasons = reasons
        step.useImage = level < 2
    }
}

/** Lớp phủ ghi thao tác: bắt thao tác của người dùng, chụp ảnh quanh điểm chạm, rồi chạm thay vào app bên dưới. */
class RecordOverlay(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val snapshot: () -> Bitmap?,
    private val onStop: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    private val cropPx = (44 * d).toInt().coerceIn(80, 200)
    private var view: RecView? = null
    private var lp: WindowManager.LayoutParams? = null

    @Volatile private var busy = false
    private var lastUpAt = 0L
    private var downAt = 0L
    private var downFrame: Bitmap? = null
    private var sx = 0f
    private var sy = 0f
    private val pts = ArrayList<Float>()
    private var stopTouch = false

    fun show() {
        val v = RecView()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (runCatching { wm.addView(v, p) }.isSuccess) { view = v; lp = p }
        RecState.recording = true
    }

    fun hide() {
        RecState.recording = false
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun setTouchable(on: Boolean) {
        val v = view ?: return
        val p = lp ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun finishGesture(ex: Float, ey: Float) {
        val now = SystemClock.uptimeMillis()
        val dur = now - downAt
        var far = 0f
        var i = 0
        while (i + 1 < pts.size) { far = maxOf(far, hypot(pts[i] - sx, pts[i + 1] - sy)); i += 2 }
        far = maxOf(far, hypot(ex - sx, ey - sy))
        val kind = when {
            far > 24 * d -> "swipe"
            dur >= 450 -> "longtap"
            else -> "tap"
        }
        // Rút gọn đường vuốt còn tối đa ~16 điểm
        val route = ArrayList<Float>()
        route.add(sx); route.add(sy)
        val n = pts.size / 2
        val stepN = maxOf(1, n / 14)
        var k = stepN
        while (k < n) { route.add(pts[k * 2]); route.add(pts[k * 2 + 1]); k += stepN }
        route.add(ex); route.add(ey)

        val gap = if (lastUpAt == 0L) 0L else (downAt - lastUpAt).coerceAtLeast(0L)
        val replayDur = when (kind) {
            "swipe" -> dur.coerceIn(120L, 2500L)
            "longtap" -> dur.coerceIn(450L, 3000L)
            else -> 60L
        }
        val step = RecStep(
            kind, sx.toInt(), sy.toInt(), ex.toInt(), ey.toInt(), replayDur, gap,
            IntArray(route.size) { route[it].toInt() },
        )
        RecState.steps.add(step)
        view?.invalidate()

        // Phân tích ảnh mẫu ở nền, lần lượt từng bước để không nghẽn máy
        val frame = downFrame
        downFrame = null
        scope.launch(Dispatchers.Default) {
            lock.withLock {
                try { if (frame != null) StepAnalyzer.analyze(step, frame, cropPx) }
                catch (e: Exception) { AppState.log("Lỗi phân tích bước ghi: ${e.message}") }
                step.ready = true
            }
        }

        // Chạm thay vào app bên dưới (tạm cho lớp phủ không nhận chạm)
        busy = true
        setTouchable(false)
        scope.launch {
            delay(40)
            val svc = AutoClickService.instance
            if (svc != null) {
                if (kind == "swipe") {
                    if (route.size >= 4) svc.path(route.toFloatArray(), replayDur) else svc.swipe(sx, sy, ex, ey, replayDur)
                } else svc.tap(sx, sy, replayDur)
            }
            delay(80)
            main.post { setTouchable(true); busy = false; lastUpAt = SystemClock.uptimeMillis() }
        }
    }

    private inner class RecView : View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6D32F2F.toInt() }
        private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * d; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val stopRect = RectF()

        override fun onDraw(c: Canvas) {
            val w = 190 * d
            val h = 40 * d
            val l = (width - w) / 2f
            val t = 30 * d
            stopRect.set(l, t, l + w, t + h)
            c.drawRoundRect(stopRect, 20 * d, 20 * d, bg)
            c.drawText("● GHI ${RecState.steps.size} bước · ■ Dừng", stopRect.centerX(), stopRect.centerY() + 5 * d, tx)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopTouch = stopRect.contains(e.x, e.y)
                    if (stopTouch || busy) return true
                    downAt = SystemClock.uptimeMillis()
                    sx = e.rawX; sy = e.rawY
                    pts.clear(); pts.add(sx); pts.add(sy)
                    downFrame = snapshot()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!stopTouch && !busy && pts.size < 600) {
                        val lx = pts[pts.size - 2]; val ly = pts[pts.size - 1]
                        if (hypot(e.rawX - lx, e.rawY - ly) > 8 * d) { pts.add(e.rawX); pts.add(e.rawY) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (stopTouch) { if (stopRect.contains(e.x, e.y)) onStop(); stopTouch = false }
                    else if (!busy) finishGesture(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_CANCEL -> { stopTouch = false }
            }
            return true
        }
    }
}

/** Biến các bước đã ghi thành luật tuần tự và lưu thành bộ luật. */
object RecBuild {
    private fun macroFor(s: RecStep, useMatch: Boolean, wait: Long): String {
        val w = if (wait > 0) " wait=$wait" else ""
        return when (s.kind) {
            "swipe" -> {
                val route = s.path.toList().chunked(2).joinToString(";") { "${it[0]},${it[1]}" }
                "swipe ${s.x1} ${s.y1} ${s.x2} ${s.y2} ${s.durMs}$w" + (if (s.path.size >= 4) " path=$route" else "")
            }
            else -> {
                val ms = if (s.kind == "longtap") " ms=${s.durMs}" else ""
                if (useMatch) {
                    val r = s.region
                    val dx = if (r != null) s.x1 - (r.l + r.r) / 2 else 0
                    val dy = if (r != null) s.y1 - (r.t + r.b) / 2 else 0
                    "tap_match" + (if (dx != 0 || dy != 0) " $dx $dy" else "") + ms + w
                } else {
                    (if (s.kind == "longtap") "longtap" else "tap") + " ${s.x1} ${s.y1}" + (if (s.kind == "longtap") " ${s.durMs}" else "") + w
                }
            }
        }
    }

    /** Trả về danh sách luật. Bước nào dùng ảnh → luật Hình riêng; bước dùng toạ độ cố định → nối vào macro của luật trước. */
    fun build(c: Context, steps: List<RecStep>, namePrefix: String): List<Rule> {
        val rules = ArrayList<Rule>()
        for ((i, s) in steps.withIndex()) {
            val img = s.crop != null && s.region != null && (i == 0 || s.useImage)
            if (img) {
                val r = Rule(name = "$namePrefix ${rules.size + 1}", type = Rule.IMAGE, threshold = 0.85, cooldownMs = 1000)
                r.regions = mutableListOf(s.searchRegion ?: s.region!!)
                r.tplRegion = s.region
                r.macro = macroFor(s, useMatch = true, wait = 0)
                FileOutputStream(RuleStore.templateFile(c, r.id)).use { s.crop!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                rules.add(r)
            } else if (rules.isNotEmpty()) {
                val gap = s.gapBeforeMs.coerceIn(200L, 5000L)
                val last = rules.last()
                last.macro = last.macro.trimEnd() + "\n" + macroFor(s, useMatch = false, wait = gap)
            }
        }
        return rules
    }

    /** Lưu thành bộ luật mà KHÔNG làm mất danh sách luật đang dùng. */
    fun saveProfile(c: Context, name: String, rules: List<Rule>): Profile {
        val oldRules = RuleStore.load(c)
        val oldSeq = RuleStore.seqOn(c); val oldCycles = RuleStore.seqCycles(c); val oldGap = RuleStore.seqGap(c)
        val oldActive = RuleStore.activeProfiles(c)
        try {
            RuleStore.saveAll(c, rules)
            RuleStore.setSeqOn(c, true); RuleStore.setSeqCycles(c, 1); RuleStore.setSeqGap(c, 0L)
            return ProfileStore.saveCurrent(c, name)
        } finally {
            RuleStore.saveAll(c, oldRules)
            RuleStore.setSeqOn(c, oldSeq); RuleStore.setSeqCycles(c, oldCycles); RuleStore.setSeqGap(c, oldGap)
            RuleStore.setActiveProfiles(c, oldActive)
            rules.forEach { RuleStore.templateFile(c, it.id).delete() }
        }
    }
}
