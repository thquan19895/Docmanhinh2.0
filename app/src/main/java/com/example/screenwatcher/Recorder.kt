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
    @Volatile var mode = IMAGE           // cách nhận diện: IMAGE / TEXT / FIXED (chạm toạ độ cố định)

    // ---- OCR chữ quanh điểm chạm ----
    @Volatile var textLevel = -1         // -1 = không có chữ dùng được, 0 = tốt, 1 = yếu
    @Volatile var textReasons: List<String> = emptyList()
    @Volatile var ocrRaw = ""            // chữ OCR đọc được (nguyên gốc)
    @Volatile var editedText = ""        // chữ sẽ dùng cho luật (người dùng sửa được)

    // ---- ngưỡng: đề xuất tự động + giá trị đang chọn ----
    @Volatile var imgSuggested = 0.85
    @Volatile var imgThreshold = 0.85
    @Volatile var imgThrNote = ""
    @Volatile var textSuggested = 0.8
    @Volatile var textThreshold = 0.8
    @Volatile var textThrNote = ""
    @Volatile var textBox: Region? = null     // khung dòng chữ trên màn hình
    @Volatile var textRegion: Region? = null  // vùng sẽ quét chữ khi chạy

    /** Mức đánh giá của cách nhận diện đang chọn (0 tốt, 1 yếu, 2 rất yếu). */
    fun activeLevel(): Int = when (mode) {
        TEXT -> if (textLevel < 0) 2 else textLevel
        IMAGE -> level
        else -> 0
    }

    companion object {
        const val IMAGE = 0
        const val TEXT = 1
        const val FIXED = 2
    }
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

    suspend fun analyze(step: RecStep, frame: Bitmap, sizePx: Int) {
        val fw = frame.width
        val fh = frame.height
        val size = minOf(sizePx, fw, fh)
        if (size < 16) return
        val l = (step.x1 - size / 2).coerceIn(0, fw - size)
        val t = (step.y1 - size / 2).coerceIn(0, fh - size)
        val crop = Bitmap.createBitmap(frame, l, t, size, size)
        val reasons = ArrayList<String>()
        var level = 0
        var lowDetail = false
        var dupScore = 0.0

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
            level = 2; lowDetail = true; reasons.add("Gần như đơn sắc, rất dễ nhận nhầm")
        } else if (std < 22 || edgeFrac < 0.06) {
            level = 1; lowDetail = true; reasons.add("Ít chi tiết, có thể nhận nhầm")
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
                    dupScore = hit.score
                    if (hit.score >= 0.93) level = 2 else if (level < 1) level = 1
                    reasons.add("Có hình giống ở chỗ khác trong vùng quét (độ giống ${"%.2f".format(hit.score)})")
                }
            }
        }
        if (reasons.isEmpty()) reasons.add("Ảnh rõ, đủ chi tiết")

        // Ngưỡng ảnh đề xuất: trùng thì đặt cao hơn mức trùng; ảnh rõ & duy nhất thì hạ thấp để chịu sai khác nhỏ
        val sugImg: Double
        val noteImg: String
        when {
            dupScore > 0 -> {
                sugImg = Math.round(minOf(0.95, dupScore + 0.03) * 100) / 100.0
                noteImg = "Có hình giống (${"%.2f".format(dupScore)}) nên đặt ngưỡng cao hơn mức đó" +
                    (if (dupScore >= 0.92) "; hai hình quá giống, nên dùng Chữ hoặc Toạ độ" else "")
            }
            lowDetail -> { sugImg = 0.88; noteImg = "Ít chi tiết nên đặt ngưỡng chặt" }
            else -> { sugImg = 0.82; noteImg = "Ảnh rõ, không bị trùng nên hạ ngưỡng để chịu sai khác nhỏ về sáng/màu" }
        }
        step.imgSuggested = sugImg
        step.imgThreshold = sugImg
        step.imgThrNote = noteImg

        // 2b) OCR chữ quanh điểm chạm: lấy dòng chữ chứa (hoặc gần) điểm chạm
        try {
            val wl = (step.x1 - size * 3).coerceAtLeast(0)
            val wt = (step.y1 - size).coerceAtLeast(0)
            val wr = (step.x1 + size * 3).coerceAtMost(fw)
            val wb = (step.y1 + size).coerceAtMost(fh)
            if (wr - wl >= 24 && wb - wt >= 24) {
                val win = Bitmap.createBitmap(frame, wl, wt, wr - wl, wb - wt)
                val lines = try {
                    TextMatcher.readLines(win).filter { ln ->
                        val n = TextMatcher.norm(ln.text)
                        !(n.contains("buoc") && n.contains("dung")) // bỏ thanh GHI của app
                    }
                } finally { if (win !== frame) win.recycle() }
                var best: TextMatcher.OcrLine? = null
                var bestBox: Region? = null
                var bestD = Double.MAX_VALUE
                for (ln in lines) {
                    val b = ln.box ?: continue
                    val bl = b.left + wl; val bt = b.top + wt; val br = b.right + wl; val bb = b.bottom + wt
                    val dx = maxOf(bl - step.x1, 0, step.x1 - br).toDouble()
                    val dy = maxOf(bt - step.y1, 0, step.y1 - bb).toDouble()
                    val dist = hypot(dx, dy)
                    if (dist < bestD) { bestD = dist; best = ln; bestBox = Region(bl, bt, br, bb) }
                }
                val chosen = best
                if (chosen != null && bestBox != null && bestD <= size * 0.75) {
                    val raw = chosen.text.replace("\n", " ").trim()
                    val nrm = TextMatcher.fold(TextMatcher.norm(raw))
                    val tr = ArrayList<String>()
                    var tl = 0
                    if (nrm.length < 2) {
                        tl = -1; tr.add("Chữ đọc được quá ngắn / không rõ")
                    } else {
                        if (nrm.length == 2) { tl = 1; tr.add("Chữ rất ngắn, dễ nhận nhầm") }
                        if (nrm.length > 35) { tl = 1; tr.add("Chữ dài, dễ bị đọc sai một phần") }
                        val dup = lines.count { o ->
                            if (o === chosen) false else {
                                val n = TextMatcher.fold(TextMatcher.norm(o.text))
                                n.isNotEmpty() && (n.contains(nrm) || nrm.contains(n))
                            }
                        }
                        if (dup > 0) { tl = 1; tr.add("Có $dup dòng chữ giống ở chỗ khác trong vùng quét") }
                        if (tr.isEmpty()) tr.add("Chữ rõ, không bị trùng")
                        var ts = when {
                            nrm.length <= 4 -> 0.9
                            nrm.length <= 8 -> 0.8
                            nrm.length <= 15 -> 0.75
                            else -> 0.7
                        }
                        if (dup > 0) ts = minOf(0.95, ts + 0.05)
                        step.textSuggested = ts
                        step.textThreshold = ts
                        step.textThrNote = "Chữ " + nrm.length + " ký tự: " +
                            (if (nrm.length <= 4) "ngắn nên đòi khớp gần chính xác" else "dài hơn nên cho phép lệch vài ký tự do OCR") +
                            (if (dup > 0) "; có dòng giống nên tăng thêm" else "")
                    }
                    step.ocrRaw = raw
                    step.editedText = raw
                    step.textLevel = tl
                    step.textReasons = tr
                    step.textBox = bestBox
                    step.textRegion = Region(wl, wt, wr, wb)
                } else {
                    step.textReasons = listOf("Không thấy chữ ở chỗ chạm")
                }
            }
        } catch (ex: Exception) {
            step.textReasons = listOf("Không đọc được chữ (${ex.message})")
        }

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
            step.textBox?.let { tb ->
                val os = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFFB8C00.toInt() }
                c.drawRect(RectF((tb.l - tl) * k, (tb.t - tt) * k, (tb.r - tl) * k, (tb.b - tt) * k), os)
            }
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
        step.mode = when {
            step.textLevel == 0 -> RecStep.TEXT
            level < 2 -> RecStep.IMAGE
            step.textLevel == 1 -> RecStep.TEXT
            else -> RecStep.FIXED
        }
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

    @Volatile private var busy = false
    private var lastUpAt = 0L
    private var downAt = 0L
    private var downFrame: Bitmap? = null
    private var sx = 0f
    private var sy = 0f
    private val pts = ArrayList<Float>()
    private var stopTouch = false

    private fun newParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    fun show() {
        view = RecView()
        RecState.recording = true
        attach()
    }

    fun hide() {
        RecState.recording = false
        if (attached) view?.let { runCatching { wm.removeViewImmediate(it) } }
        attached = false
        view = null
    }

    @Volatile private var attached = false

    /** Gỡ hẳn lớp phủ (đồng bộ) để cú chạm thay chắc chắn rơi xuống app bên dưới, không bị lớp phủ ăn mất. */
    private fun detach() {
        val v = view ?: return
        if (!attached) return
        runCatching { wm.removeViewImmediate(v) }
        attached = false
    }

    private fun attach() {
        val v = view ?: return
        if (attached || !RecState.recording) return
        if (runCatching { wm.addView(v, newParams()) }.isSuccess) attached = true
    }

    private suspend fun onMain(block: () -> Unit) {
        val d = kotlinx.coroutines.CompletableDeferred<Unit>()
        main.post { try { block() } finally { d.complete(Unit) } }
        d.await()
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
            else -> dur.coerceIn(70L, 250L)
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

        // Chạm thay vào app bên dưới: gỡ lớp phủ -> chạm (thử lại 1 lần nếu bị huỷ) -> gắn lại lớp phủ
        busy = true
        val idx = RecState.steps.size
        scope.launch {
            onMain { detach() }
            delay(50) // để hệ thống cập nhật xong danh sách cửa sổ nhận chạm
            val svc = AutoClickService.instance
            var ok = false
            if (svc != null) {
                for (attempt in 0..1) {
                    if (ok) break
                    ok = if (kind == "swipe") {
                        if (route.size >= 4) svc.path(route.toFloatArray(), replayDur) else svc.swipe(sx, sy, ex, ey, replayDur)
                    } else svc.tap(sx, sy, replayDur)
                    if (!ok) delay(80)
                }
            }
            if (!ok) AppState.log("⚠ Bước $idx: phát lại thao tác không thành công (bị huỷ hoặc dịch vụ trợ năng chưa sẵn sàng)")
            delay(60)
            onMain { attach(); busy = false; lastUpAt = SystemClock.uptimeMillis() }
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
    private fun macroFor(s: RecStep, useMatch: Boolean, wait: Long, cx: Int = s.x1, cy: Int = s.y1): String {
        val w = if (wait > 0) " wait=$wait" else ""
        return when (s.kind) {
            "swipe" -> {
                val route = s.path.toList().chunked(2).joinToString(";") { "${it[0]},${it[1]}" }
                "swipe ${s.x1} ${s.y1} ${s.x2} ${s.y2} ${s.durMs}$w" + (if (s.path.size >= 4) " path=$route" else "")
            }
            else -> {
                val ms = if (s.kind == "longtap") " ms=${s.durMs}" else ""
                if (useMatch) {
                    val dx = s.x1 - cx
                    val dy = s.y1 - cy
                    "tap_match" + (if (dx != 0 || dy != 0) " $dx $dy" else "") + ms + w
                } else {
                    (if (s.kind == "longtap") "longtap" else "tap") + " ${s.x1} ${s.y1}" + (if (s.kind == "longtap") " ${s.durMs}" else "") + w
                }
            }
        }
    }

    /** Cách nhận diện thực sự dùng cho bước [i] (bước 1 không được là toạ độ cố định). */
    fun effectiveMode(s: RecStep, i: Int): Int {
        val canText = s.textLevel >= 0 && s.textBox != null && s.editedText.isNotBlank()
        val canImage = s.crop != null && s.region != null
        val want = if (i == 0 && s.mode == RecStep.FIXED) (if (canText) RecStep.TEXT else RecStep.IMAGE) else s.mode
        return when (want) {
            RecStep.TEXT -> if (canText) RecStep.TEXT else if (canImage) RecStep.IMAGE else RecStep.FIXED
            RecStep.IMAGE -> if (canImage) RecStep.IMAGE else if (canText) RecStep.TEXT else RecStep.FIXED
            else -> RecStep.FIXED
        }
    }

    /** Trả về danh sách luật. Bước dùng ảnh/chữ → một luật riêng; bước dùng toạ độ cố định → nối vào macro của luật trước. */
    fun build(c: Context, steps: List<RecStep>, namePrefix: String): List<Rule> {
        val rules = ArrayList<Rule>()
        for ((i, s) in steps.withIndex()) {
            when (effectiveMode(s, i)) {
                RecStep.IMAGE -> {
                    val r = Rule(name = "$namePrefix ${rules.size + 1}", type = Rule.IMAGE, threshold = s.imgThreshold, cooldownMs = 1000)
                    val rg = s.region!!
                    r.regions = mutableListOf(s.searchRegion ?: rg)
                    r.tplRegion = rg
                    r.macro = macroFor(s, useMatch = true, wait = 0, cx = (rg.l + rg.r) / 2, cy = (rg.t + rg.b) / 2)
                    FileOutputStream(RuleStore.templateFile(c, r.id)).use { s.crop!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    rules.add(r)
                }
                RecStep.TEXT -> {
                    val tb = s.textBox!!
                    val r = Rule(name = "$namePrefix ${rules.size + 1}", type = Rule.TEXT, text = s.editedText.trim(), fuzzy = true, threshold = s.textThreshold, cooldownMs = 1000)
                    r.regions = mutableListOf(s.textRegion ?: tb)
                    r.macro = macroFor(s, useMatch = true, wait = 0, cx = (tb.l + tb.r) / 2, cy = (tb.t + tb.b) / 2)
                    rules.add(r)
                }
                else -> if (rules.isNotEmpty()) {
                    val gap = s.gapBeforeMs.coerceIn(200L, 5000L)
                    val last = rules.last()
                    last.macro = last.macro.trimEnd() + "\n" + macroFor(s, useMatch = false, wait = gap)
                }
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
