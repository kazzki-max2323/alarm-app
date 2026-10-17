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
                .putExtra(Scheduler.EXTRA_ID, id))
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

    private var player: MediaPlayer? = null
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
                ring(a)
            }
            ACTION_SNOOZE -> {
                current?.let { Scheduler.snooze(this, it) }
                finish()
            }
            ACTION_DISMISS -> finish()
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
        val ringIntent = Intent(this, RingActivity::class.java)
            .putExtra(Scheduler.EXTRA_ID, a.id)
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
            .setContentText("アラームが鳴っています")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .setOngoing(true)
            .addAction(0, "スヌーズ", snoozePi)
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

    private fun startPlayback(a: Alarm) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val uris = buildList {
            if (a.soundUri.isNotEmpty()) add(Uri.parse(a.soundUri))
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(it) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { add(it) }
        }
        for (u in uris) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, u)
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.prepare()
                mp.start()
                player = mp
                break
            } catch (_: Exception) { }
        }
        rampStart = System.currentTimeMillis()
        handler.post(rampTick)
    }

    /** ゆっくり音量アップ */
    private val rampTick = object : Runnable {
        override fun run() {
            val a = current ?: return
            val mp = player ?: return
            val total = a.rampSec * 1000f
            val t = if (total <= 0f) 1f else ((System.currentTimeMillis() - rampStart) / total).coerceIn(0f, 1f)
            val v = 0.03f + 0.97f * t * t   // 耳の感覚に合わせてカーブ
            try { mp.setVolume(v, v) } catch (_: Exception) { }
            if (t < 1f) handler.postDelayed(this, 200)
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        player = null
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
