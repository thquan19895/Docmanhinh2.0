package com.example.screenwatcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Một bộ luật đã lưu: tên, thời điểm lưu, số luật (chỉ để hiển thị). */
data class Profile(val id: String, val name: String, val savedAt: Long, val ruleCount: Int)

/**
 * Bộ luật cài sẵn ("preset"): chụp lại toàn bộ danh sách luật hiện tại + ảnh mẫu + các cài đặt chạy
 * (tuần tự, số vòng, chu kỳ quét) dưới một cái tên. Mỗi tác vụ lưu một bộ riêng, sau này chỉ cần chọn và chạy.
 *
 * Lưu ở  filesDir/profiles/<id>/profile.json  và các ảnh mẫu  tpl_<ruleId>.png  cùng thư mục.
 */
object ProfileStore {
    private const val FILE = "profile.json"

    private fun root(c: Context) = File(c.filesDir, "profiles").apply { mkdirs() }
    private fun dir(c: Context, id: String) = File(root(c), id)
    internal fun dirOf(c: Context, id: String) = dir(c, id)

    private fun readJson(c: Context, id: String): JSONObject? = runCatching {
        JSONObject(File(dir(c, id), FILE).readText())
    }.getOrNull()

    fun list(c: Context): List<Profile> =
        (root(c).listFiles() ?: emptyArray()).mapNotNull { d ->
            val o = readJson(c, d.name) ?: return@mapNotNull null
            Profile(d.name, o.optString("name", "Bộ luật"), o.optLong("savedAt", 0L), o.optJSONArray("rules")?.length() ?: 0)
        }.sortedBy { it.name.lowercase() }

