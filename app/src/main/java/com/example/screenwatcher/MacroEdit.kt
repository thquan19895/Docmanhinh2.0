package com.example.screenwatcher

/** Một thao tác chạm / giữ / vuốt có toạ độ tuyệt đối trong macro. */
class TouchAct(
    var kind: String,          // "tap" | "longtap" | "swipe"
    var x1: Int,
    var y1: Int,
    var x2: Int = 0,           // điểm cuối (chỉ dùng cho swipe)
    var y2: Int = 0,
    var ms: Long = MacroEdit.defaultMs(kind),
    var times: Int = 1,
    var gap: Long = 150L,
    var wait: Long = 120L,     // chờ bao lâu (ms) rồi mới chạm điểm này
    var size: Int = 44,        // kích thước nút trên màn hình (dp)
    var pts: FloatArray? = null, // đường đi đã ghi của cú vuốt: x0,y0,x1,y1,... (null = vuốt thẳng từ điểm đầu tới điểm cuối)
    var rec: Boolean = false,  // true = thao tác ghi bằng nút Ghi (không hiện trên màn hình chỉnh); false = điểm thêm tay (hiện)
    val line: Int = -1,        // chỉ số dòng gốc trong macro (-1 = thao tác mới)
) {
    fun toLine(): String =
        (if (kind == "swipe") "swipe $x1 $y1 $x2 $y2 $ms" + pathOpt() else "$kind $x1 $y1 $ms") +
            Macro.suffix(times, gap, wait, size) + (if (rec) " rec=1" else "")

    /** path=x,y;x,y;... chỉ ghi khi đường đi còn khớp điểm đầu / điểm cuối (kéo điểm đi chỗ khác thì bỏ đường cong, vuốt thẳng). */
    private fun pathOpt(): String {
        val p = pts ?: return ""
        if (p.size < 6 || p.size % 2 != 0) return ""
        if (p[0].toInt() != x1 || p[1].toInt() != y1 || p[p.size - 2].toInt() != x2 || p[p.size - 1].toInt() != y2) return ""
        val sb = StringBuilder(" path=")
        for (i in p.indices step 2) {
            if (i > 0) sb.append(';')
            sb.append(p[i].toInt()).append(',').append(p[i + 1].toInt())
        }
        return sb.toString()
    }

    /** Nội dung lúc mới đọc, để biết thao tác có bị sửa hay không. */
    val sig: String = toLine()
}

/** Đọc / ghi các dòng chạm - giữ - vuốt trong macro, giữ nguyên các dòng khác. */
object MacroEdit {
    fun defaultMs(kind: String): Long = when (kind) {
        "longtap" -> 800L
        "swipe" -> 300L
        else -> 60L
    }

    /** Đọc path=x,y;x,y;... thành mảng x0,y0,x1,y1,...; sai định dạng thì trả null. */
    fun parsePath(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        val out = ArrayList<Float>()
        for (pt in s.split(';')) {
            val xy = pt.split(',')
            val x = xy.getOrNull(0)?.toFloatOrNull() ?: return null
            val y = xy.getOrNull(1)?.toFloatOrNull() ?: return null
            out.add(x); out.add(y)
        }
        return if (out.size >= 4) out.toFloatArray() else null
    }

    fun parse(script: String): List<TouchAct> {
        val out = mutableListOf<TouchAct>()
        val lines = script.lines()
        for (idx in lines.indices) {
            val line = lines[idx].substringBefore('#').trim()
            if (line.isEmpty()) continue
            val p = line.split(Regex("\\s+"))
            val rest = p.drop(1)
            val opts = rest.filter { it.contains('=') }
                .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            val a = rest.filter { !it.contains('=') }.mapNotNull { it.toFloatOrNull() }
            val times = (opts["times"]?.toIntOrNull() ?: 1).coerceIn(1, 100_000)
            val gap = (opts["gap"]?.toLongOrNull() ?: 150L).coerceAtLeast(0)
            val ms = opts["ms"]?.toLongOrNull()
            val wait = (opts["wait"]?.toLongOrNull() ?: 120L).coerceAtLeast(0)
            val size = (opts["size"]?.toIntOrNull() ?: 44).coerceIn(16, 200)
            val rec = opts["rec"] == "1"
            val k = p[0].lowercase()
            if ((k == "tap" || k == "longtap") && a.size >= 2) {
                out.add(
                    TouchAct(
                        k, a[0].toInt(), a[1].toInt(),
                        ms = a.getOrNull(2)?.toLong() ?: ms ?: defaultMs(k),
                        times = times, gap = gap, wait = wait, size = size, rec = rec, line = idx,
                    ),
                )
            } else if (k == "swipe" && a.size >= 4) {
                out.add(
                    TouchAct(
                        "swipe", a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3].toInt(),
                        ms = a.getOrNull(4)?.toLong() ?: ms ?: defaultMs("swipe"),
                        times = times, gap = gap, wait = wait, size = size, pts = parsePath(opts["path"]), rec = rec, line = idx,
                    ),
                )
            }
        }
        return out
    }

    /** Ghi thay đổi (sửa / xoá / thêm mới) trở lại macro; các dòng wait, back, tap_match, ghi chú... giữ nguyên. */
    fun apply(script: String, acts: List<TouchAct>): String {
        val lines = script.lines()
        val touchLines = parse(script).map { it.line }.toSet()
        val keep = acts.filter { it.line >= 0 }.associateBy { it.line }
        val out = mutableListOf<String>()
        for (i in lines.indices) {
            if (i in touchLines) {
                val a = keep[i] ?: continue // đã bị xoá
                out.add(if (a.toLine() == a.sig) lines[i] else a.toLine())
            } else {
                out.add(lines[i])
            }
        }
        for (a in acts) if (a.line < 0) out.add(a.toLine())
        return out.joinToString("\n").trim()
    }
}
