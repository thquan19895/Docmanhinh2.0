package com.example.screenwatcher

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class RuleEditActivity : Activity() {

    private lateinit var rule: Rule
    private lateinit var name: EditText
    private lateinit var type: Spinner
    private lateinit var text: EditText
    private lateinit var fuzzy: CheckBox
    private lateinit var thr: EditText
    private lateinit var regionsTv: TextView
    private lateinit var tplIv: ImageView
    private lateinit var macro: EditText
    private lateinit var cool: EditText
    private lateinit var repeatEt: EditText
    private lateinit var repeatDelayEt: EditText
    private lateinit var delayBeforeEt: EditText
    private lateinit var textGroup: LinearLayout
    private lateinit var imageGroup: LinearLayout
    private lateinit var changeGroup: LinearLayout
    private lateinit var thrGroup: LinearLayout
    private lateinit var changeMode: Spinner
    private lateinit var diffThr: EditText
    private lateinit var baseTv: TextView
    private lateinit var thenSp: Spinner
    private var profiles: List<Profile> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.BLACK); setPadding(0, dp(12), 0, dp(2))
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        rule = intent.getStringExtra("id")?.let { RuleStore.get(this, it) } ?: Rule()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            fitsSystemWindows = true
        }

        root.addView(label("Tên luật"))
        name = EditText(this).apply { setText(rule.name) }
        root.addView(name)

        root.addView(label("Kiểu nhận diện"))
        type = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@RuleEditActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Chữ (OCR)", "Hình ảnh (mẫu)", "Phát hiện thay đổi (so với ban đầu)"),
            )
            setSelection(when (rule.type) { Rule.TEXT -> 0; Rule.IMAGE -> 1; else -> 2 })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = toggleGroups()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(type)

        // --- nhóm CHỮ ---
        textGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textGroup.addView(label("Chữ cần tìm (dùng | để tách nhiều lựa chọn, vd: Bỏ qua|Skip)"))
        text = EditText(this).apply { setText(rule.text) }
        textGroup.addView(text)
        fuzzy = CheckBox(this).apply { this.text = "Cho phép sai khác nhỏ (OCR đọc nhầm vài ký tự)"; isChecked = rule.fuzzy }
        textGroup.addView(fuzzy)
        root.addView(textGroup)

        // --- nhóm HÌNH ---
        imageGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        imageGroup.addView(label("Ảnh mẫu"))
        tplIv = ImageView(this).apply { adjustViewBounds = true; maxHeight = dp(120) }
        imageGroup.addView(tplIv)
        imageGroup.addView(btn("Chọn / chỉnh khung ảnh mẫu trên màn hình") { pick("TEMPLATE") })
        root.addView(imageGroup)

        // --- nhóm THAY ĐỔI ---
        changeGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        changeGroup.addView(label("So sánh bằng"))
        changeMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@RuleEditActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Hình ảnh (điểm ảnh của vùng đổi)", "Chữ (nội dung OCR của vùng đổi)"),
            )
            setSelection(if (rule.changeMode == Rule.CH_TEXT) 1 else 0)
        }
        changeGroup.addView(changeMode)
        changeGroup.addView(label("Ngưỡng thay đổi (0.01 – 1.00): vùng khác lúc đầu từ mức này trở lên thì chạy macro"))
        diffThr = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(rule.diffThreshold.toString())
        }
        changeGroup.addView(diffThr)
        changeGroup.addView(TextView(this).apply {
            textSize = 11f
            text = "Hình: trừ ảnh vùng hiện tại cho ảnh mốc, lấy mức lớn hơn giữa tỉ lệ điểm khác trên cả vùng và trên một khu vực nhỏ (icon nhỏ đổi chỗ cũng bắt được; tap_match chạm đúng chỗ thay đổi). Chữ: tỉ lệ ký tự khác. VD 0.10 = khác 10%; nhỏ hơn = nhạy hơn.\n" +
                "Ảnh gốc chính là ảnh của vùng bạn khoanh (lưu ngay lúc khoanh) và được giữ cố định; vùng quét cũng chính là vùng đó. " +
                "Mỗi lần quét, app cắt đúng vùng ấy rồi trừ cho ảnh gốc. Chạy, Thử hay dừng/chạy lại đều không đổi ảnh gốc. " +
                "Sau khi báo thay đổi, luật chỉ báo lại khi vùng đã trở về như ảnh gốc. Cần khoanh ít nhất 1 vùng."
        })
        baseTv = TextView(this).apply { setTextColor(Color.BLACK); setPadding(0, dp(8), 0, 0) }
        changeGroup.addView(baseTv)
        changeGroup.addView(btn("🖼 Chụp lại ảnh gốc của các vùng đã chọn (sau 4 giây)") {
            collect(); RuleStore.upsert(this, rule)
            if (!AppState.projectionReady) {
                AppToast.show(this, "Hãy cấp quyền chụp màn hình ở màn hình chính trước", Toast.LENGTH_LONG)
            } else {
                AppToast.show(this, "Chuyển sang màn hình gốc trong 4 giây…", Toast.LENGTH_LONG)
                startService(
                    Intent(this, CaptureService::class.java).setAction(CaptureService.A_BASELINE).putExtra("id", rule.id),
                )
            }
        })
        root.addView(changeGroup)

        thrGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        thrGroup.addView(label("Ngưỡng giống nhau (0.50 – 0.99)"))
        thr = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(rule.threshold.toString())
        }
        thrGroup.addView(thr)
        root.addView(thrGroup)

        root.addView(label("Vùng quét (để trống = toàn màn hình)"))
        regionsTv = TextView(this)
        root.addView(regionsTv)
        root.addView(btn("Chọn / chỉnh vùng trên màn hình (kéo vùng cũ để sửa, vẽ thêm để thêm)") { pick("REGIONS") })
        root.addView(btn("Xoá hết vùng") { rule.regions.clear(); ChangeMatcher.delete(this, rule.id); refreshRegions(); refreshBase() })

        root.addView(label("Macro khi khớp / khi có thay đổi (mỗi dòng 1 lệnh)"))
        macro = EditText(this).apply {
            setText(rule.macro); minLines = 3; gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            visibility = View.GONE // mặc định thu gọn: log macro (nhất là các dòng path=...) rất dài
        }
        fun lineCount() = macro.text.lines().count { it.isNotBlank() }
        val macroInfo = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY) }
        fun refreshMacroInfo() {
            macroInfo.text = "Macro: ${lineCount()} dòng" + if (macro.visibility == View.GONE) " (đang ẩn)" else ""
        }
        refreshMacroInfo()
        macro.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshMacroInfo()
        })
        var cleared: String? = null // nội dung vừa xoá, để hoàn tác
        val showBtn = btn("▾ Hiện macro") {}
        val clearBtn = btn("🗑 Xoá macro") {}
        showBtn.setOnClickListener {
            val show = macro.visibility == View.GONE
            macro.visibility = if (show) View.VISIBLE else View.GONE
            showBtn.text = if (show) "▴ Ẩn macro" else "▾ Hiện macro"
            refreshMacroInfo()
        }
        clearBtn.setOnClickListener {
            val prev = cleared
            if (prev == null) {
                cleared = macro.text.toString()
                macro.setText("")
                clearBtn.text = "↩ Hoàn tác xoá"
                Toast.makeText(this, "Đã xoá macro (bấm ↩ để khôi phục)", Toast.LENGTH_SHORT).show()
            } else {
                macro.setText(prev)
                cleared = null
                clearBtn.text = "🗑 Xoá macro"
            }
        }
        root.addView(macroInfo)
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(showBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            },
        )
        root.addView(macro)
        root.addView(TextView(this).apply {
            textSize = 11f
            text = "tap_match [dx dy]  chạm vào chỗ vừa tìm thấy\n" +
                "tap x y [ms]  |  longtap x y [ms]\n" +
                "swipe x1 y1 x2 y2 [ms]\n" +
                "Thêm  times=N gap=ms  vào cuối dòng chạm/giữ/vuốt để lặp N lần\n" +
                "  wait=ms: chờ rồi mới chạm (mặc định 120)  size=dp: cỡ nút\n" +
                "wait ms  |  back  |  home  |  recents\n" +
                "# ghi chú"
        })
        root.addView(btn("Chỉnh điểm chạm/vuốt trên màn hình (giữ để thêm 1, 2, 3…)") { pick("ACTIONS") })

        root.addView(label("Sau khi chạy xong thao tác: nạp và chạy cả bộ luật đã lưu (thay thế các luật đang dùng)"))
        profiles = ProfileStore.list(this)
        thenSp = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@RuleEditActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("(Không chạy bộ luật nào)") + profiles.map { "📂 ${it.name} (${it.ruleCount} luật)" },
            )
            val at = profiles.indexOfFirst { it.id == rule.thenProfile }
            setSelection(if (at >= 0) at + 1 else 0)
        }
        root.addView(thenSp)

        root.addView(label("Chờ bao lâu (ms) sau khi luật khớp rồi mới chạy thao tác (0 = chạy ngay)"))
        delayBeforeEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(rule.delayBeforeMs.toString())
        }
        root.addView(delayBeforeEt)

        root.addView(label("Sau khi chạy hết thao tác, lặp lại tổng cộng (lần). 1 = chỉ 1 lần, 0 = lặp mãi đến khi tắt theo dõi"))
        repeatEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(rule.repeatCount.toString())
        }
        root.addView(repeatEt)
        root.addView(label("Nghỉ giữa các lần lặp (ms)"))
        repeatDelayEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(rule.repeatDelayMs.toString())
        }
        root.addView(repeatDelayEt)

        root.addView(label("Thời gian nghỉ giữa 2 lần kích hoạt (ms)"))
        cool = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(rule.cooldownMs.toString())
        }
        root.addView(cool)

        root.addView(btn("Thử luật (quét sau 4 giây, chưa chạy macro)") {
            collect(); RuleStore.upsert(this, rule)
            if (!AppState.projectionReady) {
                AppToast.show(this, "Hãy cấp quyền chụp màn hình ở màn hình chính trước", Toast.LENGTH_LONG)
            } else {
                AppToast.show(this, "Chuyển sang màn hình cần kiểm tra trong 4 giây…", Toast.LENGTH_LONG)
                startService(
                    Intent(this, CaptureService::class.java).setAction(CaptureService.A_TEST).putExtra("id", rule.id),
                )
            }
        })
        root.addView(btn("Lưu") { collect(); RuleStore.upsert(this, rule); finish() })
        root.addView(btn("Xoá luật") { RuleStore.delete(this, rule.id); finish() })

        setContentView(ScrollView(this).apply { addView(root) })
        toggleGroups()
        refreshRegions()
        refreshTemplate()
        refreshBase()
    }

    override fun onResume() {
        super.onResume()
        if (::baseTv.isInitialized) refreshBase()
    }

    private fun refreshBase() {
        val n = ChangeMatcher.countOriginals(this, rule)
        val total = rule.regions.size
        baseTv.text = when {
            total == 0 -> "Ảnh gốc: chưa có — hãy khoanh vùng (vùng khoanh chính là ảnh gốc)"
            n >= total -> "Ảnh gốc: đã lưu cho $n/$total vùng"
            else -> "Ảnh gốc: mới có $n/$total vùng — bấm \"Chụp lại ảnh gốc\" hoặc khoanh lại vùng"
        }
    }

    private fun toggleGroups() {
        val pos = type.selectedItemPosition
        textGroup.visibility = if (pos == 0) View.VISIBLE else View.GONE
        imageGroup.visibility = if (pos == 1) View.VISIBLE else View.GONE
        changeGroup.visibility = if (pos == 2) View.VISIBLE else View.GONE
        thrGroup.visibility = if (pos == 2) View.GONE else View.VISIBLE
    }

    private fun refreshRegions() {
        regionsTv.text = if (rule.regions.isEmpty()) "Toàn màn hình"
        else rule.regions.withIndex().joinToString("\n") { (i, r) -> "${i + 1}. (${r.l},${r.t}) – (${r.r},${r.b})" }
    }

    private fun refreshTemplate() {
        val f = RuleStore.templateFile(this, rule.id)
        if (f.exists()) tplIv.setImageBitmap(BitmapFactory.decodeFile(f.path)) else tplIv.setImageDrawable(null)
    }

    private fun collect() {
        rule.name = name.text.toString().ifBlank { "Luật" }
        rule.type = when (type.selectedItemPosition) { 0 -> Rule.TEXT; 1 -> Rule.IMAGE; else -> Rule.CHANGE }
        rule.changeMode = if (changeMode.selectedItemPosition == 1) Rule.CH_TEXT else Rule.CH_IMAGE
        rule.diffThreshold = (diffThr.text.toString().toDoubleOrNull() ?: 0.10).coerceIn(0.01, 1.0)
        rule.text = text.text.toString()
        rule.fuzzy = fuzzy.isChecked
        rule.threshold = (thr.text.toString().toDoubleOrNull() ?: 0.85).coerceIn(0.5, 0.99)
        rule.macro = macro.text.toString()
        rule.cooldownMs = cool.text.toString().toLongOrNull() ?: 3000L
        rule.repeatCount = (repeatEt.text.toString().toIntOrNull() ?: 1).coerceIn(0, 100_000)
        rule.delayBeforeMs = (delayBeforeEt.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, 3_600_000L)
        rule.repeatDelayMs = (repeatDelayEt.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(0)
        rule.thenProfile = profiles.getOrNull(thenSp.selectedItemPosition - 1)?.id ?: ""
    }

    /** Lưu luật rồi giao cho CaptureService hiện lớp phủ chọn trên màn hình thật. */
    private fun pick(mode: String, extra: Intent.() -> Unit = {}) {
        collect()
        RuleStore.upsert(this, rule)
        if (!Settings.canDrawOverlays(this)) {
            AppToast.show(this, "Cần cấp quyền hiển thị trên ứng dụng khác (bước 1)", Toast.LENGTH_LONG); return
        }
        if (!AppState.projectionReady) {
            AppToast.show(this, "Cần cấp quyền chụp màn hình (bước 3) trước", Toast.LENGTH_LONG); return
        }
        startService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.A_PICK).putExtra("mode", mode).putExtra("id", rule.id).apply(extra),
        )
        AppToast.show(this, "Kéo trên lớp phủ để chọn. Bấm ✋ để thao tác app, ✏ để vẽ lại.", Toast.LENGTH_SHORT)
        // đưa ScreenWatcher xuống nền để hiện ngay màn hình ứng dụng gần nhất vừa dùng, rồi đóng màn hình sửa
        // (chọn xong / huỷ, dịch vụ sẽ tự mở lại màn hình sửa luật)
        moveTaskToBack(true)
        finish()
    }
}