    fun findByName(c: Context, name: String): Profile? =
        list(c).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Lưu trạng thái hiện tại thành bộ luật tên [name]. Trùng tên thì ghi đè. Trả về bộ luật đã lưu. */
    fun saveCurrent(c: Context, name: String): Profile {
        val clean = name.trim().ifEmpty { "Bộ luật" }
        val id = findByName(c, clean)?.id ?: UUID.randomUUID().toString()
        val d = dir(c, id)
        d.deleteRecursively()
        d.mkdirs()

        val rules = RuleStore.load(c)
        val now = System.currentTimeMillis()
        val o = JSONObject()
            .put("name", clean).put("savedAt", now)
            .put("seqOn", RuleStore.seqOn(c)).put("seqCycles", RuleStore.seqCycles(c)).put("seqGap", RuleStore.seqGap(c))
            .put("interval", RuleStore.interval(c))
            .put("rules", JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } })
        File(d, FILE).writeText(o.toString())
        RuleStore.setActiveProfiles(c, listOf(id))

        for (r in rules) {
            val src = RuleStore.templateFile(c, r.id)
            if (src.exists()) src.copyTo(File(d, src.name), overwrite = true)
            val bf = ChangeMatcher.baseFile(c, r.id)
            if (bf.exists()) bf.copyTo(File(d, bf.name), overwrite = true)
        }
        ProfileBackup.async { ProfileBackup.export(c, id) } // chép thêm ra thư mục sao lưu (nếu đã chọn)
        return Profile(id, clean, now, rules.size)
    }

    /**
     * Nạp bộ luật. [merge] = false: thay toàn bộ luật + ảnh mẫu + cài đặt hiện tại bằng nội dung đã lưu.
     * [merge] = true: GỘP thêm vào các luật đang có (để canh nhiều app cùng lúc), giữ nguyên luật cũ,
     * tên luật gắn thêm [tên bộ luật] và tự chuyển sang chạy song song (tắt chế độ tuần tự).
     */
    fun load(c: Context, id: String, merge: Boolean = false): Boolean {
        val o = readJson(c, id) ?: return false
        val a = o.optJSONArray("rules") ?: JSONArray()
        val rules = runCatching { List(a.length()) { Rule.from(a.getJSONObject(it)) } }.getOrNull() ?: return false

        if (merge) return mergeInto(c, id, o, rules)

        // xoá ảnh mẫu của bộ luật đang dùng rồi chép ảnh mẫu của bộ luật được chọn
        if (!merge) (c.filesDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("tpl_") && it.name.endsWith(".png") }
            .forEach { it.delete() }
        (dir(c, id).listFiles() ?: emptyArray())
            .filter { it.name.startsWith("tpl_") && it.name.endsWith(".png") }
            .forEach { it.copyTo(File(c.filesDir, it.name), overwrite = true) }

        // ảnh gốc "thay đổi" đi theo bộ luật
        if (!merge) (c.filesDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("base_") && it.name.endsWith(".bin") }
            .forEach { it.delete() }
        (dir(c, id).listFiles() ?: emptyArray())
            .filter { it.name.startsWith("base_") && it.name.endsWith(".bin") }
            .forEach { it.copyTo(File(c.filesDir, it.name), overwrite = true) }

        rules.forEach { it.enabled = true } // nạp bộ luật xong thì mọi luật đều được tích sẵn, chờ chạy
        RuleStore.saveAll(c, rules)
        RuleStore.setActiveProfiles(c, listOf(id))
        RuleStore.setSeqOn(c, o.optBoolean("seqOn", false))
        RuleStore.setSeqCycles(c, o.optInt("seqCycles", 1))
        RuleStore.setSeqGap(c, o.optLong("seqGap", 0L))
        RuleStore.setInterval(c, o.optLong("interval", 700L))
        return true
    }

    /**
     * Gộp bộ luật [id] vào danh sách hiện tại. Mỗi luật được gộp nhận id MỚI (kèm ảnh mẫu / ảnh gốc), vì các bộ luật
     * lưu từ cùng một danh sách có chung id luật — nếu giữ nguyên id thì bộ gộp sau sẽ đè mất bộ gộp trước.
     */
    private fun mergeInto(c: Context, id: String, o: JSONObject, rules: List<Rule>): Boolean {
        val active = RuleStore.activeProfiles(c)
        if (active.firstOrNull() == id) return true // đây là bộ đang nạp làm nền, đã có sẵn
        val tag = o.optString("name", "").trim()
        val suffix = " [$tag]"
        // gộp lại bộ đã gộp trước đó: bỏ bản cũ của nó
        val kept = RuleStore.load(c).filter { tag.isEmpty() || !it.name.endsWith(suffix) }
        val d = dir(c, id)
        val added = rules.map { r ->
            val nid = UUID.randomUUID().toString()
            val tpl = File(d, "tpl_${r.id}.png")
            if (tpl.exists()) tpl.copyTo(RuleStore.templateFile(c, nid), overwrite = true)
            val base = File(d, "base_${r.id}.bin")
            if (base.exists()) base.copyTo(ChangeMatcher.baseFile(c, nid), overwrite = true)
            r.copy(id = nid, name = if (tag.isEmpty()) r.name else r.name + suffix, enabled = true)
        }
        RuleStore.saveAll(c, kept + added)
        RuleStore.setSeqOn(c, false) // nhiều bộ luật cùng canh: chạy song song
        RuleStore.setActiveProfiles(c, (active.filter { it != id } + id))
        return true
    }

    /** Tên các bộ luật đang nạp / gộp, theo thứ tự (bỏ qua bộ đã bị xoá). */
    fun activeNames(c: Context): List<String> {
        val all = list(c)
        return RuleStore.activeProfiles(c).mapNotNull { id -> all.firstOrNull { it.id == id }?.name }
    }

    fun rename(c: Context, id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        val other = findByName(c, clean)
        if (other != null && other.id != id) return false
        val o = readJson(c, id) ?: return false
        val oldName = o.optString("name", "")
        File(dir(c, id), FILE).writeText(o.put("name", clean).toString())
        ProfileBackup.async { ProfileBackup.deleteByName(c, oldName); ProfileBackup.export(c, id) }
        return true
    }

    fun delete(c: Context, id: String) {
        val name = readJson(c, id)?.optString("name", "") ?: ""
        dir(c, id).deleteRecursively()
        if (name.isNotEmpty()) ProfileBackup.async { ProfileBackup.deleteByName(c, name) }
    }
}
