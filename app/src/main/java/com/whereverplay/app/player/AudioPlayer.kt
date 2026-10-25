package com.whereverplay.app.player

import android.media.MediaPlayer
import android.util.Log

/**
 * WP: 播放器抽象。Demo 用系统 MediaPlayer 实现；v1 换成 Media3 ExoPlayer 时只换实现。
 * 之所以先抽接口，是因为「变速/循环/后台播放」将来都要动这一层。
 */
interface AudioPlayer {
    val positionMs: Long
    val durationMs: Long
    val isPlaying: Boolean

    fun load(path: String): Boolean
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun release()
}

/**
 * WP: 平台 MediaPlayer 实现。
 *
 * 注意：这里用同步 prepare()。本地文件（已复制进私有目录）耗时很短，
 * Demo 阶段可接受；v1 换 Media3 后改为异步准备。
 */
class MediaPlayerAudioPlayer : AudioPlayer {

    private var player: MediaPlayer? = null

    override val positionMs: Long
        get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    override val durationMs: Long
        get() = runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    override val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override fun load(path: String): Boolean {
        release()
        return try {
            player = MediaPlayer().apply {
                setDataSource(path)
                prepare()
            }
            true
        } catch (t: Throwable) {
            Log.e("WP-AUDIO", "load failed: $path", t)
            release()
            false
        }
    }

    override fun play() {
        runCatching { player?.start() }
    }

    override fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
    }

    override fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms.coerceAtLeast(0).toInt()) }
    }

    override fun release() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }
}
