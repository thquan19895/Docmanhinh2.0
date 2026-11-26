package com.example.screenwatcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Màn hình xem trước các bước vừa ghi: hình thu nhỏ từng bước + cảnh báo ảnh mẫu yếu, rồi lưu thành bộ luật. */
class RecordPreviewActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var nameEt: EditText
    private lateinit var saveBtn: Button
    private lateinit var runBtn: Button
    private val h = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            render()
            if (RecState.steps.any { !it.ready }) h.postDelayed(this, 600)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val ink = 0xFF212121.toInt()

    private fun tv(text: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(18), dp(14), dp(18))
        }
        root.addView(tv("Xem trước thao tác đã ghi", 20f, bold = true))
        summary = tv("", 13f, 0xFF555555.toInt()).apply { setPadding(0, dp(4), 0, dp(10)) }
        root.addView(summary)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(tv("Tên bộ luật", 13f, 0xFF555555.toInt()).apply { setPadding(0, dp(14), 0, 0) })
        nameEt = EditText(this).apply {
            setTextColor(ink)
            setText("Ghi " + SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date()))
        }
        root.addView(nameEt)

        saveBtn = Button(this).apply { text = "💾 Lưu thành bộ luật"; setOnClickListener { save(false) } }
        runBtn = Button(this).apply { text = "▶ Lưu và chạy ngay"; setOnClickListener { save(true) } }
        root.addView(saveBtn)
        root.addView(runBtn)
        root.addView(tv("Chạy ngay sẽ thay danh sách luật đang dùng bằng bộ này (luật hiện tại của bạn không bị mất nếu đã lưu thành bộ luật).", 12f, 0xFF777777.toInt()))
        root.addView(Button(this).apply {
            text = "Huỷ, bỏ bản ghi"
            setOnClickListener { RecState.clear(); finish() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); h.post(poll) }
    override fun onPause() { super.onPause(); h.removeCallbacks(poll) }

    /** Thanh trượt ngưỡng 0.60–0.98 kèm số đề xuất và giải thích. */
    private fun thresholdRow(title: String, cur: Double, sug: Double, note: String, onSet: (Double) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lab = tv("", 13f, ink, true)
        fun upd(v: Double) { lab.text = title + ": " + "%.2f".format(v) + "  (đề xuất " + "%.2f".format(sug) + ")" }
        upd(cur)
        box.addView(lab)
        box.addView(SeekBar(this).apply {
            max = 38
            progress = (Math.round(cur * 100).toInt() - 60).coerceIn(0, 38)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (60 + p) / 100.0
                    onSet(v); upd(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        box.addView(tv("• $note\n• Cao = khớp chặt, ít nhận nhầm nhưng dễ bỏ sót; thấp = ngược lại.", 12f, 0xFF555555.toInt()))
        box.addView(Button(this).apply { text = "Dùng đề xuất"; setOnClickListener { onSet(sug); render() } })
        return box
    }

    private fun kindName(s: RecStep) = when (s.kind) { "swipe" -> "Vuốt"; "longtap" -> "Chạm giữ"; else -> "Chạm" }

    private fun render() {
        val steps = RecState.steps.toList()
        list.removeAllViews()
        val pending = steps.count { !it.ready }
        val weak = steps.count { it.ready && it.activeLevel() >= 1 }
        summary.text = if (steps.isEmpty()) "Chưa có bước nào."
        else "Đã ghi ${steps.size} bước" + (if (pending > 0) " · đang phân tích $pending bước…" else " · $weak bước đang chọn cách nhận diện yếu (⚠/❌)") +
            ".\nMỗi bước chọn nhận diện bằng Ảnh, Chữ (OCR) hoặc Toạ độ cố định (nối vào bước trước). Bước 1 không dùng được toạ độ cố định."
        val first = steps.firstOrNull()
        val ok = first != null && pending == 0 && (first.crop != null || first.textLevel >= 0)
        saveBtn.isEnabled = ok; runBtn.isEnabled = ok
        if (first != null && pending == 0 && !ok) {
            summary.text = summary.text.toString() + "\n⚠ Bước 1 không có ảnh lẫn chữ (màn hình bị chặn chụp?) nên chưa lưu được."
        }

        for ((i, s) in steps.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(if (i % 2 == 0) 0xFFF5F5F5.toInt() else 0xFFEEEEEE.toInt())
            }
            val th = s.thumb
            if (th != null) {
                card.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(th)
                }, LinearLayout.LayoutParams(dp(104), dp(104)))
            } else {
                card.addView(tv(if (s.ready) "(không có ảnh)" else "Đang phân tích…", 12f, 0xFF777777.toInt()).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(104), dp(104)))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            val gapTxt = if (i == 0) "" else " · nghỉ ${"%.1f".format(s.gapBeforeMs / 1000.0)}s"
            col.addView(tv("Bước ${i + 1} · ${kindName(s)}$gapTxt", 15f, bold = true))
            if (s.ready) {
                fun lvl(l: Int) = when (l) {
                    0 -> "✅ Tốt" to 0xFF2E7D32.toInt()
                    1 -> "⚠ Yếu" to 0xFFEF6C00.toInt()
                    else -> "❌ Rất yếu" to 0xFFC62828.toInt()
                }
                val (ic, co) = lvl(s.level)
                col.addView(tv("Ảnh: $ic", 13f, co, true))
                col.addView(tv(s.reasons.joinToString("\n") { "• $it" }, 12f, 0xFF555555.toInt()))
                if (s.textLevel >= 0) {
                    val (tc, tcol) = lvl(s.textLevel)
                    col.addView(tv("Chữ: $tc  “${s.ocrRaw}”", 13f, tcol, true))
                }
                col.addView(tv(s.textReasons.joinToString("\n") { "• $it" }, 12f, 0xFF555555.toInt()))

                val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                fun radio(label: String, m: Int, enabled: Boolean) {
                    group.addView(RadioButton(this).apply {
                        text = label; setTextColor(ink); isEnabled = enabled && !(i == 0 && m == RecStep.FIXED)
                        id = View.generateViewId()
                        isChecked = RecBuild.effectiveMode(s, i) == m
                        setOnClickListener { s.mode = m; render() }
                    })
                }
                radio("Nhận diện bằng ảnh", RecStep.IMAGE, s.crop != null)
                radio("Nhận diện bằng chữ (OCR)", RecStep.TEXT, s.textLevel >= 0)
                radio("Chạm toạ độ cố định", RecStep.FIXED, true)
                col.addView(group)
                when (RecBuild.effectiveMode(s, i)) {
                    RecStep.IMAGE -> col.addView(thresholdRow("Ngưỡng ảnh", s.imgThreshold, s.imgSuggested, s.imgThrNote) { s.imgThreshold = it })
                    RecStep.TEXT -> col.addView(thresholdRow("Ngưỡng chữ", s.textThreshold, s.textSuggested, s.textThrNote) { s.textThreshold = it })
                }
                if (RecBuild.effectiveMode(s, i) == RecStep.TEXT) {
                    col.addView(tv("Chữ cần tìm (sửa nếu OCR đọc sai; dùng | để thêm lựa chọn):", 12f, 0xFF555555.toInt()))
                    col.addView(EditText(this).apply {
                        setTextColor(ink); setText(s.editedText); textSize = 14f
                        addTextChangedListener(object : TextWatcher {
                            override fun afterTextChanged(e: Editable?) { s.editedText = e?.toString() ?: "" }
                            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                        })
                    })
                }
            }
            col.addView(Button(this).apply {
                text = "Xoá bước"
                setOnClickListener { RecState.steps.remove(s); render() }
            })
            card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun save(run: Boolean) {
        val steps = RecState.steps.toList()
        if (steps.isEmpty() || steps.any { !it.ready }) return
        val name = nameEt.text.toString().trim().ifEmpty { "Ghi thao tác" }
        saveBtn.isEnabled = false; runBtn.isEnabled = false
        Thread {
            val result = try {
                val rules = RecBuild.build(this, steps, "Bước")
                if (rules.isEmpty()) null else RecBuild.saveProfile(this, name, rules)
            } catch (e: Exception) { AppState.log("Lỗi lưu bản ghi: ${e.message}"); null }
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(this, "Không lưu được bộ luật", Toast.LENGTH_LONG).show()
                    render()
                } else {
                    AppState.log("💾 Đã lưu bộ luật '${result.name}' (${result.ruleCount} luật) từ bản ghi")
                    RecState.clear()
                    if (run) {
                        startService(
                            Intent(this, CaptureService::class.java).setAction(CaptureService.A_PROFILE)
                                .putExtra("id", result.id).putExtra("run", true),
                        )
                    }
                    Toast.makeText(this, "Đã lưu bộ luật '${result.name}' (${result.ruleCount} luật)", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }
}
