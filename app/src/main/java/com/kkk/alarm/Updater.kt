package com.kkk.alarm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHubに新しいビルドがあるか調べる。押したときだけネットを使う */
object Updater {
    private const val API = "https://api.github.com/repos/kazzki-max2323/alarm-app/releases/latest"
    private const val PAGE = "https://github.com/kazzki-max2323/alarm-app/releases/latest"

    fun currentBuild(act: Activity): Int = try {
        val info = act.packageManager.getPackageInfo(act.packageName, 0)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (_: Exception) { 0 }

    fun check(act: Activity) {
        val mine = currentBuild(act)
        val wait = AlertDialog.Builder(act).setMessage("最新版を確認しています…").setCancelable(true).show()
        val main = Handler(Looper.getMainLooper())

        Thread {
            var latest = -1
            var apkUrl: String? = null
            var error: String? = null
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                latest = json.getString("tag_name").removePrefix("build-").toIntOrNull() ?: -1
                val assets = json.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) apkUrl = a.optString("browser_download_url")
                }
            } catch (e: Exception) {
                error = e.message
            }

            main.post {
                if (act.isFinishing) return@post
                wait.dismiss()
                when {
                    error != null || latest < 0 ->
                        AlertDialog.Builder(act)
                            .setTitle("確認できませんでした")
                            .setMessage("インターネットにつながっているか確認して、もう一度お試しください。")
                            .setPositiveButton("OK", null).show()
                    latest > mine ->
                        AlertDialog.Builder(act)
                            .setTitle("新しいバージョンがあります")
                            .setMessage("今のバージョン：build $mine\n最新のバージョン：build $latest\n\n" +
                                    "ダウンロードしたら alarm.apk を開いて「更新」をタップしてください。" +
                                    "アラームや曲リストの設定はそのまま残ります。")
                            .setPositiveButton("ダウンロード") { _, _ -> open(act, apkUrl ?: PAGE) }
                            .setNegativeButton("あとで", null).show()
                    else ->
                        AlertDialog.Builder(act)
                            .setTitle("最新版です")
                            .setMessage("今のバージョン（build $mine）が最新です。")
                            .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    /** ダウンロードはSamsung Internetで開く（Chromeだとapkの保存が止まることがあるため） */
    private fun open(act: Activity, url: String) {
        val uri = Uri.parse(url)
        val samsung = Intent(Intent.ACTION_VIEW, uri).setPackage("com.sec.android.app.sbrowser")
        try {
            act.startActivity(samsung)
        } catch (_: Exception) {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(act, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
