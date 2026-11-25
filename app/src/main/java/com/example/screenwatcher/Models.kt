package com.example.screenwatcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Một vùng chữ nhật trên màn hình (toạ độ pixel thật). */
data class Region(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    fun toJson(): JSONArray = JSONArray().put(l).put(t).put(r).put(b)

    companion object {
        fun from(a: JSONArray) = Region(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
    }
}

/** Một luật: nhìn vào các vùng -> nếu thấy chữ/hình thì chạy macro. */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Luật mới",
    var type: String = TEXT,
    var text: String = "",
    var fuzzy: Boolean = true,
    var threshold: Double = 0.85,
    var regions: MutableList<Region> = mutableListOf(),
    var macro: String = "tap_match",
    var cooldownMs: Long = 3000,
    var enabled: Boolean = true,
    /** Sau khi chạy hết macro: lặp lại tổng cộng bấy nhiêu lần (1 = chỉ 1 lần, 0 = lặp mãi đến khi tắt theo dõi). */
    var repeatCount: Int = 1,
    var repeatDelayMs: Long = 500,
    /** Kiểu CHANGE: so sánh bằng hình (CH_IMAGE) hay bằng chữ OCR (CH_TEXT). */
    var changeMode: String = CH_IMAGE,
    /** Kiểu CHANGE: vùng khác so với lúc đầu từ mức này (0.01..1.0) trở lên thì tính là có thay đổi. */
    var diffThreshold: Double = 0.10,
    /** Khi luật chạy xong macro: nạp rồi chạy cả bộ luật đã lưu có id này ("" = không). */
    var thenProfile: String = "",
    /** Kiểu IMAGE: khung đã khoanh để cắt ảnh mẫu (để lần sau mở lên chỉ cần chỉnh khung, không phải vẽ lại). */
    var tplRegion: Region? = null,
    /** Sau khi luật khớp: chờ bấy nhiêu ms rồi mới chạy macro (0 = chạy ngay). */
    var delayBeforeMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("text", text)
        .put("fuzzy", fuzzy).put("threshold", threshold).put("macro", macro)
        .put("cooldownMs", cooldownMs).put("enabled", enabled)
        .put("repeatCount", repeatCount).put("repeatDelayMs", repeatDelayMs)
        .put("delayBeforeMs", delayBeforeMs).put("changeMode", changeMode).put("diffThreshold", diffThreshold).put("thenProfile", thenProfile)
        .put("regions", JSONArray().also { a -> regions.forEach { a.put(it.toJson()) } })
        .also { o -> tplRegion?.let { o.put("tplRegion", it.toJson()) } }

    companion object {
        const val TEXT = "TEXT"
        const val IMAGE = "IMAGE"
        /** Phát hiện sự thay đổi của một vùng so với lúc ban đầu. */
        const val CHANGE = "CHANGE"
        const val CH_IMAGE = "IMAGE"
        const val CH_TEXT = "TEXT"

        fun from(o: JSONObject): Rule {
            val regs = mutableListOf<Region>()
            val a = o.optJSONArray("regions") ?: JSONArray()
            for (i in 0 until a.length()) regs.add(Region.from(a.getJSONArray(i)))
            return Rule(
                id = o.getString("id"),
                name = o.optString("name", "Luật"),
                type = o.optString("type", TEXT).let { if (it == "AI") TEXT else it },
                text = o.optString("text", ""),
                fuzzy = o.optBoolean("fuzzy", true),
                threshold = o.optDouble("threshold", 0.85),
                regions = regs,
                macro = o.optString("macro", "tap_match"),
                cooldownMs = o.optLong("cooldownMs", 3000),
                enabled = o.optBoolean("enabled", true) && o.optString("type", TEXT) != "AI",
                repeatCount = o.optInt("repeatCount", 1),
                repeatDelayMs = o.optLong("repeatDelayMs", 500),
                changeMode = o.optString("changeMode", CH_IMAGE),
                diffThreshold = o.optDouble("diffThreshold", 0.10),
                thenProfile = o.optString("thenProfile", ""),
                delayBeforeMs = o.optLong("delayBeforeMs", 0L),
                tplRegion = o.optJSONArray("tplRegion")?.let { runCatching { Region.from(it) }.getOrNull() },
            )
        }
    }
}

