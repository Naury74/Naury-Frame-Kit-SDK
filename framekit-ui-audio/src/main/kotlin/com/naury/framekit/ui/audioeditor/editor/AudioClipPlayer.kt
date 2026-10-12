package com.naury.framekit.ui.audioeditor.editor

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.naury.framekit.core.video.AudioClip
import kotlin.math.abs

/**
 * 음성 편집 미리보기를 튼다. 재생 위치를 프레임마다 받아, 그 시각에 들려야 할 조각만 원본 위치에 맞춰 튼다(어긋나면 위치를 맞춤).
 * 페이드와 볼륨은 재생 볼륨으로 흉내 낸다(100%를 넘는 볼륨은 미리보기에서 100%). 메인 스레드에서 쓴다.
 */
internal class AudioClipPlayer(private val context: Context, private val uriOf: (String) -> Uri?) {

    private val players = mutableMapOf<String, Track>()

    private class Track(val source: String, val player: MediaPlayer, var prepared: Boolean = false)

    fun sync(clips: List<AudioClip>, durationUs: Long, positionUs: Long, playing: Boolean) {
        val wanted = clips.associateBy { it.id }
        players.entries.removeAll { (id, track) ->
            val keep = wanted[id]?.source?.value == track.source
            if (!keep) track.player.release()
            !keep
        }
        wanted.values.forEach { clip ->
            val range = clip.outputRange(durationUs)
            val active = playing && positionUs in range
            val existing = players[clip.id]
            // 곧 들릴 조각만 미리 준비해 플레이어 수를 줄인다.
            val soon = positionUs >= range.startUs - PREPARE_AHEAD_US && positionUs < range.endExclusiveUs
            if (existing == null) {
                if (soon) open(clip)?.let { players[clip.id] = it }
                return@forEach
            }
            if (!soon && !active) {
                existing.player.release()
                players.remove(clip.id)
                return@forEach
            }
            if (!existing.prepared) return@forEach
            val player = existing.player
            if (!active) {
                if (player.isPlaying) player.pause()
                return@forEach
            }
            val target = sourcePositionMs(clip, positionUs)
            val gain = gainAt(clip, range.startUs, range.endExclusiveUs, positionUs)
            player.setVolume(gain, gain)
            if (!player.isPlaying) {
                player.seekTo(target.toInt())
                player.start()
            } else if (abs(player.currentPosition - target) > DRIFT_MS) {
                player.seekTo(target.toInt())
            }
        }
    }

    fun pauseAll() = players.values.forEach { if (it.prepared && it.player.isPlaying) it.player.pause() }

    fun release() {
        players.values.forEach { it.player.release() }
        players.clear()
    }

    private fun open(clip: AudioClip): Track? {
        val uri = uriOf(clip.source.value) ?: return null
        return try {
            val player = MediaPlayer()
            val track = Track(clip.source.value, player)
            player.setDataSource(context, uri)
            player.setOnPreparedListener { track.prepared = true }
            player.prepareAsync()
            track
        } catch (_: Exception) {
            null
        }
    }

    private fun sourcePositionMs(clip: AudioClip, positionUs: Long): Long {
        val length = clip.sourceRange.durationUs.coerceAtLeast(1)
        val elapsed = positionUs - clip.timelineStartUs
        val first = length - clip.startOffsetUs
        val source = if (elapsed < first) clip.sourceRange.startUs + clip.startOffsetUs + elapsed else clip.sourceRange.startUs + (elapsed - first) % length
        return source / 1000
    }

    private fun gainAt(clip: AudioClip, startUs: Long, endUs: Long, positionUs: Long): Float {
        var gain = clip.volume.toFloat().coerceIn(0f, 1f)
        if (clip.fadeInUs > 0) gain *= ((positionUs - startUs).toFloat() / clip.fadeInUs).coerceIn(0f, 1f)
        if (clip.fadeOutUs > 0) gain *= ((endUs - positionUs).toFloat() / clip.fadeOutUs).coerceIn(0f, 1f)
        return gain
    }

    private companion object {
        const val DRIFT_MS = 200L
        const val PREPARE_AHEAD_US = 1_500_000L
    }
}
