package com.example.screenwatcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Dịch vụ trợ năng: thực hiện chạm / vuốt / Back / Home thay người dùng. */
class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoClickService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private suspend fun gesture(p: Path, dur: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceIn(1, GestureDescription.getMaxGestureDuration())))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    private val ui = Handler(Looper.getMainLooper())

    /** Vẽ hiệu ứng chạm / vuốt lên màn hình để người dùng quan sát (có thể tắt trong app). */
    fun flash(kind: String, x1: Float, y1: Float, x2: Float, y2: Float, dur: Long, label: String, sizeDp: Int = 44) {
        if (!RuleStore.feedbackOn(this)) return
        ui.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = FlashView(this, kind, x1, y1, x2, y2, dur, label, sizeDp)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            if (runCatching { wm.addView(v, lp) }.isSuccess) {
                ui.postDelayed({ runCatching { wm.removeView(v) } }, dur + 450)
            }
        }
    }

    suspend fun tap(x: Float, y: Float, dur: Long = 60): Boolean =
        gesture(Path().apply { moveTo(x, y) }, dur)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, dur)

    /** Vuốt theo đường đi nhiều điểm (xy = x0,y0,x1,y1,...) — phát lại đúng đường ngón tay đã ghi. */
    suspend fun path(xy: FloatArray, dur: Long): Boolean {
        if (xy.size < 4) return false
        return gesture(
            Path().apply {
                moveTo(xy[0], xy[1])
                for (i in 2 until xy.size - 1 step 2) lineTo(xy[i], xy[i + 1])
            },
            dur,
        )
    }
}

/** Hiệu ứng ngắn: vòng tròn cho chạm / giữ, đường + chấm chạy cho vuốt. */
private class FlashView(
    c: Context,
    private val kind: String,
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
    private val dur: Long,
    private val label: String,
    private val sizeDp: Int,
) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val t0 = SystemClock.uptimeMillis()
    private val fade = 350L
    private val rad = sizeDp / 2f * d
    private val col = when (kind) {
        "longtap" -> 0xFFFB8C00.toInt()
        "swipe" -> 0xFF43A047.toInt()
        else -> 0xFF1E88E5.toInt()
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = col }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = col }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16 * d; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(c: Canvas) {
        val el = SystemClock.uptimeMillis() - t0
        val a = if (el <= dur) 255 else (255 * (1f - (el - dur) / fade.toFloat())).toInt().coerceIn(0, 255)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        line.alpha = a
        dot.alpha = (a * 0.7f).toInt()
        if (kind == "swipe") {
            c.drawLine(x1, y1, x2, y2, line)
            c.drawCircle(x1, y1, rad, line)
            val p = (el / max(dur, 1L).toFloat()).coerceIn(0f, 1f)
            c.drawCircle(x1 + (x2 - x1) * p, y1 + (y2 - y1) * p, rad * 0.8f, dot)
            val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            for (s in doubleArrayOf(2.6, -2.6)) {
                c.drawLine(
                    x2, y2,
                    x2 + (rad * 1.4f * cos(ang + s)).toFloat(), y2 + (rad * 1.4f * sin(ang + s)).toFloat(), line,
                )
            }
        } else {
            val g = (el / (dur + fade).toFloat()).coerceIn(0f, 1f)
            c.drawCircle(x1, y1, rad + 24 * d * g, line)
            c.drawCircle(x1, y1, rad * 0.7f, dot)
        }
        if (label.isNotEmpty()) c.drawText(label, x1 + rad, y1 - rad, txt)
        c.restore()
        if (el < dur + fade) postInvalidateOnAnimation()
    }
}

/**
 * Macro: mỗi dòng một lệnh
 *   tap_match [dx dy] [ms=..]     chạm vào vị trí vừa tìm thấy (có thể lệch dx, dy; ms=.. để giữ)
 *   tap x y [ms]                  chạm toạ độ (mặc định 60 ms)
 *   longtap x y [ms]              chạm và giữ (mặc định 800 ms)
 *   swipe x1 y1 x2 y2 [ms] [path=x,y;x,y;...]  vuốt (mặc định 300 ms); path= là đường đi đã ghi (nếu có thì vuốt theo đường đó)
 *   wait ms                       chờ
 *   back | home | recents         phím hệ thống
 *   # ghi chú
 * Các lệnh chạm/giữ/vuốt có thể thêm  times=N  (số lần lặp) và  gap=ms  (nghỉ giữa các lần, mặc định 150).
 *   wait=ms  chờ bấy nhiêu ms TRƯỚC khi thực hiện dòng này, rồi mới chạm (mặc định 120).
 *   size=dp  kích thước nút hiển thị của điểm chạm (mặc định 44, chỉ để quan sát).
 *   vd: swipe 540 1600 540 600 300 times=5 gap=400
 */
