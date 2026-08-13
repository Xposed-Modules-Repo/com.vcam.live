package com.hazbu.xcam.core.engine

import android.content.Context
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Handles Media3/ExoPlayer lifecycle and state management for video injection.
 */
class MediaEngine(private val logAction: (String) -> Unit) {

    private var player: ExoPlayer? = null
    private var isBusy = false

    var videoWidth = 0
        private set

    var videoHeight = 0
        private set

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    val currentPosition: Long
        get() = player?.currentPosition ?: 0L

    private fun log(tag: String, msg: String) {
        logAction("[$tag] $msg")
    }

    fun stop() {
        isBusy = true

        try {
            player?.apply {
                stop()
                clearVideoSurface()
                release()
            }
        } catch (e: Throwable) {
            log("MEDIA-ENGINE", "Stop failed: ${e.message}")
        } finally {
            player = null
            videoWidth = 0
            videoHeight = 0
            isBusy = false
        }
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        onPrepared: ((ExoPlayer) -> Unit)? = null
    ) {
        if (isBusy) return

        stop()

        isBusy = true

        try {
            val exoPlayer = ExoPlayer.Builder(context.applicationContext)
                .build()

            player = exoPlayer

            val mediaItem = MediaItem.fromUri(path.toUri())

            exoPlayer.setMediaItem(mediaItem)

            // Penting untuk pipeline xCam:
            // langsung render video ke Surface yang diberikan.
            exoPlayer.setVideoSurface(surface)

            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE

            exoPlayer.addListener(object : Player.Listener {

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {

                        Player.STATE_READY -> {
                            if (!isBusy) return

                            isBusy = false

                            videoWidth = exoPlayer.videoSize.width
                            videoHeight = exoPlayer.videoSize.height

                            try {
                                exoPlayer.play()

                                log(
                                    tag,
                                    "Player ACTIVE (${videoWidth}x${videoHeight})"
                                )

                                onPrepared?.invoke(exoPlayer)

                            } catch (e: Throwable) {
                                log(
                                    tag,
                                    "Start failed: ${e.message}"
                                )
                            }
                        }

                        Player.STATE_BUFFERING -> {
                            log(tag, "Player buffering")
                        }

                        Player.STATE_ENDED -> {
                            log(tag, "Player ended")
                        }

                        Player.STATE_IDLE -> {
                            // Ignore
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isBusy = false

                    log(
                        tag,
                        "Player Error: ${error.errorCodeName} | ${error.message}"
                    )

                    stop()
                }
            })

            exoPlayer.prepare()

        } catch (e: Throwable) {
            isBusy = false

            log(
                tag,
                "Prepare failed: ${e.message}"
            )

            try {
                player?.release()
            } catch (_: Throwable) {
            }

            player = null
        }
    }

    /**
     * Optional helper if Surface changes while the player is alive.
     */
    fun setSurface(surface: Surface?) {
        player?.setVideoSurface(surface)
    }
}