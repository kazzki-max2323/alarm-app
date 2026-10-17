package com.kkk.alarm

import android.content.Context
import java.util.Calendar

/**
 * 鳴動画面に出す「つぶやき」。assets/lines/*.txt から読み込む。
 *  - ファイル1行目 "# 絵文字 ジャンル名"
 *  - "@0"〜"@6" で始まる行は曜日限定（0=日）
 *  - "%1,2,12" で始まる行はその月限定
 *  - 09_snooze はスヌーズ中、10_snooze_last はスヌーズが1分になってから
 */
object Quips {
    data class Quip(val label: String, val text: String)

    private class Line(val label: String, val text: String, val day: Int?, val months: Set<Int>?, val kind: String)

    private var cache: List<Line>? = null

    private fun load(ctx: Context): List<Line> {
        cache?.let { return it }
        val out = mutableListOf<Line>()
        val files = ctx.assets.list("lines")?.sorted() ?: emptyList()
        for (f in files) {
            val kind = when {
                f.startsWith("09_") -> "snooze"
                f.startsWith("10_") -> "snooze_last"
                else -> "normal"
            }
            var label = ""
            ctx.assets.open("lines/$f").bufferedReader().useLines { seq ->
                for (raw in seq) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("#")) { label = line.removePrefix("#").trim(); continue }
                    var text = line
                    var day: Int? = null
                    var months: Set<Int>? = null
                    if (text.startsWith("@")) {
                        day = text.substring(1, 2).toIntOrNull()
                        text = text.substring(2).trim()
                    } else if (text.startsWith("%")) {
                        val sp = text.indexOf(' ')
                        months = text.substring(1, sp).split(',').mapNotNull { it.toIntOrNull() }.toSet()
                        text = text.substring(sp + 1).trim()
                    }
                    out.add(Line(label, text, day, months, kind))
                }
            }
        }
        cache = out
        return out
    }

    /** snoozeCount: 何回スヌーズしたか。nextMin: 今の間隔（分） */
    fun pick(ctx: Context, snoozeCount: Int, currentMin: Int): Quip {
        val all = load(ctx)
        if (all.isEmpty()) return Quip("", "おはよう！")
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_WEEK) - 1
        val month = cal.get(Calendar.MONTH) + 1

        val usable = all.filter { l ->
            (l.day == null || l.day == today) && (l.months == null || month in l.months)
        }
        val normal = usable.filter { it.kind == "normal" }
        val snooze = usable.filter { it.kind == "snooze" }
        val last = usable.filter { it.kind == "snooze_last" }
        val dayLines = normal.filter { it.day != null }

        val r = Math.random()
        val pool = when {
            snoozeCount > 0 && currentMin <= 1 && r < 0.35 && last.isNotEmpty() -> last
            snoozeCount > 0 && r < 0.65 && snooze.isNotEmpty() -> snooze
            snoozeCount == 0 && r < 0.12 && dayLines.isNotEmpty() -> dayLines
            else -> normal.filter { it.day == null }.ifEmpty { normal }
        }

        // 最近出たものは避ける
        val prefs = ctx.getSharedPreferences("quips", Context.MODE_PRIVATE)
        val recent = (prefs.getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
        val fresh = pool.filter { it.text !in recent }
        val chosen = (fresh.ifEmpty { pool }).random()
        recent.add(chosen.text)
        while (recent.size > 300) recent.removeAt(0)
        prefs.edit().putString("recent", recent.joinToString("\n")).apply()

        return Quip(chosen.label, chosen.text)
    }
}