object Macro {
    private suspend fun repeatAction(times: Int, gap: Long, f: suspend (Int) -> Unit) {
        for (i in 0 until times) {
            if (!AppState.monitoring) return
            f(i)
            if (i < times - 1) delay(gap)
        }
    }

    private fun lab(times: Int, i: Int): String = if (times > 1) "${i + 1}/$times" else ""

    /** Hậu tố ghi vào macro cho số lần / nghỉ giữa các lần. */
    fun suffix(times: Int, gap: Long, wait: Long = 120L, size: Int = 44): String =
        (if (times > 1) " times=$times gap=$gap" else "") +
            (if (wait != 120L) " wait=$wait" else "") +
            (if (size != 44) " size=$size" else "")

    /** Chạy macro của luật, lặp theo cài đặt của luật (repeatCount: 1 = 1 lần, 0 = mãi mãi). */
    suspend fun runRule(rule: Rule, mx: Int, my: Int) {
        if (rule.delayBeforeMs > 0) { // chờ rồi mới chạy (toạ độ tap_match giữ theo lúc khớp)
            AppState.log("⏱ '${rule.name}': chờ ${rule.delayBeforeMs} ms rồi mới chạy")
            delay(rule.delayBeforeMs)
            if (!AppState.monitoring) return
        }
        var n = 0
        while (AppState.monitoring) {
            run(rule.macro, mx, my)
            n++
            if (rule.repeatCount != 0 && n >= rule.repeatCount) break
            delay(rule.repeatDelayMs.coerceAtLeast(50))
        }
        if (n > 1) AppState.log("↻ '${rule.name}' đã chạy $n lần")
    }

    suspend fun run(script: String, mx: Int, my: Int) {
        val svc = AutoClickService.instance
        if (svc == null) {
            AppState.log("⚠ Chưa bật Dịch vụ trợ năng nên không thể chạm")
            return
        }
        for (raw in script.lines()) {
            if (!AppState.monitoring) return
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val p = line.split(Regex("\\s+"))
            val rest = p.drop(1)
            val opts = rest.filter { it.contains('=') }
                .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            val a = rest.filter { !it.contains('=') }.mapNotNull { it.toFloatOrNull() }
            val times = (opts["times"]?.toIntOrNull() ?: 1).coerceIn(1, 100_000)
            val gap = (opts["gap"]?.toLongOrNull() ?: 150L).coerceAtLeast(0)
            val ms = opts["ms"]?.toLongOrNull()
            val waitBefore = (opts["wait"]?.toLongOrNull() ?: 120L).coerceAtLeast(0)
            val sizeDp = (opts["size"]?.toIntOrNull() ?: 44).coerceIn(16, 200)
            if (waitBefore > 0) delay(waitBefore) // chờ rồi mới chạm
            if (!AppState.monitoring) return
            when (p[0].lowercase()) {
                "tap_match" -> repeatAction(times, gap) { i ->
                    val x = mx + (a.getOrNull(0) ?: 0f)
                    val y = my + (a.getOrNull(1) ?: 0f)
                    val d = ms ?: 60L
                    svc.flash(if (d >= 500L) "longtap" else "tap", x, y, x, y, d, lab(times, i), sizeDp)
                    svc.tap(x, y, d)
                }
                "tap" -> if (a.size >= 2) repeatAction(times, gap) { i ->
                    val d = (a.getOrNull(2)?.toLong()) ?: ms ?: 60L
                    svc.flash("tap", a[0], a[1], a[0], a[1], d, lab(times, i), sizeDp)
                    svc.tap(a[0], a[1], d)
                }
                "longtap" -> if (a.size >= 2) repeatAction(times, gap) { i ->
                    val d = (a.getOrNull(2)?.toLong()) ?: ms ?: 800L
                    svc.flash("longtap", a[0], a[1], a[0], a[1], d, lab(times, i), sizeDp)
                    svc.tap(a[0], a[1], d)
                }
                "swipe" -> if (a.size >= 4) repeatAction(times, gap) { i ->
                    val d = (a.getOrNull(4)?.toLong()) ?: ms ?: 300L
                    svc.flash("swipe", a[0], a[1], a[2], a[3], d, lab(times, i), sizeDp)
                    val route = MacroEdit.parsePath(opts["path"])
                    if (route != null) svc.path(route, d) else svc.swipe(a[0], a[1], a[2], a[3], d)
                }
                "wait" -> delay((a.getOrNull(0) ?: 500f).toLong())
                "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                else -> AppState.log("⚠ Lệnh macro không hợp lệ: $line")
            }
        }
    }
}
