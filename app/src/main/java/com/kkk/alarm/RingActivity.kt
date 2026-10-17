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
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        setContentView(R.layout.activity_ring)

        val a = AlarmStore.get(this, intent.getIntExtra(Scheduler.EXTRA_ID, -1))
        findViewById<TextView>(R.id.ringLabel).text = a?.label?.ifEmpty { "おはよう" } ?: "おはよう"
        findViewById<Button>(R.id.btnSnooze).text = "スヌーズ  ${a?.snoozeMin ?: 5}分"

        findViewById<Button>(R.id.btnSnooze).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_SNOOZE); finish()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_DISMISS); finish()
        }
        // 戻るボタンで誤って消えないようにする
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        AlarmService.onFinished = { runOnUiThread { finish() } }
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
