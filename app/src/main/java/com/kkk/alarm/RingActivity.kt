package com.kkk.alarm

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RingActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_QUIP = "quip"
        const val EXTRA_QUIP_LABEL = "quip_label"
    }

    private val clock = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // ロック解除は求めない（ロックしたままこの画面を表示する）
        }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        setContentView(R.layout.activity_ring)

        val a = AlarmStore.get(this, intent.getIntExtra(Scheduler.EXTRA_ID, -1))
        findViewById<TextView>(R.id.ringLabel).text = a?.label?.ifEmpty { "おはよう" } ?: "おはよう"
        findViewById<Button>(R.id.btnSnooze).text =
            "スヌーズ  ${a?.let { Scheduler.nextSnoozeMin(this, it) } ?: 5}分"

        showQuip(intent.getStringExtra(EXTRA_QUIP_LABEL) ?: "", intent.getStringExtra(EXTRA_QUIP) ?: "")
        // タップで次のつぶやき
        findViewById<android.view.View>(R.id.quipCard).setOnClickListener {
            val q = Quips.pick(this, a?.let { Scheduler.snoozeCount(this, it.id) } ?: 0,
                a?.let { Scheduler.nextSnoozeMin(this, it) } ?: 5)
            showQuip(q.label, q.text)
        }

        findViewById<Button>(R.id.btnSnooze).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_SNOOZE, a?.id ?: AlarmService.ringingId); finish()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_DISMISS, a?.id ?: AlarmService.ringingId); finish()
        }
        // 戻るボタンで誤って消えないようにする
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        AlarmService.onFinished = { runOnUiThread { finish() } }
    }

    private fun showQuip(label: String, text: String) {
        val body: String
        val author: String
        val i = text.lastIndexOf(" — ")
        if (i > 0) { body = text.substring(0, i); author = "— " + text.substring(i + 3) } else { body = text; author = "" }
        findViewById<TextView>(R.id.quipLabel).text = label
        findViewById<TextView>(R.id.quipText).text = body.ifEmpty { "おはよう！" }
        findViewById<TextView>(R.id.quipBy).apply {
            this.text = author
            visibility = if (author.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.ringTime).text =
                SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date())
            clock.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (AlarmService.ringingId < 0) { finish(); return }
        clock.post(tick)
    }

    override fun onPause() {
        super.onPause()
        clock.removeCallbacks(tick)
    }

    override fun onDestroy() {
        AlarmService.onFinished = null
        super.onDestroy()
    }
}
