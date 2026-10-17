package com.kkk.alarm

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** スマホ内の曲を一覧表示。▶で試聴、タップで選択（複数可） */
class SoundPickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_NAMES = "names"

        fun readResult(data: Intent?): MutableList<Sound> {
            val uris = data?.getStringArrayExtra(EXTRA_URIS) ?: return mutableListOf()
            val names = data.getStringArrayExtra(EXTRA_NAMES) ?: arrayOf()
            return uris.mapIndexed { i, u -> Sound(u, names.getOrElse(i) { "曲" }) }.toMutableList()
        }

        fun intentFor(act: Activity, current: List<Sound>) =
            Intent(act, SoundPickerActivity::class.java)
                .putExtra(EXTRA_URIS, current.map { it.uri }.toTypedArray())
                .putExtra(EXTRA_NAMES, current.map { it.name }.toTypedArray())
    }

    private data class Song(val uri: String, val title: String, val artist: String, val durationMs: Long)

    private val allSongs = mutableListOf<Song>()
    private var shown = listOf<Song>()
    /** 選択中（選んだ順を保持） */
    private val selected = LinkedHashMap<String, String>()
    private var playingUri: String? = null
    private var player: MediaPlayer? = null

    private lateinit var listView: ListView
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private val adapter = SongAdapter()

    private val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) loadSongs() else showEmpty("曲の一覧を表示するには「音楽とオーディオ」の許可が必要です。\n下の「ファイルから追加」でも選べます。")
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            val name = fileName(uri)
            selected[uri.toString()] = name
            if (allSongs.none { it.uri == uri.toString() }) allSongs.add(0, Song(uri.toString(), name, "ファイル", 0))
        }
        applyFilter(currentQuery)
    }

    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound_picker)

        SoundPickerActivity.readResult(intent).forEach { selected[it.uri] = it.name }

        listView = findViewById(R.id.songList)
        countText = findViewById(R.id.pickCount)
        emptyText = findViewById(R.id.pickEmpty)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos]) }

        findViewById<View>(R.id.pickClose).setOnClickListener { finish() }
        findViewById<View>(R.id.pickDone).setOnClickListener { done() }
        findViewById<View>(R.id.pickFiles).setOnClickListener {
            stopPreview()
            pickFiles.launch(arrayOf("audio/*"))
        }
        findViewById<EditText>(R.id.pickSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString() ?: "") }
        })

        // すでに選んである曲（ファイルから追加した分など）も一覧に出す
        selected.forEach { (u, n) -> allSongs.add(Song(u, n, "選択中", 0)) }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadSongs()
        else askPermission.launch(permission)
        updateCount()
    }

    private fun loadSongs() {
        val found = mutableListOf<Song>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
        )
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Audio.Media.DURATION} >= 5000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    val artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: ""
                    found.add(Song(uri.toString(), c.getString(1) ?: "曲", artist, c.getLong(3)))
                }
            }
        } catch (_: Exception) { }
        val foundUris = found.map { it.uri }.toSet()
        val extras = allSongs.filter { it.uri !in foundUris }   // ファイルから選んだ分は残す
        allSongs.clear()
        allSongs.addAll(extras)
        allSongs.addAll(found)
        applyFilter(currentQuery)
    }

    private fun applyFilter(q: String) {
        currentQuery = q
        val key = q.trim().lowercase()
        shown = if (key.isEmpty()) allSongs.toList()
        else allSongs.filter { it.title.lowercase().contains(key) || it.artist.lowercase().contains(key) }
        adapter.notifyDataSetChanged()
        if (allSongs.isEmpty()) showEmpty("スマホ内に曲が見つかりませんでした。\n下の「ファイルから追加」で選べます。")
        else if (shown.isEmpty()) showEmpty("「$q」に一致する曲はありません")
        else emptyText.visibility = View.GONE
    }

    private fun showEmpty(msg: String) {
        emptyText.text = msg
        emptyText.visibility = View.VISIBLE
    }

    private fun toggle(s: Song) {
        if (selected.containsKey(s.uri)) selected.remove(s.uri) else selected[s.uri] = s.title
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        countText.text = if (selected.isEmpty()) "曲を選ぶ" else "${selected.size}曲 選択中"
    }

    private fun done() {
        stopPreview()
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_URIS, selected.keys.toTypedArray())
            .putExtra(EXTRA_NAMES, selected.values.toTypedArray()))
        finish()
    }

    // ---- 試聴 ----
    private fun togglePreview(s: Song) {
        if (playingUri == s.uri) { stopPreview(); return }
        stopPreview()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(this, Uri.parse(s.uri))
            mp.setOnCompletionListener { stopPreview() }
            mp.prepare()
            mp.start()
            player = mp
            playingUri = s.uri
        } catch (_: Exception) {
            mp.release()
            Toast.makeText(this, "この曲は再生できませんでした", Toast.LENGTH_SHORT).show()
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPreview() {
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        player = null
        playingUri = null
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
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

    private fun durationText(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private inner class SongAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
            val s = shown[p]
            val isSel = selected.containsKey(s.uri)
            val isPlaying = playingUri == s.uri

            v.findViewById<TextView>(R.id.songTitle).apply {
                text = s.title
                setTextColor(getColor(if (isPlaying) R.color.accent else R.color.text))
            }
            v.findViewById<TextView>(R.id.songSub).text =
                listOf(s.artist, durationText(s.durationMs)).filter { it.isNotEmpty() }.joinToString("  ·  ")

            v.findViewById<ImageView>(R.id.songPlay).apply {
                setImageResource(if (isPlaying) R.drawable.ic_stop else R.drawable.ic_play)
                setBackgroundResource(if (isPlaying) R.drawable.knob_on else R.drawable.play_bg)
                setColorFilter(getColor(if (isPlaying) R.color.bg else R.color.text))
                setOnClickListener { togglePreview(s) }
            }
            v.findViewById<ImageView>(R.id.songCheck).apply {
                setImageResource(if (isSel) R.drawable.ic_check else 0)
                setBackgroundResource(if (isSel) R.drawable.day_on else R.drawable.day_off)
                setColorFilter(getColor(R.color.bg))
            }
            return v
        }
    }
}
