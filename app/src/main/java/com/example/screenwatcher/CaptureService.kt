package com.example.screenwatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * Giữ MediaProjection (chụp màn hình), vòng lặp quét luật, và lớp phủ chọn vùng.
 */
class CaptureService : Service() {

    companion object {
        const val A_START = "sw.start"
        const val A_MONITOR = "sw.monitor"
        const val A_STOP = "sw.stop"
        const val A_PICK = "sw.pick"
        const val A_TEST = "sw.test"
        const val A_BUBBLE = "sw.bubble"
        const val A_PROFILE = "sw.profile"
        const val A_BASELINE = "sw.baseline"
        const val A_RECORD = "sw.record"
        private const val CH = "sw_capture"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var testOv: TestOverlay? = null
    /** Lúc bắt đầu theo dõi (elapsedRealtime) để tính giới hạn "tự dừng sau N phút". */
    private var monitorStartAt = 0L
    /** Phút (epoch) cuối cùng đã xét hẹn giờ; 0 = chưa xét lần nào. */
    private var lastSchedMin = 0L
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    @Volatile private var last: Bitmap? = null
    private var overlay: SelectorOverlay? = null
    private var fab: FloatingButton? = null
    private var loop: Job? = null
    private val lastFire = HashMap<String, Long>()
    private var editor: ActionEditorOverlay? = null
    private var recOv: RecordOverlay? = null
    private var seqIndex = 0
    private var seqCycle = 0
    @Volatile private var seqResumeAt = 0L // đang nghỉ giữa hai vòng đến thời điểm này
    private var seqLogged = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_START -> start()
            A_MONITOR -> setMonitoring(i.getBooleanExtra("on", false))
            A_BUBBLE -> if (i.getBooleanExtra("on", true)) showBubble() else { fab?.hide() }
            A_STOP -> { stopAll(); return START_NOT_STICKY }
            A_PICK -> pick(
                i.getStringExtra("mode") ?: "REGIONS", i.getStringExtra("id") ?: "",
                i.getStringExtra("kind") ?: "tap", i.getLongExtra("ms", 60L),
                i.getIntExtra("times", 1), i.getLongExtra("gap", 150L),
            )
            A_BASELINE -> recaptureOriginals(i.getStringExtra("id") ?: "")
            A_RECORD -> if (recOv == null) startRecord() else stopRecord()
            A_TEST -> test(i.getStringExtra("id") ?: "")
            A_PROFILE -> loadProfile(i.getStringExtra("id") ?: "", i.getBooleanExtra("run", true))
        }
        return START_NOT_STICKY
    }

    private fun setMonitoring(on: Boolean) {
        val was = AppState.monitoring
        AppState.monitoring = on && projection != null
        if (AppState.monitoring && !was) {
            seqIndex = 0; seqCycle = 0; seqLogged = -1; seqResumeAt = 0L; ChangeMatcher.resetLatches()
            monitorStartAt = SystemClock.elapsedRealtime()
        }
        if (!AppState.monitoring) AppState.seqStatus = ""
        getSystemService(NotificationManager::class.java).notify(1, notif())
        AppState.log(if (AppState.monitoring) "▶ Bắt đầu theo dõi" else "⏸ Dừng theo dõi")
        fab?.refresh()
    }

    /** Nạp một bộ luật đã lưu; [run] = true thì chạy theo dõi luôn (bắt đầu lại từ luật đầu). */
    private fun loadProfile(id: String, run: Boolean, merge: Boolean = false) {
        val name = ProfileStore.list(this).firstOrNull { it.id == id }?.name
        if (name == null || !ProfileStore.load(this, id, merge)) {
            AppToast.show(this, "Không nạp được bộ luật", Toast.LENGTH_LONG)
            return
        }
        if (merge) {
            // gộp thêm: giữ nguyên các luật đang chạy, bộ luật mới tham gia từ lần quét kế tiếp
            AppState.log("➕ Đã gộp bộ luật '$name' (chạy song song)")
            AppToast.show(this, "Đã gộp '$name' — các bộ luật chạy song song", Toast.LENGTH_SHORT)
            fab?.refresh()
            return
        }
        lastFire.clear()
        ChangeMatcher.resetLatches()
        AppState.log("📂 Đã nạp bộ luật '$name'")
        if (AppState.monitoring) setMonitoring(false) // dừng vòng cũ để chạy lại từ đầu
        if (run) {
            if (projection == null) {
                AppToast.show(this, "Đã nạp '$name'. Cần cấp quyền chụp màn hình để chạy.", Toast.LENGTH_LONG)
            } else {
                setMonitoring(true)
                AppToast.show(this, "Đang chạy bộ luật '$name'", Toast.LENGTH_SHORT)
            }
        } else {
            AppToast.show(this, "Đã nạp bộ luật '$name'", Toast.LENGTH_SHORT)
        }
        fab?.refresh()
    }

    /* ---------------- nút nổi ---------------- */

    private fun showBubble() {
        if (projection == null || !RuleStore.bubbleOn(this) || !Settings.canDrawOverlays(this)) return
        val f = fab ?: FloatingButton(
            this,
            onMonitor = { setMonitoring(it) },
            onAddRule = {
                startActivity(Intent(this, RuleEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onOpenApp = {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onTestRule = { quickTest(it) },
            onRecord = { fab?.collapse(); main.postDelayed({ startRecord() }, 350) },
            onRunProfile = { id -> fab?.collapse(); loadProfile(id, false) },
            onMergeProfile = { id -> loadProfile(id, false, merge = true) },
            onHideButton = {
                RuleStore.setBubbleOn(this, false)
                fab?.hide()
                AppToast.show(this, "Đã ẩn nút nổi. Bật lại trong app ở mục \"Hiện nút nổi\".", Toast.LENGTH_LONG)
            },
        ).also { fab = it }
        f.show()
    }

    /* ---------------- khởi động chụp màn hình ---------------- */

    private fun start() {
        val data = AppState.projData ?: return
        teardown()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "ScreenWatcher", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, notif())

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(AppState.projCode, data)
        AppState.projData = null
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll() } }
        }, main)
        projection = p
        setupDisplay()
        AppState.projectionReady = true
        loop = scope.launch { runLoop() }
        AppState.log("✔ Đã cấp quyền chụp màn hình")
        showBubble()
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    @Synchronized
    private fun setupDisplay() {
        val p = projection ?: return
        val (w, h, dpi) = realSize()
        if (vd != null && w == curW && h == curH) return
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = reader
        if (vd == null) {
            vd = p.createVirtualDisplay(
                "sw", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
            )
        } else {
            vd!!.surface = r.surface
            vd!!.resize(w, h, dpi)
        }
        reader = r
        old?.close()
        curW = w; curH = h
        last = null
    }

    /** Lấy khung hình mới nhất (nếu màn hình không đổi thì dùng lại khung cũ). */
    @Synchronized
    private fun grab(): Bitmap? {
        val r = reader ?: return last
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return last
        try {
            val pl = img.planes[0]
            val pad = pl.rowStride - pl.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + pad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            last = if (pad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also { bmp.recycle() }
        } finally {
            img.close()
        }
        return last
    }

    /* ---------------- vòng lặp quét ---------------- */

    /**
     * Hẹn giờ: tự bắt đầu / tự dừng theo giờ trong ngày (lặp mỗi ngày) và tự dừng sau N phút.
     * Xét theo từng phút đã trôi qua kể từ lần kiểm tra trước, nên không bỏ lỡ giờ hẹn dù vòng quét bị trễ.
     * Chỉ chạy khi dịch vụ đang sống (thông báo "Đang theo dõi/Sẵn sàng" còn hiện).
     */
    private fun checkSchedule() {
        val lim = RuleStore.autoStopMin(this)
        if (AppState.monitoring && lim > 0 && SystemClock.elapsedRealtime() - monitorStartAt >= lim * 60_000L) {
            AppState.log("⏱ Đã chạy đủ $lim phút, tự dừng theo dõi")
            main.post { setMonitoring(false) }
            return
        }
        val onStart = RuleStore.schedStartOn(this)
        val onStop = RuleStore.schedStopOn(this)
        val nowMin = System.currentTimeMillis() / 60_000L
        if (lastSchedMin == 0L) lastSchedMin = nowMin - 1
        if (nowMin <= lastSchedMin) return
        val from = maxOf(lastSchedMin + 1, nowMin - 1440) // tối đa xét lại 1 ngày
        lastSchedMin = nowMin
        if (!onStart && !onStop) return
        val tz = java.util.TimeZone.getDefault()
        val startAt = RuleStore.schedStartMin(this)
        val stopAt = RuleStore.schedStopMin(this)
        var act = 0 // 1 = bắt đầu, 2 = dừng; lấy giờ hẹn muộn nhất trong khoảng vừa qua
        for (m in from..nowMin) {
            val ms = m * 60_000L
            val md = (((ms + tz.getOffset(ms)) / 60_000L) % 1440L).toInt().let { if (it < 0) it + 1440 else it }
            if (onStop && md == stopAt) act = 2
            if (onStart && md == startAt) act = 1
        }
        when (act) {
            1 -> if (!AppState.monitoring) {
                if (projection == null) {
                    AppState.log("⚠ Hẹn giờ: tới giờ bắt đầu nhưng chưa cấp quyền chụp màn hình nên không tự chạy được")
                } else {
                    AppState.log("⏰ Tới giờ hẹn, tự bắt đầu theo dõi")
                    main.post { setMonitoring(true) }
                }
            }
            2 -> if (AppState.monitoring) {
                AppState.log("⏰ Tới giờ hẹn, tự dừng theo dõi")
                main.post { setMonitoring(false) }
            }
        }
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                checkSchedule()
                if (AppState.monitoring && !AppState.picking) {
                    setupDisplay()
                    tick()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppState.log("Lỗi: ${e.message}")
            }
            delay(RuleStore.interval(this))
        }
    }

    private suspend fun tick() {
        val frame = grab() ?: return
        if (RuleStore.seqOn(this)) { tickSequence(frame); return }
        for (rule in RuleStore.load(this).filter { it.enabled }) {
            val now = SystemClock.elapsedRealtime()
            if (now - (lastFire[rule.id] ?: -1_000_000L) < rule.cooldownMs) continue
            val hit = safeEval(rule, frame) ?: continue
            lastFire[rule.id] = SystemClock.elapsedRealtime()
            AppState.log("✔ '${rule.name}' ${hitText(rule, hit)}")
            Macro.runRule(rule, hit.x, hit.y)
            lastFire[rule.id] = SystemClock.elapsedRealtime()
            chain(rule) // có đặt "chạy cả bộ luật" thì chuyển bộ luật
            break // chụp lại khung mới rồi mới xét tiếp
        }
    }

    /**
     * Chế độ tuần tự: chờ luật 1 khớp -> chạy macro -> chờ luật 2 ... -> hết luật thì tính là xong 1 vòng,
     * rồi lặp lại theo số vòng đã đặt (0 = mãi mãi). Chỉ các luật đang bật mới tham gia, theo thứ tự trong danh sách.
     */
    /**
     * Luật vừa chạy xong macro: nếu có đặt "chạy cả bộ luật" thì nạp bộ luật đó (thay thế) và chạy từ đầu.
     * Trả true nếu đã chuyển bộ luật (vòng quét hiện tại cần dừng ngay).
     */
    private fun chain(rule: Rule): Boolean {
        if (rule.thenProfile.isEmpty() || !AppState.monitoring) return false
        val p = ProfileStore.list(this).firstOrNull { it.id == rule.thenProfile }
        if (p == null) {
            AppState.log("⚠ '${rule.name}': bộ luật kế tiếp không còn tồn tại, bỏ qua")
            return false
        }
        AppState.log("➡ '${rule.name}' xong -> chạy bộ luật '${p.name}'")
        main.post { loadProfile(p.id, true) } // Toast/giao diện phải chạy trên luồng chính
        return true
    }

    private suspend fun tickSequence(frame: Bitmap) {
        val rules = RuleStore.load(this).filter { it.enabled }
        if (rules.isEmpty()) return
        if (seqIndex >= rules.size) seqIndex = 0
        val total = RuleStore.seqCycles(this)
        val rest = seqResumeAt - System.currentTimeMillis()
        if (rest > 0) {
            AppState.seqStatus = "Tuần tự: nghỉ giữa các vòng, còn ${(rest + 999) / 1000} giây"
            return
        }
        val rule = rules[seqIndex]
        val cyc = if (total == 0) "${seqCycle + 1}" else "${seqCycle + 1}/$total"
        AppState.seqStatus = "Tuần tự: vòng $cyc • đang chờ luật ${seqIndex + 1}/${rules.size} '${rule.name}'"
        if (seqLogged != seqIndex) {
            seqLogged = seqIndex
            AppState.log("⏳ Chờ luật ${seqIndex + 1}/${rules.size}: '${rule.name}'")
        }
        val hit = safeEval(rule, frame) ?: return
        AppState.log("✔ [${seqIndex + 1}/${rules.size}] '${rule.name}' ${hitText(rule, hit)}")
        Macro.runRule(rule, hit.x, hit.y)
        if (!AppState.monitoring) return
        if (chain(rule)) return
        seqIndex++
        seqLogged = -1
        if (seqIndex >= rules.size) {
            seqIndex = 0
            seqCycle++
            if (total != 0 && seqCycle >= total) {
                AppState.log("✔ Hoàn thành $seqCycle vòng, dừng theo dõi")
                setMonitoring(false)
                return
            }
            val gap = RuleStore.seqGap(this)
            if (gap > 0) {
                seqResumeAt = System.currentTimeMillis() + gap
                AppState.log("⏸ Nghỉ ${gap / 1000.0} giây rồi bắt đầu vòng ${seqCycle + 1}" + (if (total == 0) "" else "/$total"))
            } else {
                AppState.log("↻ Bắt đầu vòng ${seqCycle + 1}" + (if (total == 0) "" else "/$total"))
            }
        }
    }

    private suspend fun safeEval(
        rule: Rule, frame: Bitmap,
        test: Boolean = false,
    ): Hit? = try {
        evaluate(rule, frame, test)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppState.log("Lỗi quét '${rule.name}': ${e.message}")
        null
    }

    private fun hitText(rule: Rule, hit: Hit): String =
        if (rule.type == Rule.CHANGE) "CÓ THAY ĐỔI ${"%.0f".format(hit.score * 100)}% tại ${hit.x},${hit.y}"
        else "KHỚP (${"%.2f".format(hit.score)}, tỉ lệ ${"%.2f".format(hit.scale)}x) tại ${hit.x},${hit.y}"


    private fun missText(rule: Rule) = if (rule.type == Rule.CHANGE) "không thấy thay đổi" else "không khớp"

    /** Người dùng chủ động chụp lại ảnh gốc của các vùng đã khoanh: chờ 4 giây để chuyển sang màn hình gốc rồi chụp. */
    private fun recaptureOriginals(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        fab?.hide()
        scope.launch {
            delay(3400)
            AppToast.cancel() // toast "Chuyển sang màn hình gốc…" không được lọt vào ảnh gốc
            delay(600)
            val f = grab()
            val n = if (f == null) 0 else try { ChangeMatcher.recaptureOriginals(this@CaptureService, rule, f) } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
            main.post {
                AppToast.show(this@CaptureService, if (n > 0) "Đã chụp lại ảnh gốc $n vùng của '${rule.name}'" else "Không chụp được ảnh gốc (chưa có vùng?)", Toast.LENGTH_LONG)
                showBubble()
            }
        }
    }

    private suspend fun evaluate(rule: Rule, frame: Bitmap, test: Boolean = false): Hit? {
        if (rule.type == Rule.CHANGE) return ChangeMatcher.detect(this, rule, frame, test)
        val regs = if (rule.regions.isEmpty()) listOf(Region(0, 0, frame.width, frame.height)) else rule.regions
        val tpl: Bitmap? = if (rule.type == Rule.IMAGE) {
            BitmapFactory.decodeFile(RuleStore.templateFile(this, rule.id).path) ?: return null
        } else null

        for (rg in regs) {
            val l = rg.l.coerceIn(0, frame.width - 1)
            val t = rg.t.coerceIn(0, frame.height - 1)
            val w = rg.r.coerceAtMost(frame.width) - l
            val h = rg.b.coerceAtMost(frame.height) - t
            if (w < 8 || h < 8) continue
            val crop = Bitmap.createBitmap(frame, l, t, w, h)
            val hit = if (tpl != null) TemplateMatcher.find(crop, tpl, rule.threshold)
            else TextMatcher.find(crop, rule)
            if (hit != null) return hit.copy(x = hit.x + l, y = hit.y + t)
        }
        return null
    }

    /* ---------------- thử một luật ---------------- */

    /** Thử nhanh một luật từ bảng nút nổi: ẩn bảng + nút, chờ 0,8 giây rồi quét, báo kết quả (chưa chạy macro). */
    /** Gom dữ liệu để vẽ kết quả Thử lên màn hình. */
    private fun buildViz(rule: Rule, hit: Hit?, frame: Bitmap, msg: String): TestViz {
        val change = rule.type == Rule.CHANGE
        val regs: List<RegionViz> = if (change) ChangeMatcher.lastDebug else {
            val rs = if (rule.regions.isEmpty()) listOf(Region(0, 0, frame.width, frame.height)) else rule.regions
            rs.map { RegionViz(android.graphics.Rect(it.l, it.t, it.r, it.b), null, "", emptyList()) }
        }
        var box: android.graphics.Rect? = null
        if (hit != null && rule.type == Rule.IMAGE) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(RuleStore.templateFile(this, rule.id).path, o)
            if (o.outWidth > 0) {
                val hw = (o.outWidth * hit.scale / 2).toInt()
                val hh = (o.outHeight * hit.scale / 2).toInt()
                box = android.graphics.Rect(hit.x - hw, hit.y - hh, hit.x + hw, hit.y + hh)
            }
        }
        val th = if (change) rule.diffThreshold else rule.threshold
        return TestViz("Thử '${rule.name}'", regs, hit, box, "Thử '${rule.name}': $msg", th, change)
    }

    private fun showViz(viz: TestViz) {
        if (!Settings.canDrawOverlays(this)) return
        testOv?.hide()
        testOv = TestOverlay(this).also { it.show(viz) }
    }

    private fun quickTest(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        fab?.hide()
        scope.launch {
            AppToast.cancel()
            delay(800)
            val frame: Bitmap?
            val hit: Hit?
            frame = grab()
            hit = if (frame == null) null else safeEval(rule, frame, test = true)
            val msg = when {
                frame == null -> "Chưa có khung hình"
                hit != null -> hitText(rule, hit)
                else -> missText(rule)
            }
            AppState.log("Thử nhanh '${rule.name}': $msg")
            val viz = if (frame != null) buildViz(rule, hit, frame, msg) else null
            main.post {
                if (viz != null) showViz(viz) else AppToast.show(this@CaptureService, "'${rule.name}': $msg", Toast.LENGTH_LONG)
                showBubble()
            }
        }
    }

    private fun test(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        scope.launch {
            delay(3400) // thời gian để người dùng chuyển sang màn hình cần kiểm tra
            AppToast.cancel() // toast "Chuyển sang màn hình cần kiểm tra…" không được lọt vào khung hình
            delay(600)
            val frame: Bitmap?
            val hit: Hit?
            frame = grab()
            hit = if (frame == null) null else safeEval(rule, frame, test = true)
            val msg = when {
                frame == null -> "Chưa có khung hình"
                hit != null -> hitText(rule, hit)
                else -> missText(rule)
            }
            AppState.log("Thử '${rule.name}': $msg")
            val viz = if (frame != null) buildViz(rule, hit, frame, msg) else null
            main.post {
                if (viz != null) showViz(viz) else AppToast.show(this@CaptureService, "Thử '${rule.name}': $msg", Toast.LENGTH_LONG)
            }
        }
    }

    /* ---------------- chọn vùng / ảnh mẫu / điểm chạm ---------------- */

    private fun pick(mode: String, id: String, kind: String = "tap", ms: Long = 60L, times: Int = 1, gap: Long = 150L) {
        if (mode == "ACTIONS") { pickActions(id); return }
        if (!Settings.canDrawOverlays(this)) {
            AppToast.show(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG)
            return
        }
        overlay?.hide()
        fab?.hide()
        AppState.picking = true
        val saved = RuleStore.get(this, id)
        val initial = when (mode) {
            "REGIONS" -> saved?.regions?.toList() ?: emptyList()
            "TEMPLATE" -> listOfNotNull(saved?.tplRegion)
            else -> emptyList()
        }
        overlay = SelectorOverlay(this, mode, initial = initial, onFinish = { rects, pt, swipe ->
            AppState.picking = false
            overlay = null
            val rule = RuleStore.get(this, id)
            if (rule != null) {
                when (mode) {
                    "REGIONS" -> {
                        // lớp phủ đã hiện sẵn các vùng cũ để chỉnh: kết quả là DANH SÁCH VÙNG MỚI, thay cho danh sách cũ
                        val old = rule.regions.toList()
                        rule.regions = rects.toMutableList()
                        val changed = rects.filter { it !in old } // chỉ vùng mới / vùng vừa chỉnh mới cần chụp lại ảnh gốc
                        RuleStore.upsert(this, rule)
                        AppState.log("Đã lưu ${rects.size} vùng cho '${rule.name}' (${changed.size} vùng mới/đã chỉnh)")
                        // chờ lớp phủ chọn biến mất rồi cắt đúng vùng vừa khoanh làm ẢNH GỐC, xong mới mở lại màn hình sửa luật
                        AppToast.cancel() // toast còn hiện sẽ bị chụp vào ảnh gốc
                        main.postDelayed({
                            scope.launch {
                                val f = grab()
                                if (f != null) {
                                    try { ChangeMatcher.addOriginals(this@CaptureService, rule, changed, f) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { AppState.log("Lỗi lưu ảnh gốc: ${e.message}") }
                                }
                                main.post { showBubble(); reopenEditor(id) }
                            }
                        }, 700)
                    }
                    "POINT" -> {
                        if (pt != null) {
                            rule.macro = rule.macro.trimEnd() + "\n$kind ${pt.x} ${pt.y} $ms" + Macro.suffix(times, gap)
                            RuleStore.upsert(this, rule)
                        }
                        reopenEditor(id)
                    }
                    "SWIPE" -> {
                        if (swipe != null) {
                            val (a, b) = swipe
                            rule.macro = rule.macro.trimEnd() +
                                "\nswipe ${a.x} ${a.y} ${b.x} ${b.y} $ms" + Macro.suffix(times, gap)
                            RuleStore.upsert(this, rule)
                        }
                        reopenEditor(id)
                    }
                    "TEMPLATE" -> {
                        val rg = rects.lastOrNull()
                        if (rg != null) main.postDelayed({ saveTemplate(rule, rg); reopenEditor(id); showBubble() }, 450)
                        else showBubble()
                    }
                }
            }
            if (rule == null || (mode != "TEMPLATE" && mode != "REGIONS")) showBubble()
        }, onCancel = {
            AppState.picking = false
            overlay = null
            showBubble()
            reopenEditor(id)
        }).also { it.show() }
    }

    /* ---------------- ghi thao tác ---------------- */

    @Synchronized
    private fun snapshotFrame(): Bitmap? = grab()

    private fun startRecord() {
        if (recOv != null) return
        if (!Settings.canDrawOverlays(this)) {
            AppToast.show(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG); return
        }
        if (AutoClickService.instance == null) {
            AppToast.show(this, "Cần bật Dịch vụ trợ năng để ghi và phát lại thao tác", Toast.LENGTH_LONG); return
        }
        if (AppState.monitoring) setMonitoring(false)
        RecState.clear()
        AppState.picking = true
        fab?.hide()
        recOv = RecordOverlay(this, scope, { snapshotFrame() }, { main.post { stopRecord() } }).also { it.show() }
        AppState.log("● Bắt đầu ghi thao tác")
    }

    private fun stopRecord() {
        recOv?.hide(); recOv = null
        AppState.picking = false
        showBubble()
        AppState.log("■ Dừng ghi: ${RecState.steps.size} bước")
        if (RecState.steps.isEmpty()) {
            AppToast.show(this, "Chưa ghi được thao tác nào", Toast.LENGTH_SHORT)
        } else {
            startActivity(Intent(this, RecordPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Lớp phủ chỉnh điểm chạm / vuốt của luật: hiện số 1, 2, 3..., giữ ngón tay để thêm, chạm để chỉnh. */
    private fun pickActions(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        if (!Settings.canDrawOverlays(this)) {
            AppToast.show(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG)
            return
        }
        overlay?.hide(); overlay = null
        editor?.hide()
        fab?.hide()
        AppState.picking = true
        val base = rule.macro
        editor = ActionEditorOverlay(
            this, MacroEdit.parse(base).toMutableList(),
            onDone = { acts ->
                AppState.picking = false
                editor = null
                (RuleStore.get(this, id) ?: rule).let { r ->
                    r.macro = MacroEdit.apply(base, acts)
                    RuleStore.upsert(this, r)
                }
                AppState.log("Đã lưu ${acts.size} điểm chạm/vuốt cho '${rule.name}'")
                showBubble()
                reopenEditor(id)
            },
            onCancel = {
                AppState.picking = false
                editor = null
                // Huỷ: bỏ cả những gì đã tự lưu khi bấm Dừng, trả macro về như lúc mở
                (RuleStore.get(this, id) ?: rule).let { r ->
                    if (r.macro != base) { r.macro = base; RuleStore.upsert(this, r) }
                }
                showBubble()
                reopenEditor(id)
            },
            onSave = { acts ->
                (RuleStore.get(this, id) ?: rule).let { r ->
                    r.macro = MacroEdit.apply(base, acts)
                    RuleStore.upsert(this, r)
                }
                AppState.log("Đã lưu ${acts.size} điểm chạm/vuốt cho '${rule.name}'")
            },
        ).also { it.show() }
    }

    private fun saveTemplate(rule: Rule, rg: Region) {
        val f = grab()
        if (f == null) {
            AppToast.show(this, "Không lấy được khung hình", Toast.LENGTH_LONG); return
        }
        val l = rg.l.coerceIn(0, f.width - 1)
        val t = rg.t.coerceIn(0, f.height - 1)
        val w = (rg.r.coerceAtMost(f.width) - l).coerceAtLeast(1)
        val h = (rg.b.coerceAtMost(f.height) - t).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(f, l, t, w, h)
        FileOutputStream(RuleStore.templateFile(this, rule.id)).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.type = Rule.IMAGE
        rule.tplRegion = Region(l, t, l + w, t + h)
        RuleStore.upsert(this, rule)
        AppState.log("Đã lưu ảnh mẫu ${w}x$h cho '${rule.name}'")
    }

    /** Có quyền vẽ trên ứng dụng khác nên được phép mở lại màn hình sửa luật từ nền. */
    private fun reopenEditor(id: String) {
        startActivity(
            Intent(this, RuleEditActivity::class.java)
                .putExtra("id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    /* ---------------- thông báo & dọn dẹp ---------------- */

    @Suppress("DEPRECATION")
    private fun notif(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ScreenWatcher")
            .setContentText(if (AppState.monitoring) "Đang theo dõi màn hình" else "Sẵn sàng (chưa theo dõi)")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Dừng hẳn", stop)
            .setOngoing(true)
            .build()
    }

    private fun teardown() {
        loop?.cancel()
        overlay?.hide(); overlay = null
        editor?.hide(); editor = null
        recOv?.hide(); recOv = null
        fab?.hide(); fab = null
        vd?.release(); vd = null
        reader?.close(); reader = null
        runCatching { projection?.stop() }
        projection = null
        curW = 0; curH = 0
        AppState.monitoring = false
        AppState.picking = false
        AppState.projectionReady = false
    }

    private fun stopAll() {
        teardown()
        AppState.log("■ Đã dừng dịch vụ")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        testOv?.hide()
        teardown()
        scope.cancel()
        super.onDestroy()
    }
}
