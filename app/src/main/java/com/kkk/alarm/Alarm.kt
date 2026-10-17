package com.kkk.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** days: ビット0=日 … ビット6=土。0なら1回だけ */
data class Alarm(
    val id: Int,
    var hour: Int = 7,
    var minute: Int = 0,
    var days: Int = 0b1111111,
    var enabled: Boolean = true,
    var label: String = "",
    var soundUri: String = "",
    var soundName: String = "",
    var snoozeMin: Int = 5,
    var rampSec: Int = 60,
) {
    fun timeText() = String.format("%02d:%02d", hour, minute)

    fun daysText(): String {
        val names = arrayOf("日", "月", "火", "水", "木", "金", "土")
        return when (days) {
            0b1111111 -> "毎日"
            0 -> "1回のみ"
            0b0111110 -> "平日"
            0b1000001 -> "週末"
            else -> (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { names[it] }
        }
    }

    /** 次に鳴る時刻（ミリ秒） */
    fun nextTrigger(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (days == 0) {
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        for (i in 0..7) {
            val dow = c.get(Calendar.DAY_OF_WEEK) - 1
            if (c.timeInMillis > now && days and (1 shl dow) != 0) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute); put("days", days)
        put("enabled", enabled); put("label", label); put("soundUri", soundUri)
        put("soundName", soundName); put("snoozeMin", snoozeMin); put("rampSec", rampSec)
    }

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute", 0),
            days = o.optInt("days", 0b1111111),
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label", ""),
            soundUri = o.optString("soundUri", ""),
            soundName = o.optString("soundName", ""),
            snoozeMin = o.optInt("snoozeMin", 5),
            rampSec = o.optInt("rampSec", 60),
        )
    }
}

object AlarmStore {
    private const val PREF = "alarms"
    private const val KEY = "list"

    fun all(ctx: Context): MutableList<Alarm> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(s)
        val list = (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }.toMutableList()
        list.sortWith(compareBy({ it.hour }, { it.minute }))
        return list
    }

    fun get(ctx: Context, id: Int) = all(ctx).firstOrNull { it.id == id }

    private fun saveAll(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun save(ctx: Context, a: Alarm) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list.add(a)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: Int) = saveAll(ctx, all(ctx).filter { it.id != id })

    fun newId(ctx: Context) = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1
}

object Scheduler {
    const val EXTRA_ID = "alarm_id"
    private const val SNOOZE_OFFSET = 100000

    private fun pending(ctx: Context, id: Int, snooze: Boolean): PendingIntent {
        val code = if (snooze) id + SNOOZE_OFFSET else id
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAt(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun schedule(ctx: Context, a: Alarm) {
        cancel(ctx, a.id)
        if (a.enabled) setAt(ctx, a.nextTrigger(), pending(ctx, a.id, false))
    }

    fun snooze(ctx: Context, a: Alarm) {
        setAt(ctx, System.currentTimeMillis() + a.snoozeMin * 60_000L, pending(ctx, a.id, true))
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, id, false))
        am.cancel(pending(ctx, id, true))
    }

    fun rescheduleAll(ctx: Context) = AlarmStore.all(ctx).forEach { schedule(ctx, it) }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}
