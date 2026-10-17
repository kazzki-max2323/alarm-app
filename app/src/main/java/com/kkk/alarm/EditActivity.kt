package com.kkk.alarm

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class EditActivity : AppCompatActivity() {
    private lateinit var alarm: Alarm
    private var isNew = false
    private val dayViews = mutableListOf<TextView>()
    private var preview: Track? = null
    private val handler = Handler(Looper.getMainLooper())

    private val snoozeOptions = intArrayOf(1, 3, 5, 7, 10, 15)
    private val rampOptions = intArrayOf(0, 30, 60, 120, 180, 300)

    private val pickSound = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            alarm.playlistId = r.data?.getIntExtra(PlaylistActivity.EXTRA_PLAYLIST_ID, 0) ?: 0
            alarm.sounds = mutableListOf()
        }
        updateCards()   // 編集・削除されたかもしれないので毎回更新
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val existing = if (id >= 0) AlarmStore.get(this, id) else null
        isNew = existing == null
        alarm = existing ?: Alarm(id = AlarmStore.newId(this)).also { a ->
            // 前回使った曲・設定を引き継ぐ
            AlarmStore.all(this).lastOrNull()?.let {
                a.playlistId = it.playlistId
                a.snoozeMin = it.snoozeMin; a.rampSec = it.rampSec
            }
        }

        val hour = findViewById<NumberPicker>(R.id.pickHour)
        val min = findViewById<NumberPicker>(R.id.pickMinute)
        hour.minValue = 0; hour.maxValue = 23; hour.value = alarm.hour
        min.minValue = 0; min.maxValue = 59; min.value = alarm.minute
        min.setFormatter { String.format("%02d", it) }
        hour.wrapSelectorWheel = true; min.wrapSelectorWheel = true
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val px = 40 * resources.displayMetrics.scaledDensity
            for (p in listOf(hour, min)) {
                p.textSize = px
                p.setTextColor(getColor(R.color.text))
                p.selectionDividerHeight = (3 * resources.displayMetrics.density).toInt()
            }
        }

        val label = findViewById<EditText>(R.id.editLabel)
        label.setText(alarm.label)

        val ids = intArrayOf(R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6)
        ids.forEachIndexed { i, vid ->
            val v = findViewById<TextView>(vid)
            dayViews.add(v)
            v.setOnClickListener {
                alarm.days = alarm.days xor (1 shl i)
                updateDays()
            }
        }
        updateDays()
        updateCards()

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.cardSound).setOnClickListener {
            stopPreview()
            pickSound.launch(Intent(this, PlaylistActivity::class.java)
                .putExtra(PlaylistActivity.EXTRA_PLAYLIST_ID, alarm.playlistId))
        }
        findViewById<View>(R.id.cardSnooze).setOnClickListener {
            val labels = snoozeOptions.map { snoozeText(it) }.toTypedArray()
            AlertDialog.Builder(this).setTitle("スヌーズ（1回ごとに1分ずつ短く）")
                .setSingleChoiceItems(labels, snoozeOptions.indexOf(alarm.snoozeMin)) { d, w ->
                    alarm.snoozeMin = snoozeOptions[w]; updateCards(); d.dismiss()
                }.show()
        }
        findViewById<View>(R.id.cardRamp).setOnClickListener {
            val labels = rampOptions.map { rampText(it) }.toTypedArray()
            AlertDialog.Builder(this).setTitle("最大音量になるまで")
                .setSingleChoiceItems(labels, rampOptions.indexOf(alarm.rampSec)) { d, w ->
                    alarm.rampSec = rampOptions[w]; updateCards(); d.dismiss()
                }.show()
        }
        findViewById<TextView>(R.id.btnPreview).setOnClickListener {
            if (preview != null) stopPreview() else startPreview()
        }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            alarm.hour = hour.value
            alarm.minute = min.value
            alarm.label = label.text.toString().trim()
            alarm.enabled = true
            AlarmStore.save(this, alarm)
            Scheduler.cancelSnooze(this, alarm.id)   // 時刻を変えたら古いスヌーズは取り消す
            Scheduler.schedule(this, alarm)
            MainActivity.toastUntil(this, alarm)
            finish()
        }
    }

    private fun updateDays() {
        dayViews.forEachIndexed { i, v ->
            val on = alarm.days and (1 shl i) != 0
            v.setBackgroundResource(if (on) R.drawable.day_on else R.drawable.day_off)
            v.setTextColor(getColor(if (on) R.color.bg else R.color.textOff2))
        }
        findViewById<TextView>(R.id.daysSummary).text = alarm.daysText()
    }

    private fun snoozeText(m: Int) = when (m) {
        1 -> "ずっと1分"
        2 -> "2分→1分"
        3 -> "3分→2分→1分"
        else -> "${m}分→${m - 1}分→…→1分"
    }

    private fun rampText(s: Int) = when {
        s == 0 -> "すぐ最大音量"
        s < 60 -> "${s}秒かけて"
        else -> "${s / 60}分かけて"
    }

    private fun updateCards() {
        val pl = PlaylistStore.get(this, alarm.playlistId)
        if (pl == null && alarm.sounds.isEmpty()) alarm.playlistId = 0
        val songs = alarm.songs(this)
        findViewById<TextView>(R.id.soundTitle).text = when {
            pl != null -> "${pl.name}（${pl.sounds.size}曲${if (pl.sounds.size > 1) "・ランダム" else ""}）"
            songs.isNotEmpty() -> "音楽"
            else -> "標準のアラーム音"
        }
        findViewById<TextView>(R.id.soundName).text = when {
            songs.isEmpty() -> "タップして曲リストを選ぶ・作る"
            songs.size <= 3 -> songs.joinToString("\n") { it.name }
            else -> songs.take(3).joinToString("\n") { it.name } + "\n他 ${songs.size - 3}曲"
        }
        findViewById<TextView>(R.id.snoozeValue).text = snoozeText(alarm.snoozeMin)
        findViewById<TextView>(R.id.rampValue).text = rampText(alarm.rampSec)
    }

    private fun fileName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "曲"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (_: Exception) { }
        return name.substringBeforeLast('.')
    }

    // プレビュー：5秒で音量が上がりきる
    private fun startPreview() {
        // 選んだ曲からランダムに1曲（鳴るときと同じ）
        val uri = alarm.songs(this).randomOrNull()?.let { Uri.parse(it.uri) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val t = Playback.open(this, uri, attrs, loop = true, startVolume = 0.03f)
        if (t == null) {
            Toast.makeText(this, "この曲を再生できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        preview = t
        findViewById<TextView>(R.id.btnPreview).text = "停止"
        val start = System.currentTimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                val p = preview ?: return
                val x = ((System.currentTimeMillis() - start) / 5000f).coerceIn(0f, 1f)
                p.setVolume(0.03f + 0.97f * x * x)
                if (x < 1f) handler.postDelayed(this, 150)
            }
        })
    }

    private fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        preview?.release()
        preview = null
        findViewById<TextView>(R.id.btnPreview).text = "プレビュー"
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }
}
