package com.kkk.alarm

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 曲リストを選ぶ画面。新規作成・編集・削除もここで */
class PlaylistActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
    }

    private var selectedId = 0
    private var editingId = 0   // 0なら新規作成
    private lateinit var listView: LinearLayout

    private val editList = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val sounds = SoundPickerActivity.readResult(r.data)
        val name = SoundPickerActivity.listName(r.data)
        if (editingId == 0) {
            // 新しく作ったリストはそのまま選んで戻る
            val p = Playlist(PlaylistStore.newId(this), name, sounds)
            PlaylistStore.save(this, p)
            choose(p.id)
        } else {
            PlaylistStore.get(this, editingId)?.let {
                it.name = name; it.sounds = sounds
                PlaylistStore.save(this, it)
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        selectedId = intent.getIntExtra(EXTRA_PLAYLIST_ID, 0)
        listView = findViewById(R.id.playlistList)
        findViewById<View>(R.id.plClose).setOnClickListener { finish() }
        findViewById<View>(R.id.plNew).setOnClickListener {
            editingId = 0
            val n = PlaylistStore.all(this).size + 1
            editList.launch(SoundPickerActivity.intentFor(this, emptyList(), "マイリスト" + if (n == 1) "" else " $n"))
        }
        render()
    }

    private fun render() {
        listView.removeAllViews()
        addRow(0, "標準のアラーム音", "スマホのアラーム音", false)
        for (p in PlaylistStore.all(this)) {
            val preview = p.sounds.take(2).joinToString("、") { it.name } + if (p.sounds.size > 2) " ほか" else ""
            addRow(p.id, p.name, "${p.sounds.size}曲  ·  $preview", true)
        }
    }

    private fun addRow(id: Int, title: String, sub: String, editable: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_playlist, listView, false)
        val sel = id == selectedId
        row.findViewById<TextView>(R.id.plTitle).text = title
        row.findViewById<TextView>(R.id.plSub).text = sub
        row.findViewById<ImageView>(R.id.plRadio).apply {
            setImageResource(if (sel) R.drawable.ic_check else 0)
            setBackgroundResource(if (sel) R.drawable.day_on else R.drawable.day_off)
            setColorFilter(getColor(R.color.bg))
        }
        row.setOnClickListener { choose(id) }
        val more = row.findViewById<View>(R.id.plMore)
        if (!editable) {
            more.visibility = View.INVISIBLE
        } else {
            more.setOnClickListener { v ->
                PopupMenu(this, v).apply {
                    menu.add(0, 1, 0, "編集（曲・名前）")
                    menu.add(0, 2, 1, "削除")
                    setOnMenuItemClickListener {
                        if (it.itemId == 1) edit(id) else confirmDelete(id, title)
                        true
                    }
                }.show()
            }
        }
        listView.addView(row)
    }

    private fun edit(id: Int) {
        val p = PlaylistStore.get(this, id) ?: return
        editingId = id
        editList.launch(SoundPickerActivity.intentFor(this, p.sounds, p.name))
    }

    private fun confirmDelete(id: Int, name: String) {
        val users = AlarmStore.all(this).count { it.playlistId == id }
        val msg = "「$name」を削除しますか？" +
                if (users > 0) "\nこのリストを使っている${users}件のアラームは標準のアラーム音になります。" else ""
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("削除") { _, _ ->
                PlaylistStore.delete(this, id)
                AlarmStore.all(this).filter { it.playlistId == id }.forEach {
                    it.playlistId = 0; AlarmStore.save(this, it)
                }
                if (selectedId == id) selectedId = 0
                render()
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun choose(id: Int) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PLAYLIST_ID, id))
        finish()
    }
}
