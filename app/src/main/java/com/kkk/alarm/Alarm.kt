package com.kkk.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Sound(val uri: String, val name: String)

/** 名前付きの曲リスト */
data class Playlist(val id: Int, var name: String, var sounds: MutableList<Sound> = mutableListOf()) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name)
        put("sounds", JSONArray().apply { sounds.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)) } })
    }
    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val list = mutableListOf<Sound>()
            o.optJSONArray("sounds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    list.add(Sound(s.getString("uri"), s.optString("name", "曲")))
                }
            }
            return Playlist(o.getInt("id"), o.optString("name", "リスト"), list)
        }
    }
}

object PlaylistStore {
    private const val PREF = "playlists"
    private const val KEY = "list"

    fun all(ctx: Context): MutableList<Playlist> {
        val arr = JSONArray(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map { Playlist.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    fun get(ctx: Context, id: Int) = if (id <= 0) null else all(ctx).firstOrNull { it.id == id }

    private fun saveAll(ctx: Context, list: List<Playlist>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun save(ctx: Context, p: Playlist) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: Int) = saveAll(ctx, all(ctx).filter { it.id != id })

    fun newId(ctx: Context) = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1
}

/** days: ビット0=日 … ビット6=土。0なら1回だけ */
data class Alarm(
    val id: Int,
    var hour: Int = 7,
    var minute: Int = 0,
    var days: Int = 0b1111111,
    var enabled: Boolean = true,
    var label: String = "",
    /** 旧バージョンの曲（プレイリストに移行される） */
    var sounds: MutableList<Sound> = mutableListOf(),
    /** 使うプレイリスト。0なら標準のアラーム音 */
    var playlistId: Int = 0,
    var snoozeMin: Int = 5,
    var rampSec: Int = 60,
) {
    /** 鳴らす曲 */
    fun songs(ctx: Context): List<Sound> = PlaylistStore.get(ctx, playlistId)?.sounds ?: sounds

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
        put("enabled", enabled); put("label", label); put("sounds", JSONArray().apply {
            sounds.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        })
        put("playlistId", playlistId)
        put("snoozeMin", snoozeMin); put("rampSec", rampSec)
    }

    companion object {
        private fun readSounds(o: JSONObject): MutableList<Sound> {
            val list = mutableListOf<Sound>()
            o.optJSONArray("sounds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    list.add(Sound(s.getString("uri"), s.optString("name", "曲")))
                }
            }
            // 旧バージョン（1曲だけ）の設定を引き継ぐ
            val old = o.optString("soundUri", "")
            if (list.isEmpty() && old.isNotEmpty()) list.add(Sound(old, o.optString("soundName", "曲")))
            return list
        }

        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute", 0),
            days = o.optInt("days", 0b1111111),
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label", ""),
            sounds = readSounds(o),
            playlistId = o.optInt("playlistId", 0),
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
        if (migrate(ctx, list)) saveAll(ctx, list)
        list.sortWith(compareBy({ it.hour }, { it.minute }))
        return list
    }

    fun get(ctx: Context, id: Int) = all(ctx).firstOrNull { it.id == id }

    /** 旧バージョンで直接選んだ曲を、名前付きリストに移す */
    private fun migrate(ctx: Context, list: List<Alarm>): Boolean {
        var changed = false
        for (a in list) {
            if (a.playlistId == 0 && a.sounds.isNotEmpty()) {
                val key = a.sounds.map { it.uri }.toSet()
                val existing = PlaylistStore.all(ctx).firstOrNull { p -> p.sounds.map { it.uri }.toSet() == key }
                val p = existing ?: Playlist(PlaylistStore.newId(ctx), "マイリスト" +
                        (PlaylistStore.all(ctx).size + 1).let { if (it == 1) "" else " $it" }, a.sounds.toMutableList())
                    .also { PlaylistStore.save(ctx, it) }
                a.playlistId = p.id
                a.sounds = mutableListOf()
                changed = true
            }
        }
        return changed
    }

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
    const val EXTRA_SNOOZE = "is_snooze"
    private const val SNOOZE_OFFSET = 100000

    private fun pending(ctx: Context, id: Int, snooze: Boolean): PendingIntent {
        val code = if (snooze) id + SNOOZE_OFFSET else id
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_SNOOZE, snooze)
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

    /** 決まった時刻の予約をし直す（スヌーズ中の予約は消さない） */
    fun schedule(ctx: Context, a: Alarm) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, a.id, false))
        if (a.enabled) setAt(ctx, a.nextTrigger(), pending(ctx, a.id, false))
    }

    private fun snoozePrefs(ctx: Context) = ctx.getSharedPreferences("snooze", Context.MODE_PRIVATE)

    /** 次のスヌーズ間隔：最初の間隔から1回ごとに1分ずつ短く、1分になったらずっと1分 */
    fun nextSnoozeMin(ctx: Context, a: Alarm): Int {
        val count = snoozePrefs(ctx).getInt("count_${a.id}", 0)
        return (a.snoozeMin - count).coerceAtLeast(1)
    }

    fun snooze(ctx: Context, a: Alarm) {
        val min = nextSnoozeMin(ctx, a)
        val count = snoozePrefs(ctx).getInt("count_${a.id}", 0)
        val at = System.currentTimeMillis() + min * 60_000L
        snoozePrefs(ctx).edit().putInt("count_${a.id}", count + 1).putLong("until_${a.id}", at).commit()
        setAt(ctx, at, pending(ctx, a.id, true))
    }

    /** スヌーズで次に鳴る時刻（スヌーズ中でなければ0） */
    fun snoozeUntil(ctx: Context, id: Int): Long {
        val t = snoozePrefs(ctx).getLong("until_$id", 0L)
        return if (t > System.currentTimeMillis() - 60_000L) t else 0L
    }

    /** スヌーズの予約が鳴った */
    fun snoozeFired(ctx: Context, id: Int) = snoozePrefs(ctx).edit().remove("until_$id").apply()

    /** スヌーズをやめる（停止・削除・OFFのとき） */
    fun cancelSnooze(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id, true))
        snoozePrefs(ctx).edit().remove("until_$id").remove("count_$id").apply()
    }

    fun snoozeCount(ctx: Context, id: Int) = snoozePrefs(ctx).getInt("count_$id", 0)

    fun resetSnooze(ctx: Context, id: Int) =
        snoozePrefs(ctx).edit().remove("count_$id").remove("until_$id").apply()

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id, false))
        cancelSnooze(ctx, id)
    }

    /** アプリ起動時・再起動時など。スヌーズ中のものは予約を張り直す */
    fun rescheduleAll(ctx: Context) {
        for (a in AlarmStore.all(ctx)) {
            schedule(ctx, a)
            val until = snoozePrefs(ctx).getLong("until_${a.id}", 0L)
            if (until > 0L) {
                val at = maxOf(until, System.currentTimeMillis() + 5_000L)
                setAt(ctx, at, pending(ctx, a.id, true))
            }
        }
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}
