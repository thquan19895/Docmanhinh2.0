package com.example.screenwatcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Toast của app. Toast nằm trong khung hình chụp màn hình, nên nếu còn hiện lúc chụp "ảnh gốc" hoặc lúc "Thử"
 * thì chữ của nó bị tính là THAY ĐỔI. Dùng [cancel] ngay trước khi chụp để toast biến mất.
 */
object AppToast {
    private val main = Handler(Looper.getMainLooper())
    private var cur: Toast? = null

    fun show(c: Context, msg: String, len: Int = Toast.LENGTH_SHORT) {
        val run = Runnable {
            cur?.cancel()
            cur = Toast.makeText(c.applicationContext, msg, len).also { it.show() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    fun cancel() {
        main.post { cur?.cancel(); cur = null }
    }
}
