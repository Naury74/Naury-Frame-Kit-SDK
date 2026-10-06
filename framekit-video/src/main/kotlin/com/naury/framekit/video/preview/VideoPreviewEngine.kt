// Media3의 composition·effect·Transformer API는 @UnstableApi다. FrameKit은 이 모듈 안의 어댑터에서만 쓰고
// 버전을 고정(1.11.1)해 API 변경을 빌드 단계에서 확인한다. 일정 배속(setSpeed)과 scrubbing 모드는
// @ExperimentalApi라 계측 테스트로 결과 길이·동작을 확인한다.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.naury.framekit.video.preview

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.view.SurfaceHolder
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.video.VideoRenderPlan
import com.naury.framekit.video.media3.Media3CompositionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playback state of the preview. Positions are output-timeline microseconds.
 *
 * @property planRevision revision of the project the player currently shows; a late prepare of an
 *   older revision never overwrites a newer one.
 */
public data class VideoPlaybackState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val positionUs: Long = 0L,
    val durationUs: Long = 0L,
    val ended: Boolean = false,
    val error: EditorErrorCode? = null,
    val planRevision: Long = -1L,
)

/** Preview player used by the video editor. UI code never touches Media3 directly. */
public interface VideoPreviewEngine {
    public val state: StateFlow<VideoPlaybackState>

    /** Shows [plan], keeping the given position, without starting playback. */
    public fun setPlan(plan: VideoRenderPlan, positionUs: Long)
    public fun play()
    public fun pause()
    public fun seekTo(positionUs: Long)

    /** Scrubbing mode favours fast seeks over exact frames while the user drags the playhead. */
    public fun setScrubbing(enabled: Boolean)
    public fun attachSurface(holder: SurfaceHolder)
    public fun detachSurface(holder: SurfaceHolder)
    public fun release()
}

/**
 * [VideoPreviewEngine] backed by Media3 CompositionPlayer. Must be used on the main thread.
 */
public class Media3VideoPreviewEngine(context: Context, private val scope: CoroutineScope) : VideoPreviewEngine {

    private val player: CompositionPlayer = CompositionPlayer.Builder(context.applicationContext).build()
    private val _state = MutableStateFlow(VideoPlaybackState())
    override val state: StateFlow<VideoPlaybackState> = _state.asStateFlow()
    private var poller: Job? = null
    private var released = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update {
                    it.copy(
                        isReady = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED,
                        ended = playbackState == Player.STATE_ENDED,
                        durationUs = player.duration.takeIf { d -> d > 0 }?.times(1000) ?: it.durationUs,
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startPolling() else stopPolling()
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> EditorErrorCode.SOURCE_UNAVAILABLE
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> EditorErrorCode.PERMISSION_DENIED
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> EditorErrorCode.UNSUPPORTED_FORMAT
                    else -> EditorErrorCode.DECODE_FAILED
                }
                _state.update { it.copy(error = code, isPlaying = false) }
            }
        })
    }

    override fun setPlan(plan: VideoRenderPlan, positionUs: Long) {
        if (released || plan.projectRevision < _state.value.planRevision) return
        player.setComposition(Media3CompositionFactory.create(plan, maxFrameRate = null), positionUs / 1000)
        player.prepare()
        _state.update { it.copy(planRevision = plan.projectRevision, positionUs = positionUs, error = null, ended = false) }
    }

    override fun play() {
        if (released) return
        if (_state.value.ended) player.seekTo(0)
        player.play()
    }

    override fun pause() {
        if (!released) player.pause()
    }

    override fun seekTo(positionUs: Long) {
        if (released) return
        player.seekTo(positionUs / 1000)
        _state.update { it.copy(positionUs = positionUs, ended = false) }
    }

    override fun setScrubbing(enabled: Boolean) {
        if (!released) player.setScrubbingModeEnabled(enabled)
    }

    override fun attachSurface(holder: SurfaceHolder) {
        if (!released) player.setVideoSurfaceHolder(holder)
    }

    override fun detachSurface(holder: SurfaceHolder) {
        if (!released) player.clearVideoSurfaceHolder(holder)
    }

    override fun release() {
        if (released) return
        released = true
        stopPolling()
        player.release()
    }

    // 재생 중에는 33ms마다 위치를 갱신해 playhead가 부드럽게 움직이게 한다.
    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive && !released) {
                _state.update { it.copy(positionUs = player.currentPosition * 1000) }
                delay(POSITION_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        poller?.cancel()
        poller = null
        if (!released) _state.update { it.copy(positionUs = player.currentPosition * 1000) }
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 33L
    }
}
