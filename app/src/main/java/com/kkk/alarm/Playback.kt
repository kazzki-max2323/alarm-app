package com.kkk.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media

/** 再生中の1曲。音量は 0.0〜1.0 */
interface Track {
    fun setVolume(v: Float)
    fun release()
}

/**
 * 曲を開いて再生する。まずAndroid標準の MediaPlayer で開き、
 * 開けない形式（WMAなど）は VLC のエンジンで再生する。
 * アラームとして鳴らすときは、VLC再生中だけメディア音量をアラーム音量に合わせる。
 */
object Playback {

    fun open(
        ctx: Context, uri: Uri, attrs: AudioAttributes, loop: Boolean,
        startVolume: Float, onComplete: (() -> Unit)? = null,
    ): Track? {
        if (!looksLikeWma(ctx, uri)) {
            openStandard(ctx, uri, attrs, loop, startVolume, onComplete)?.let { return it }
        }
        return openVlc(ctx, uri, loop, startVolume, onComplete,
            matchAlarmVolume = attrs.usage == AudioAttributes.USAGE_ALARM)
    }

    private fun looksLikeWma(ctx: Context, uri: Uri): Boolean {
        val type = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null } ?: ""
        return type.contains("wma", true) || type.contains("ms-asf", true) ||
                uri.toString().lowercase().let { it.endsWith(".wma") || it.endsWith(".asf") }
    }

    private fun openStandard(
        ctx: Context, uri: Uri, attrs: AudioAttributes, loop: Boolean,
        vol: Float, onComplete: (() -> Unit)?,
    ): Track? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(ctx, uri)
            mp.isLooping = loop
            if (!loop && onComplete != null) mp.setOnCompletionListener { onComplete() }
            mp.setVolume(vol, vol)
            mp.prepare()
            mp.start()
            object : Track {
                override fun setVolume(v: Float) { try { mp.setVolume(v, v) } catch (_: Exception) { } }
                override fun release() {
                    try { mp.stop() } catch (_: Exception) { }
                    mp.release()
                }
            }
        } catch (_: Exception) {
            mp.release()
            null
        }
    }

    private fun openVlc(
        ctx: Context, uri: Uri, loop: Boolean, vol: Float, onComplete: (() -> Unit)?,
        matchAlarmVolume: Boolean,
    ): Track? {
        var pfd: ParcelFileDescriptor? = null
        var vlc: LibVLC? = null
        var player: org.videolan.libvlc.MediaPlayer? = null
        return try {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            vlc = LibVLC(ctx.applicationContext, arrayListOf("--no-video", "--quiet"))
            val p = org.videolan.libvlc.MediaPlayer(vlc)
            player = p
            val media = Media(vlc, pfd.fileDescriptor)
            media.addOption(":no-video")
            if (loop) media.addOption(":input-repeat=65535")
            p.media = media
            media.release()

            val main = Handler(Looper.getMainLooper())
            var failed = false
            p.setEventListener { e ->
                when (e.type) {
                    org.videolan.libvlc.MediaPlayer.Event.EndReached ->
                        if (!loop) onComplete?.let { cb -> main.post { cb() } }
                    org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> failed = true
                }
            }
            p.volume = (vol * 100).toInt().coerceIn(0, 100)

            // VLCはメディア音量で鳴るので、鳴っている間だけメディア音量をアラーム音量に合わせる
            val am = ctx.getSystemService(AudioManager::class.java)
            val savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            var changed = false
            if (matchAlarmVolume) {
                try {
                    val ratio = am.getStreamVolume(AudioManager.STREAM_ALARM).toFloat() /
                            am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
                    val target = Math.round(ratio * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                        .coerceAtLeast(if (ratio > 0f) 1 else 0)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    changed = true
                } catch (_: Exception) { }
            }
            p.play()

            val fd = pfd!!
            val lib = vlc!!
            object : Track {
                override fun setVolume(v: Float) {
                    try { p.volume = (v * 100).toInt().coerceIn(0, 100) } catch (_: Exception) { }
                }
                override fun release() {
                    try { p.setEventListener(null); p.stop() } catch (_: Exception) { }
                    try { p.release() } catch (_: Exception) { }
                    try { lib.release() } catch (_: Exception) { }
                    try { fd.close() } catch (_: Exception) { }
                    if (changed) {
                        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0) } catch (_: Exception) { }
                    }
                }
            }.takeIf { !failed }
        } catch (_: Throwable) {
            try { player?.release() } catch (_: Exception) { }
            try { vlc?.release() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
            null
        }
    }
}
