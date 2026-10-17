package com.kkk.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        if (id < 0) return
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_RING)
                .putExtra(Scheduler.EXTRA_ID, id)
                .putExtra(Scheduler.EXTRA_SNOOZE, intent.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false)))
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.rescheduleAll(ctx)
}

class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "ring"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_DISMISS = "dismiss"
        const val CHANNEL = "alarm_ring"
        private const val NOTIF_ID = 1
        private const val AUTO_STOP_MS = 10 * 60_000L   // 10分鳴り続けたら自動スヌーズ

        /** 鳴動画面を閉じるためのコールバック */
        var onFinished: (() -> Unit)? = null
        var ringingId: Int = -1

        /** 鳴動中に強制終了した等で音量が下がったままなら戻す */
        fun restoreVolumeIfNeeded(ctx: Context) {
            if (ringingId >= 0) return
            val prefs = ctx.getSharedPreferences("vol", Context.MODE_PRIVATE)
            val target = prefs.getInt("alarm_target", -1)
            if (target > 0) {
                try {
                    ctx.getSystemService(android.media.AudioManager::class.java)
                        .setStreamVolume(android.media.AudioManager.STREAM_ALARM, target, 0)
                } catch (_: Exception) { }
                prefs.edit().remove("alarm_target").apply()
            }
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
        }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "アラーム", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private var player: Track? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var current: Alarm? = null
    private var rampStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val a = AlarmStore.get(this, intent.getIntExtra(Scheduler.EXTRA_ID, -1))
                if (a == null) { stopSelf(); return START_NOT_STICKY }
                // 決まった時刻に鳴った（スヌーズではない）ならスヌーズ間隔を最初に戻す
                if (!intent.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false)) Scheduler.resetSnooze(this, a.id)
                ring(a)
            }
            ACTION_SNOOZE -> {
                current?.let { Scheduler.snooze(this, it) }
                finish()
            }
            ACTION_DISMISS -> {
                current?.let { Scheduler.resetSnooze(this, it.id) }
                finish()
            }
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ring(a: Alarm) {
        stopPlayback()
        current = a
        ringingId = a.id

        // 次回分を予約（1回のみならOFFにする）
        if (a.days == 0) {
            a.enabled = false
            AlarmStore.save(this, a)
        } else {
            Scheduler.schedule(this, a)
        }

        ensureChannel(this)
        val quip = Quips.pick(this, Scheduler.snoozeCount(this, a.id), Scheduler.nextSnoozeMin(this, a))
        val ringIntent = Intent(this, RingActivity::class.java)
            .putExtra(Scheduler.EXTRA_ID, a.id)
            .putExtra(RingActivity.EXTRA_QUIP, quip.text)
            .putExtra(RingActivity.EXTRA_QUIP_LABEL, quip.label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val fullPi = PendingIntent.getActivity(this, 1, ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozePi = PendingIntent.getService(this, 2,
            Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 3,
            Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS), PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(a.timeText() + if (a.label.isNotEmpty()) "  " + a.label else "")
            .setContentText(quip.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(quip.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .setOngoing(true)
            .addAction(0, "スヌーズ ${Scheduler.nextSnoozeMin(this, a)}分", snoozePi)
            .addAction(0, "停止", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        try { startActivity(ringIntent) } catch (_: Exception) { }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarm:ring").apply { acquire(AUTO_STOP_MS + 60_000) }

        startPlayback(a)
        handler.postDelayed({ current?.let { Scheduler.snooze(this, it) }; finish() }, AUTO_STOP_MS)
    }

    private var lastUri: String? = null
    private var audio: AudioManager? = null
    /** ユーザーが設定していたアラーム音量（目盛り）。最後にここへ戻す */
    private var alarmTarget = -1
    private var appliedIndex = -1
    private var appliedStream = -1

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun startPlayback(a: Alarm) {
        lastUri = getSharedPreferences("play", MODE_PRIVATE).getString("last", null)
        val am = getSystemService(AudioManager::class.java)
        audio = am
        val prefs = getSharedPreferences("vol", MODE_PRIVATE)
        // 前回うまく戻せなかった場合は、保存しておいた本来の音量を使う
        alarmTarget = prefs.getInt("alarm_target", -1).takeIf { it > 0 }
            ?: am.getStreamVolume(AudioManager.STREAM_ALARM)
        prefs.edit().putInt("alarm_target", alarmTarget).apply()
        appliedIndex = -1; appliedStream = -1
        rampStart = System.currentTimeMillis()
        playNext(a)
        handler.post(rampTick)
    }

    /**
     * 今の音量を反映する。
     * 前半はスマホの音量を一番小さい目盛りに固定して、曲の音量をほぼ無音からじわじわ上げる。
     * 後半はスマホの音量の目盛りを、設定していた大きさまで少しずつ上げる。
     */
    private fun applyVolume(a: Alarm) {
        val t0 = player ?: return
        val am = audio ?: return
        val total = a.rampSec * 1000f
        val t = if (total <= 0f) 1f else ((System.currentTimeMillis() - rampStart) / total).coerceIn(0f, 1f)

        val stream = t0.stream
        val maxIdx = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val minIdx = 1
        val target = if (stream == AudioManager.STREAM_ALARM) alarmTarget
            else Math.round(alarmTarget.toFloat() / alarmMax * maxIdx).coerceAtLeast(minIdx)

        val phase1 = 0.2f   // 最初の2割の時間は、最小の目盛りで曲の音量を上げる
        val gain: Float
        val idx: Int
        if (t < phase1 && target > minIdx) {
            val x = t / phase1
            gain = 0.02f + 0.98f * x * x
            idx = minIdx
        } else if (target <= minIdx) {
            gain = 0.02f + 0.98f * t * t
            idx = minIdx
        } else {
            val x = (t - phase1) / (1f - phase1)
            gain = 1f
            idx = (minIdx + Math.ceil((target - minIdx) * x.toDouble()).toInt()).coerceIn(minIdx, target)
        }
        if (idx != appliedIndex || stream != appliedStream) {
            try { am.setStreamVolume(stream, idx, 0) } catch (_: Exception) { }
            appliedIndex = idx; appliedStream = stream
        }
        t0.setVolume(gain)
    }

    private fun restoreAlarmVolume() {
        val am = audio ?: getSystemService(AudioManager::class.java)
        if (alarmTarget > 0) {
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, alarmTarget, 0) } catch (_: Exception) { }
        }
        alarmTarget = -1
        getSharedPreferences("vol", MODE_PRIVATE).edit().remove("alarm_target").apply()
    }

    /** 選んだ曲からランダムに1曲（直前と同じ曲は避ける）。曲が終わったら次の曲へ */
    private fun playNext(a: Alarm) {
        player?.release()
        player = null

        val all = a.songs(this)
        val songs = all.shuffled().toMutableList()
        if (songs.size > 1 && songs.first().uri == lastUri) songs.add(songs.removeAt(0))
        val candidates = songs.map { Uri.parse(it.uri) }.toMutableList()
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { candidates.add(it) }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { candidates.add(it) }

        for (u in candidates) {
            // 1曲だけ・標準音ならループ、複数曲なら終わったら次へ
            val multi = all.size > 1 && all.any { it.uri == u.toString() }
            val t = Playback.open(this, u, attrs, loop = !multi, startVolume = 0f,
                onComplete = if (multi) ({ if (current != null) playNext(a) }) else null)
            if (t != null) {
                player = t
                appliedIndex = -1
                applyVolume(a)
                lastUri = u.toString()
                getSharedPreferences("play", MODE_PRIVATE).edit().putString("last", lastUri).apply()
                break
            }
        }
    }

    /** ゆっくり音量アップ */
    private val rampTick = object : Runnable {
        override fun run() {
            val a = current ?: return
            if (player == null) { handler.postDelayed(this, 200); return }
            applyVolume(a)
            val done = a.rampSec <= 0 || System.currentTimeMillis() - rampStart >= a.rampSec * 1000L
            if (!done) handler.postDelayed(this, 200)
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        restoreAlarmVolume()
    }

    private fun finish() {
        stopPlayback()
        current = null
        ringingId = -1
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        onFinished?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopPlayback()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
