package com.vrdevicecontrol.headset.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vrdevicecontrol.headset.scene.ImmersiveScene
import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.PresentationDescriptor
import java.io.File

/**
 * Media3/ExoPlayer feeding a Spatial SDK direct-to-surface panel. Preparation sets media at
 * position zero with `playWhenReady = false`, waits for both `STATE_READY` and the first rendered
 * frame on the hidden surface, and only then reports ready. `STATE_READY` alone is not treated as
 * proof that rendering works.
 */
@OptIn(UnstableApi::class)
class ExoPlaybackEngine(
    private val context: Context,
    private val scene: ImmersiveScene,
    private val prepareTimeoutMs: Long = 20_000,
) : PlaybackEngine {
    override var listener: (PlaybackEvent) -> Unit = {}
    override var lastStartTimings: StartTimings? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var token: String? = null
    private var prepared = false
    private var playing = false
    private var stateReady = false
    private var firstFrame = false
    private var timeout: Runnable? = null
    private var volume = 1f

    override fun prepare(token: String, file: File, asset: AssetDescriptor, presentation: PresentationDescriptor) {
        release()
        this.token = token
        val exo = ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .build()
            .apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
                this.volume = this@ExoPlaybackEngine.volume
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            }
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== exo) return
                when (state) {
                    Player.STATE_READY -> { stateReady = true; maybePrepared() }
                    Player.STATE_ENDED -> if (playing) {
                        playing = false
                        listener(PlaybackEvent.Completed(exo.duration.coerceAtLeast(0) * 1_000))
                    }
                    else -> Unit
                }
            }

            override fun onRenderedFirstFrame() {
                if (player !== exo) return
                if (!firstFrame) { firstFrame = true; maybePrepared() }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo) return
                Log.e(TAG, "Player error ${error.errorCodeName}", error)
                val info = ErrorInfo(
                    if (error.errorCode in UNSUPPORTED_CODES) ErrorCode.UNSUPPORTED_MEDIA else ErrorCode.PLAYBACK_FAILED,
                    error.errorCodeName,
                )
                if (prepared) listener(PlaybackEvent.Failed(info)) else failPrepare(info)
            }
        })
        exo.setVideoFrameMetadataListener { _, releaseTimeNs, _, _ ->
            // Runs on the playback thread; record only the first presented frame after play().
            val timings = lastStartTimings ?: return@setVideoFrameMetadataListener
            if (timings.firstFrameReleaseUs == null && playing) {
                val elapsedOffsetNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                lastStartTimings = timings.copy(firstFrameReleaseUs = (releaseTimeNs + elapsedOffsetNs) / 1_000)
            }
        }

        scene.createVideo(asset, presentation) { surface ->
            handler.post {
                if (player !== exo) return@post
                exo.setVideoSurface(surface)
                exo.seekTo(0)
                exo.prepare()
            }
        }
        timeout = Runnable {
            if (player === exo && !prepared) {
                failPrepare(ErrorInfo(ErrorCode.PLAYBACK_FAILED, "No first frame within ${prepareTimeoutMs / 1000} s"))
            }
        }.also { handler.postDelayed(it, prepareTimeoutMs) }
    }

    private fun maybePrepared() {
        if (prepared || !stateReady || !firstFrame) return
        val t = token ?: return
        val exo = player ?: return
        if (exo.currentPosition != 0L) exo.seekTo(0)
        prepared = true
        timeout?.let(handler::removeCallbacks)
        listener(PlaybackEvent.Prepared(t))
    }

    private fun failPrepare(error: ErrorInfo) {
        val t = token ?: return
        timeout?.let(handler::removeCallbacks)
        listener(PlaybackEvent.PrepareFailed(t, error))
    }

    override fun play() {
        val exo = player ?: return
        playing = true
        lastStartTimings = StartTimings(playRequestedUs = SystemClock.elapsedRealtimeNanos() / 1_000)
        exo.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun resume() {
        player?.play()
    }

    override fun release() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        player?.let {
            it.clearVideoSurface()
            it.release()
        }
        player = null
        token = null
        prepared = false
        playing = false
        stateReady = false
        firstFrame = false
        scene.destroyVideo()
    }

    override fun positionUs(): Long = (player?.currentPosition ?: 0) * 1_000
    override fun durationUs(): Long = (player?.duration?.takeIf { it > 0 } ?: 0) * 1_000

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        player?.volume = this.volume
    }

    private companion object {
        const val TAG = "ExoPlaybackEngine"
        val UNSUPPORTED_CODES = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}
