package com.kkk.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var listView: LinearLayout
    private lateinit var banner: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listView = findViewById(R.id.alarmList)
        banner = findViewById(R.id.banner)
        empty = findViewById(R.id.emptyText)
        findViewById<View>(R.id.fab).setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }
        AlarmService.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Scheduler.rescheduleAll(this)
    }

    override fun onResume() {
        super.onResume()
        AlarmService.restoreVolumeIfNeeded(this)
        render()
        updateBanner()
    }

    private fun render() {
        listView.removeAllViews()
        val alarms = AlarmStore.all(this)
        empty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        val inf = LayoutInflater.from(this)
        for (a in alarms) {
            val row = inf.inflate(R.layout.item_alarm, listView, false)
            val time = row.findViewById<TextView>(R.id.itemTime)
            val days = row.findViewById<TextView>(R.id.itemDays)
            val toggle = row.findViewById<View>(R.id.itemToggle)
            val knob = row.findViewById<ImageView>(R.id.itemKnob)
            val track = row.findViewById<View>(R.id.itemTrack)
            val note = row.findViewById<ImageView>(R.id.itemNote)
            val noteLine = row.findViewById<View>(R.id.itemNoteLine)

            time.text = a.timeText()
            days.text = if (a.label.isNotEmpty()) "${a.daysText()}  ·  ${a.label}" else a.daysText()
            val until = Scheduler.snoozeUntil(this, a.id)
            if (until > 0L) {
                val t = java.text.SimpleDateFormat("H:mm", java.util.Locale.JAPAN).format(java.util.Date(until))
                days.text = "😴 スヌーズ中 → $t にまた鳴ります（タップで取消）"
                days.setTextColor(getColor(R.color.accent))
                days.setOnClickListener {
                    Scheduler.cancelSnooze(this, a.id)
                    Toast.makeText(this, "スヌーズを取り消しました", Toast.LENGTH_SHORT).show()
                    render()
                }
            }

            val on = a.enabled
            time.setTextColor(getColor(if (on) R.color.text else R.color.textOff))
            knob.setImageResource(if (on) R.drawable.ic_alarm else R.drawable.ic_close)
            knob.setBackgroundResource(if (on) R.drawable.knob_on else R.drawable.knob_off)
            knob.setColorFilter(getColor(if (on) R.color.bg else R.color.textOff2))
            track.setBackgroundResource(if (on) R.drawable.track_on else R.drawable.track_off)
            noteLine.setBackgroundColor(getColor(if (on) R.color.accent else R.color.textOff))
            note.setColorFilter(getColor(R.color.textOff2))

            toggle.setOnClickListener {
                a.enabled = !a.enabled
                AlarmStore.save(this, a)
                Scheduler.schedule(this, a)
                if (!a.enabled) Scheduler.cancelSnooze(this, a.id)
                if (a.enabled) toastUntil(this, a)
                render()
            }
            row.setOnClickListener { openEdit(a.id) }
            row.findViewById<View>(R.id.itemMore).setOnClickListener { v ->
                PopupMenu(this, v).apply {
                    menu.add(0, 1, 0, "編集")
                    menu.add(0, 2, 1, "削除")
                    setOnMenuItemClickListener {
                        if (it.itemId == 1) openEdit(a.id) else confirmDelete(a)
                        true
                    }
                }.show()
            }
            listView.addView(row)
        }
    }

    private fun openEdit(id: Int) =
        startActivity(Intent(this, EditActivity::class.java).putExtra(Scheduler.EXTRA_ID, id))

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this)
            .setMessage("${a.timeText()} のアラームを削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                Scheduler.cancel(this, a.id); AlarmStore.delete(this, a.id); render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 確実に鳴らすために足りない設定があれば上に表示 */
    private fun updateBanner() {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val (msg, action) = when {
            !nm.areNotificationsEnabled() ->
                "通知がOFFです。タップして許可してください" to {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            !Scheduler.canExact(this) ->
                "正確なアラームが許可されていません。タップして許可" to {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")))
                }
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                "ロック画面に表示する許可が必要です。タップして許可" to {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName")))
                }
            !pm.isIgnoringBatteryOptimizations(packageName) ->
                "電池の最適化を解除すると確実に鳴ります（タップ）" to {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                }
            else -> null to {}
        }
        if (msg == null) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            banner.text = msg
            banner.setOnClickListener {
                try { action() } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }
            }
        }
    }

    companion object {
        fun toastUntil(ctx: android.content.Context, a: Alarm) {
            val diff = (a.nextTrigger() - System.currentTimeMillis() + 59_999) / 60_000
            val d = diff / (60 * 24); val h = (diff / 60) % 24; val m = diff % 60
            val s = buildString {
                if (d > 0) append("${d}日")
                if (h > 0) append("${h}時間")
                append("${m}分後")
            }
            Toast.makeText(ctx, "$s に鳴ります", Toast.LENGTH_SHORT).show()
        }
    }
}
