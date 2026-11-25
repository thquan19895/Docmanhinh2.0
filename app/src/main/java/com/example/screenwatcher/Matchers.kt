package com.example.screenwatcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Kết quả khớp: toạ độ tâm (trong bitmap được quét) + độ giống 0..1 */
data class Hit(val x: Int, val y: Int, val score: Double, val scale: Double = 1.0)

/* ------------------------------------------------------------------ */
/*  Nhận diện CHỮ (OCR bằng ML Kit, offline)                          */
/* ------------------------------------------------------------------ */
object TextMatcher {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Vùng nhỏ hơn mức này (số điểm ảnh) mới thử thêm lượt OCR "phóng to + tăng tương phản" khi lượt đầu không thấy. */
    private const val ENHANCE_MAX_PX = 700_000L

    /** Chữ thường, bỏ dấu tiếng Việt, bỏ khoảng trắng/ký tự đặc biệt -> so khớp bền hơn với lỗi OCR. */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("đ", "d")
        return buildString {
            for (c in n) {
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt() && c.isLetterOrDigit()) append(c)
            }
        }
    }

    /**
     * Gộp các ký tự OCR hay đọc nhầm thành một (o/0, l/i/1, s/5, b/8, z/2, u/v, q/g/9).
     * Chỉ đổi 1 ký tự -> 1 ký tự nên độ dài và vị trí ký tự giữ nguyên. Dùng cho cả mẫu lẫn chữ đọc được.
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(
            when (c) {
                'o' -> '0'
                'l', 'i' -> '1'
                's' -> '5'
                'b' -> '8'
                'z' -> '2'
                'u' -> 'v'
                'q', '9' -> 'g'
                else -> c
            },
        )
        return sb.toString()
    }

    private class Match(val score: Double, val start: Int, val end: Int)

    /**
     * Tìm chuỗi con gần giống nhất (Sellers) và trả cả vị trí. Độ giống >= minRatio mới tính.
     * Quét hết văn bản để lấy chỗ khớp TỐT NHẤT, không dừng ở chỗ khớp đầu tiên.
     */
    private fun approx(text: String, pat: String, minRatio: Double): Match? {
        if (pat.isEmpty() || text.isEmpty()) return null
        val ix = text.indexOf(pat)
        if (ix >= 0) return Match(1.0, ix, ix + pat.length)
        val m = pat.length
        val allowed = floor(m * (1 - minRatio)).toInt()
        if (allowed <= 0) return null
        var prev = IntArray(m + 1) { it }
        var bestD = Int.MAX_VALUE
        var bestEnd = -1
        for ((k, c) in text.withIndex()) {
            val cur = IntArray(m + 1)
            for (i in 1..m) {
                cur[i] = minOf(prev[i] + 1, cur[i - 1] + 1, prev[i - 1] + if (pat[i - 1] == c) 0 else 1)
            }
            if (cur[m] < bestD) { bestD = cur[m]; bestEnd = k + 1 }
            prev = cur
        }
        if (bestD > allowed || bestEnd < 0) return null
        return Match(1.0 - bestD.toDouble() / m, max(0, bestEnd - m), bestEnd)
    }

    private suspend fun recognize(bmp: Bitmap): Text = suspendCancellableCoroutine { c ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    /** Đọc toàn bộ chữ trong ảnh, đã chuẩn hoá (dùng cho so sánh thay đổi). */
    suspend fun readNorm(bmp: Bitmap): String = norm(recognize(bmp).text)

    /** Phóng to 2x, chuyển xám và tăng tương phản: giúp OCR đọc chữ nhỏ/mờ. */
    private fun enhance(src: Bitmap): Bitmap {
        val big = Bitmap.createScaledBitmap(src, src.width * 2, src.height * 2, true)
        val out = Bitmap.createBitmap(big.width, big.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val k = 1.8f
        val t = (-0.5f * k + 0.5f) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(big, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        big.recycle()
        return out
    }

    /** Duyệt kết quả OCR, lấy chỗ khớp tốt nhất; toạ độ nhân [inv] để quy về ảnh gốc (lượt phóng to thì inv = 0.5). */
    private fun scan(res: Text, bmp: Bitmap, pats: List<String>, ratio: Double, inv: Double): Hit? {
        var best: Hit? = null
        for (line in res.textBlocks.flatMap { it.lines }) {
            val els = line.elements
            val parts = if (els.isEmpty()) listOf(fold(norm(line.text))) else els.map { fold(norm(it.text)) }
            val t = parts.joinToString("")
            if (t.isEmpty()) continue
            for (p in pats) {
                val mt = approx(t, p, ratio) ?: continue
                if (best != null && mt.score <= best.score) continue
                // hộp bao các từ nằm trong đoạn khớp -> chạm đúng chỗ khớp, không phải tâm cả dòng
                var box: Rect? = null
                if (els.isNotEmpty()) {
                    var off = 0
                    for ((n, e) in els.withIndex()) {
                        val len = parts[n].length
                        if (len > 0 && off < mt.end && off + len > mt.start) {
                            e.boundingBox?.let { b -> box = box?.also { it.union(b) } ?: Rect(b) }
                        }
                        off += len
                    }
                }
                val r = box ?: line.boundingBox ?: continue
                best = Hit(
                    (r.centerX() * inv).roundToInt(), (r.centerY() * inv).roundToInt(), mt.score,
                )
            }
        }
        if (best != null) return best
        // Cụm chữ nằm vắt qua nhiều dòng
        val all = fold(norm(res.text))
        for (p in pats) {
            val mt = approx(all, p, ratio) ?: continue
            if (best == null || mt.score > best.score) best = Hit(bmp.width / 2, bmp.height / 2, mt.score)
        }
        return best
    }

    /** rule.text có thể chứa nhiều lựa chọn, ngăn cách bằng dấu | (khớp 1 trong số đó là đủ). */
    suspend fun find(bmp: Bitmap, rule: Rule): Hit? {
        val pats = rule.text.split("|").map { fold(norm(it)) }.filter { it.isNotEmpty() }
        if (pats.isEmpty()) return null
        val ratio = if (rule.fuzzy) rule.threshold else 1.0
        scan(recognize(bmp), bmp, pats, ratio, 1.0)?.let { return it }
        // Không thấy: với vùng nhỏ, đọc lại bản phóng to + tăng tương phản (vùng lớn bỏ qua để khỏi chậm)
        if (bmp.width.toLong() * bmp.height <= ENHANCE_MAX_PX) {
            val e = enhance(bmp)
            try {
                return scan(recognize(e), bmp, pats, ratio, 0.5)
            } finally {
                e.recycle()
            }
        }
        return null
    }
}

/* ------------------------------------------------------------------ */
/*  Nhận diện HÌNH ẢNH (template matching NCC, đa tỉ lệ, thuần Kotlin) */
/* ------------------------------------------------------------------ */
object TemplateMatcher {
    private class G(val w: Int, val h: Int, val d: FloatArray)

    /** Pixel của một bitmap, đọc đúng 1 lần để dùng lại cho nhiều mức thu nhỏ. */
    private class Px(val w: Int, val h: Int, val px: IntArray) {
        constructor(b: Bitmap) : this(
            b.width, b.height,
            IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) },
        )
    }

    // Các tỉ lệ thử (ảnh trong vùng quét / ảnh mẫu): 1.0, 1.15, 0.87, 1.32, 0.76 ... trong [0.4 .. 2.5]
    private const val SCALE_MIN = 0.4
    private const val SCALE_MAX = 2.5
    private const val SCALE_STEP = 1.15
    private val SCALES: List<Double> = run {
        val l = mutableListOf(1.0)
        var k = SCALE_STEP
        while (k <= SCALE_MAX) {
            l.add(k)
            if (1.0 / k >= SCALE_MIN) l.add(1.0 / k)
            k *= SCALE_STEP
        }
        l
    }

    private fun gray(p: Px, s: Int): G {
        val w = p.w
        val gw = w / s
        val gh = p.h / s
        val out = FloatArray(gw * gh)
        val inv = 1f / (s * s)
        for (y in 0 until gh) for (x in 0 until gw) {
            var acc = 0f
            for (dy in 0 until s) {
                val row = (y * s + dy) * w + x * s
                for (dx in 0 until s) {
                    val q = p.px[row + dx]
                    acc += 0.299f * ((q shr 16) and 255) + 0.587f * ((q shr 8) and 255) + 0.114f * (q and 255)
                }
            }
            out[y * gw + x] = acc * inv
        }
        return G(gw, gh, out)
    }

    private fun gray(b: Bitmap, s: Int): G = gray(Px(b), s)

    /** Trả về (x, y góc trên-trái, điểm NCC) tốt nhất. */
    private fun search(img: G, t: G): Triple<Int, Int, Double>? {
        val tw = t.w
        val th = t.h
        val n = tw * th
        if (tw < 3 || th < 3 || img.w < tw || img.h < th) return null
        var mean = 0f
        for (v in t.d) mean += v
        mean /= n
        val tz = FloatArray(n) { t.d[it] - mean }
        var tn = 0.0
        for (v in tz) tn += v * v
        tn = sqrt(tn)
        if (tn < 1e-3) return null // mẫu trơn, không có chi tiết

        val iw = img.w + 1
        val sum = DoubleArray(iw * (img.h + 1))
        val sq = DoubleArray(iw * (img.h + 1))
        for (y in 1..img.h) {
            var rs = 0.0
            var rq = 0.0
            for (x in 1..img.w) {
                val v = img.d[(y - 1) * img.w + x - 1].toDouble()
                rs += v
                rq += v * v
                sum[y * iw + x] = sum[(y - 1) * iw + x] + rs
                sq[y * iw + x] = sq[(y - 1) * iw + x] + rq
            }
        }
        var best = -2.0
        var bx = 0
        var by = 0
        for (y in 0..img.h - th) for (x in 0..img.w - tw) {
            val a = (y + th) * iw + x + tw
            val b = y * iw + x + tw
            val c = (y + th) * iw + x
            val d = y * iw + x
            val s = sum[a] - sum[b] - sum[c] + sum[d]
            val q = sq[a] - sq[b] - sq[c] + sq[d]
            val v = q - s * s / n
            if (v < 1e-3) continue
            var num = 0.0
            for (j in 0 until th) {
                val ro = (y + j) * img.w + x
                val to = j * tw
                for (i in 0 until tw) num += img.d[ro + i] * tz[to + i]
            }
            val sc = num / (sqrt(v) * tn)
            if (sc > best) {
                best = sc; bx = x; by = y
            }
        }
        return if (best < -1) null else Triple(bx, by, best)
    }

    /** Các kênh màu của một ảnh (float 0..255) + độ sáng. */
    private class Ch(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray, val y: FloatArray)

    private fun channels(bm: Bitmap): Ch {
        val w = bm.width
        val h = bm.height
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val y = FloatArray(w * h)
        for (i in px.indices) {
            val q = px[i]
            val rr = ((q shr 16) and 255).toFloat()
            val gg = ((q shr 8) and 255).toFloat()
            val bb = (q and 255).toFloat()
            r[i] = rr; g[i] = gg; b[i] = bb
            y[i] = 0.299f * rr + 0.587f * gg + 0.114f * bb
        }
        return Ch(w, h, r, g, b, y)
    }

    /** Mẫu đã trừ trung bình của một kênh; [ok] = false nếu kênh gần như phẳng (không có chi tiết để so). */
    private class Tz(val z: FloatArray, val norm: Double, val ok: Boolean)

    private fun tz(t: FloatArray): Tz {
        var m = 0.0
        for (v in t) m += v
        m /= t.size
        val z = FloatArray(t.size) { (t[it] - m).toFloat() }
        var n = 0.0
        for (v in z) n += v * v
        n = sqrt(n)
        return Tz(z, n, n > 2.0)
    }

    /** NCC của ô [tw]x[th] tại (ox, oy) trên kênh [src] (rộng [sw]) so với mẫu [t]. */
    private fun nccAt(src: FloatArray, sw: Int, ox: Int, oy: Int, t: Tz, tw: Int, th: Int): Double {
        var s = 0.0
        var q = 0.0
        var dot = 0.0
        for (j in 0 until th) {
            val ro = (oy + j) * sw + ox
            val to = j * tw
            for (i in 0 until tw) {
                val v = src[ro + i].toDouble()
                s += v; q += v * v; dot += v * t.z[to + i]
            }
        }
        val n = tw * th
        val vr = q - s * s / n
        if (vr < 1e-3) return 0.0
        return dot / (sqrt(vr) * t.norm)
    }

    private class Fin(val x: Int, val y: Int, val score: Double)

    /**
     * Tinh lần cuối ở độ phân giải ĐẦY ĐỦ quanh (gx, gy): chọn vị trí đúng từng điểm ảnh theo NCC độ sáng,
     * rồi kiểm tra MÀU bằng NCC từng kênh R/G/B. Điểm cuối = min(độ sáng, màu trung bình)
     * nên icon cùng hình khác màu không còn bị coi là giống.
     */
    private fun finalCheck(region: Bitmap, st: Bitmap, gx: Int, gy: Int, win: Int): Fin? {
        val sw = st.width
        val sh = st.height
        val x0 = (gx - win).coerceIn(0, region.width - sw)
        val y0 = (gy - win).coerceIn(0, region.height - sh)
        val rw = min(region.width - x0, sw + 2 * win)
        val rh = min(region.height - y0, sh + 2 * win)
        if (rw < sw || rh < sh) return null
        val sub = Bitmap.createBitmap(region, x0, y0, rw, rh)
        val rc = channels(sub)
        if (sub !== region) sub.recycle()
        val tc = channels(st)
        val ty = tz(tc.y)
        if (!ty.ok) return null
        var best = -2.0
        var bx = 0
        var by = 0
        for (dy in 0..rh - sh) for (dx in 0..rw - sw) {
            val v = nccAt(rc.y, rc.w, dx, dy, ty, sw, sh)
            if (v > best) { best = v; bx = dx; by = dy }
        }
        if (best < -1) return null
        var cs = 0.0
        var cn = 0
        for ((src, tch) in listOf(rc.r to tc.r, rc.g to tc.g, rc.b to tc.b)) {
            val t = tz(tch)
            if (!t.ok) continue
            cs += nccAt(src, rc.w, bx, by, t, sw, sh)
            cn++
        }
        val color = if (cn == 0) best else cs / cn
        return Fin(x0 + bx, y0 + by, min(best, color))
    }

    /** Hệ số thu nhỏ cho bước tìm thô: mẫu ~10px, và giới hạn khối lượng tính. */
    private fun coarseFactor(rw: Int, rh: Int, tw: Int, th: Int): Int {
        val tmin = min(tw, th)
        var s = max(1, tmin / 10)
        while (tmin / (s + 1) >= 4 &&
            (rw / s).toLong() * (rh / s) * ((tw / s) * (th / s)) > 80_000_000L
        ) s++
        return s
    }

    private fun scaled(tpl: Bitmap, w: Int, h: Int): Bitmap =
        if (w == tpl.width && h == tpl.height) tpl else Bitmap.createScaledBitmap(tpl, w, h, true)

    /**
     * Tìm [tpl] trong [region], chấp nhận ảnh trong vùng quét có tỉ lệ khác ảnh mẫu.
     * 1) Thô: thử nhiều tỉ lệ (0.4x..2.5x) ở độ phân giải thấp, chọn tỉ lệ có điểm cao nhất.
     * 2) Tinh: quanh vị trí đó, thử thêm tỉ lệ lân cận (±6%) ở độ phân giải cao.
     * 3) Cuối: định vị đúng từng điểm ảnh ở độ phân giải đầy đủ và kiểm tra thêm MÀU (NCC từng kênh R/G/B).
     */
    fun find(region: Bitmap, tpl: Bitmap, threshold: Double): Hit? {
        val rpx = Px(region)
        val cache = HashMap<Int, G>() // ảnh xám của vùng quét theo hệ số thu nhỏ

        var bestK = 0.0
        var bestScore = -2.0
        var bx = 0
        var by = 0
        var bs = 1
        var bsw = 0
        var bsh = 0
        for (k in SCALES) {
            val sw = (tpl.width * k).roundToInt()
            val sh = (tpl.height * k).roundToInt()
            if (sw < 4 || sh < 4 || sw > region.width || sh > region.height) continue
            val st = scaled(tpl, sw, sh)
            val s = coarseFactor(region.width, region.height, sw, sh)
            val rg = cache.getOrPut(s) { gray(rpx, s) }
            val c = search(rg, gray(st, s))
            if (st !== tpl) st.recycle()
            if (c == null) continue
            if (c.third > bestScore) {
                bestScore = c.third; bestK = k
                bx = c.first; by = c.second; bs = s; bsw = sw; bsh = sh
            }
            if (bestScore >= max(0.9, threshold)) break // đã đủ tốt, khỏi thử tiếp
        }
        if (bestK == 0.0 || bestScore < threshold - 0.2) return null

        // Tâm của vị trí tìm thô (toạ độ vùng quét)
        val cx = bx * bs + bsw / 2
        val cy = by * bs + bsh / 2

        var outScore = -2.0
        var ox = 0
        var oy = 0
        var ofs = 1
        var osw = 0
        var osh = 0
        var ok = 0.0
        for (d in doubleArrayOf(1.0, 0.94, 1.06)) {
            val k = bestK * d
            val sw = (tpl.width * k).roundToInt()
            val sh = (tpl.height * k).roundToInt()
            if (sw < 4 || sh < 4 || sw > region.width || sh > region.height) continue
            val st = scaled(tpl, sw, sh)

            val rw = min(region.width, sw + 4 * bs)
            val rh = min(region.height, sh + 4 * bs)
            val rx = (cx - sw / 2 - 2 * bs).coerceIn(0, region.width - rw)
            val ry = (cy - sh / 2 - 2 * bs).coerceIn(0, region.height - rh)
            val sub = Bitmap.createBitmap(region, rx, ry, rw, rh)

            // Mẫu lớn thì tinh ở độ phân giải vừa phải để không quá nặng
            var fs = 1
            while (fs < bs &&
                ((rw - sw) / fs + 1).toLong() * ((rh - sh) / fs + 1) * ((sw / fs) * (sh / fs)) > 40_000_000L
            ) fs++

            val f = search(gray(sub, fs), gray(st, fs))
            if (st !== tpl) st.recycle()
            if (sub !== region) sub.recycle()
            if (f == null) continue
            if (f.third > outScore) {
                outScore = f.third
                ox = rx + f.first * fs; oy = ry + f.second * fs
                ofs = fs; osw = sw; osh = sh; ok = k
            }
        }
        if (osw == 0 || outScore < threshold - 0.1) return null

        // Bước cuối: độ phân giải đầy đủ + kiểm tra màu (loại các kết quả khớp "hình dáng" nhưng sai màu)
        val st = scaled(tpl, osw, osh)
        val fin = finalCheck(region, st, ox, oy, min(ofs, 3) + 1)
        if (st !== tpl) st.recycle()
        if (fin == null || fin.score < threshold) return null
        return Hit(fin.x + osw / 2, fin.y + osh / 2, fin.score, ok)
    }
}
