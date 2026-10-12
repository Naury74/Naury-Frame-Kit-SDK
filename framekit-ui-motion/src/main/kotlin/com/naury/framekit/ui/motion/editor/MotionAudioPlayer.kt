package com.naury.framekit.ui.motion.editor

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.naury.framekit.motion.model.MotionAudio

/**
 * 미리보기 재생에 맞춰 배경 음악·음성을 함께 튼다. 재생 위치를 매 프레임 받아, 소리가 들려야 할 때만 틀고 어긋나면 위치를 맞춘다.
 * 메인 스레드에서 쓴다.
 */
internal class MotionAudioPlayer(private val context: Context, private val uriOf: (String) -> Uri?) {

    private val players = mutableMapOf<String, Track>()

    private class Track(val sourceId: String, val player: MediaPlayer, var prepared: Boolean = false)

    /**
     * @param positionMs 지금 결과 재생 위치(밀리초).
     * @param durationMs 결과 한 번 재생 길이(밀리초).
     * @param playing 미리보기가 재생 중이면 `true`. 멈추면 소리도 멈춘다.
     */
    fun sync(audio: List<MotionAudio>, positionMs: Long, durationMs: Long, playing: Boolean) {
        val wanted = audio.associateBy { it.id }
        // 빠졌거나 원본이 바뀐 트랙은 정리한다.
        players.entries.removeAll { (id, track) ->
            val keep = wanted[id]?.sourceId == track.sourceId
            if (!keep) track.player.release()
            !keep
        }
        wanted.values.forEach { item ->
            val track = players.getOrPut(item.id) { open(item) ?: return@forEach }
            if (!track.prepared) return@forEach
            val target = sourcePosition(item, positionMs, durationMs)
            val player = track.player
            if (!playing || target == null) {
                if (player.isPlaying) player.pause()
                return@forEach
            }
            // MediaPlayer는 키울 수 없어 100%를 넘는 볼륨은 미리보기에서 100%로 튼다(저장에는 그대로 반영된다).
            val volume = gainAt(item, positionMs, durationMs)
            player.setVolume(volume, volume)
            if (!player.isPlaying) {
                player.seekTo(target.toInt())
                player.start()
            } else if (kotlin.math.abs(player.currentPosition - target) > DRIFT_MS) {
                player.seekTo(target.toInt())
            }
        }
    }

    fun pauseAll() = players.values.forEach { if (it.prepared && it.player.isPlaying) it.player.pause() }

    fun release() {
        players.values.forEach { it.player.release() }
        players.clear()
    }

    private fun open(item: MotionAudio): Track? {
        val uri = uriOf(item.sourceId) ?: return null
        return try {
            val player = MediaPlayer()
            val track = Track(item.sourceId, player)
            player.setDataSource(context, uri)
            player.setOnPreparedListener { track.prepared = true }
            player.prepareAsync()
            track
        } catch (_: Exception) {
            null
        }
    }

    // 결과 시각에 들려야 할 원본 위치(밀리초). 들리지 않을 때(시작 전, 끝난 뒤)는 null.
    private fun sourcePosition(item: MotionAudio, positionMs: Long, durationMs: Long): Long? {
        val clip = item.toAudioClip()
        val range = clip.outputRange(durationMs * 1000)
        val timeUs = positionMs * 1000
        if (timeUs !in range) return null
        val length = clip.sourceRange.durationUs.coerceAtLeast(1)
        val elapsed = timeUs - clip.timelineStartUs
        val first = length - clip.startOffsetUs
        val source = if (elapsed < first) clip.sourceRange.startUs + clip.startOffsetUs + elapsed else clip.sourceRange.startUs + (elapsed - first) % length
        return source / 1000
    }

    // 페이드를 반영한 볼륨(MediaPlayer는 1보다 크게 키울 수 없다).
    private fun gainAt(item: MotionAudio, positionMs: Long, durationMs: Long): Float {
        val range = item.toAudioClip().outputRange(durationMs * 1000)
        val t = positionMs * 1000 - range.startUs
        var gain = item.volume.toFloat().coerceIn(0f, 1f)
        if (item.fadeInMs > 0) gain *= (t / (item.fadeInMs * 1000f)).coerceIn(0f, 1f)
        if (item.fadeOutMs > 0) gain *= ((range.endExclusiveUs - positionMs * 1000) / (item.fadeOutMs * 1000f)).coerceIn(0f, 1f)
        return gain
    }

    private companion object {
        const val DRIFT_MS = 250L
    }
}
