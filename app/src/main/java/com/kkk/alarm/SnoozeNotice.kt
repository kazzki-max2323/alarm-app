package com.kkk.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** スヌーズ中の通知。次に鳴るまでの残り時間をカウントダウン表示する */
object SnoozeNotice {
    private const val CHANNEL = "snooze_wait"
    private const val BASE_ID = 5000
    const val ACTION_CANCEL = "com.kkk.alarm.CANCEL_SNOOZE"

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "スヌーズ中", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                })
        }
    }

    fun show(ctx: Context, a: Alarm, at: Long) {
        ensureChannel(ctx)
        val time = SimpleDateFormat("H:mm", Locale.JAPAN).format(Date(at))
        val open = PendingIntent.getActivity(ctx, BASE_ID + a.id,
            Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(ctx, BASE_ID + a.id,
            Intent(ctx, SnoozeCancelReceiver::class.java).setAction(ACTION_CANCEL)
                .putExtra(Scheduler.EXTRA_ID, a.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_snooze)
            .setContentTitle("😴 スヌーズ中  $time にまた鳴ります")
            .setContentText(if (a.label.isNotEmpty()) "${a.timeText()}  ${a.label}" else "${a.timeText()} のアラーム")
            // 残り時間をカウントダウン表示（例：4:32）
            .setWhen(at)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "スヌーズを取り消す", cancel)
            .setTimeoutAfter(maxOf(1_000L, at - System.currentTimeMillis() + 5_000L))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(BASE_ID + a.id, n)
        } catch (_: SecurityException) { }
    }

    fun hide(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(BASE_ID + id)
    }
}

/** 通知の「スヌーズを取り消す」 */
class SnoozeCancelReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        if (id >= 0) Scheduler.cancelSnooze(ctx, id)
    }
}