object RuleStore {
    private fun prefs(c: Context) = c.getSharedPreferences("sw", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Rule> {
        val a = JSONArray(prefs(c).getString("rules", "[]"))
        return MutableList(a.length()) { Rule.from(a.getJSONObject(it)) }
    }

    fun saveAll(c: Context, l: List<Rule>) {
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("rules", a.toString()).apply()
    }

    fun get(c: Context, id: String): Rule? = load(c).firstOrNull { it.id == id }

    fun upsert(c: Context, r: Rule) {
        val l = load(c)
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l.add(r)
        saveAll(c, l)
    }

    fun delete(c: Context, id: String) {
        saveAll(c, load(c).filter { it.id != id })
        templateFile(c, id).delete()
        ChangeMatcher.delete(c, id)
    }

    fun templateFile(c: Context, id: String) = File(c.filesDir, "tpl_$id.png")

    /** Các bộ luật đang được nạp / gộp trong danh sách luật hiện tại (id), theo thứ tự nạp. */
    fun activeProfiles(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("activeProfiles", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
    fun setActiveProfiles(c: Context, ids: List<String>) =
        prefs(c).edit().putString("activeProfiles", JSONArray(ids).toString()).apply()

    fun bubbleOn(c: Context): Boolean = prefs(c).getBoolean("bubble", true)
    fun setBubbleOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bubble", on).apply()
    fun bubblePos(c: Context): Pair<Int, Int> = prefs(c).getInt("bx", 0) to prefs(c).getInt("by", 300)
    fun setBubblePos(c: Context, x: Int, y: Int) = prefs(c).edit().putInt("bx", x).putInt("by", y).apply()

    /** Chạy tuần tự: luật 1 -> 2 -> 3 ... xong hết thì lặp lại theo số vòng (0 = mãi). */
    fun seqOn(c: Context): Boolean = prefs(c).getBoolean("seqOn", false)
    fun setSeqOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("seqOn", on).apply()
    /** Thời gian nghỉ (ms) giữa hai vòng khi chạy tuần tự (0 = không nghỉ). */
    fun seqGap(c: Context): Long = prefs(c).getLong("seqGap", 0L)
    fun setSeqGap(c: Context, ms: Long) = prefs(c).edit().putLong("seqGap", ms.coerceIn(0L, 86_400_000L)).apply()
    fun seqCycles(c: Context): Int = prefs(c).getInt("seqCycles", 1)
    fun setSeqCycles(c: Context, n: Int) = prefs(c).edit().putInt("seqCycles", n.coerceIn(0, 100_000)).apply()

    /** Hiện hiệu ứng chạm / vuốt trên màn hình khi macro chạy. */
    fun feedbackOn(c: Context): Boolean = prefs(c).getBoolean("feedback", true)
    fun setFeedbackOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("feedback", on).apply()

    /* ---- hẹn giờ & giới hạn ---- */
    /** Giờ tự bắt đầu / tự dừng tính bằng số phút từ 00:00; *On = có bật hay không. */
    fun schedStartOn(c: Context) = prefs(c).getBoolean("schedStartOn", false)
    fun schedStartMin(c: Context) = prefs(c).getInt("schedStartMin", 8 * 60)
    fun schedStopOn(c: Context) = prefs(c).getBoolean("schedStopOn", false)
    fun schedStopMin(c: Context) = prefs(c).getInt("schedStopMin", 18 * 60)
    fun setSchedStart(c: Context, on: Boolean, min: Int) =
        prefs(c).edit().putBoolean("schedStartOn", on).putInt("schedStartMin", min.coerceIn(0, 1439)).apply()
    fun setSchedStop(c: Context, on: Boolean, min: Int) =
        prefs(c).edit().putBoolean("schedStopOn", on).putInt("schedStopMin", min.coerceIn(0, 1439)).apply()
    /** Tự dừng theo dõi sau bấy nhiêu phút kể từ lúc bắt đầu (0 = không giới hạn). */
    fun autoStopMin(c: Context) = prefs(c).getInt("autoStopMin", 0)
    fun setAutoStopMin(c: Context, n: Int) = prefs(c).edit().putInt("autoStopMin", n.coerceIn(0, 100_000)).apply()

    fun interval(c: Context): Long = prefs(c).getLong("interval", 700L)
    fun setInterval(c: Context, ms: Long) = prefs(c).edit().putLong("interval", ms.coerceIn(150L, 10_000L)).apply()
}

/** Trạng thái dùng chung trong tiến trình. */
object AppState {
    @Volatile var projectionReady = false
    @Volatile var monitoring = false
    @Volatile var picking = false
    @Volatile var seqStatus = ""
    @Volatile var projCode = 0
    @Volatile var projData: android.content.Intent? = null

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lines.addFirst("$t  $s")
        while (lines.size > 40) lines.removeLast()
    }

    @Synchronized
    fun logText(): String = lines.joinToString("\n")
}
