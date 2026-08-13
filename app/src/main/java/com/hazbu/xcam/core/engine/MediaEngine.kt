package com.hazbu.xcam.core.engine

import android.content.Context
import android.media.MediaPlayer
import android.view.Surface
import androidx.core.net.toUri

/**
 * Handles MediaPlayer lifecycle and state management for video injection.
 */
class MediaEngine(private val logAction: (String) -> Unit) {
    private var mediaPlayer: MediaPlayer? = null
    private var isBusy = false
    
    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true
    val currentPosition: Int get() = mediaPlayer?.currentPosition ?: 0

    private fun log(tag: String, msg: String) = logAction("[$tag] $msg")

    fun stop() {
        isBusy = true
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Throwable) {
            log("MEDIA-ENGINE", "Stop failed: ${e.message}")
        } finally {
            mediaPlayer = null
            isBusy = false
        }
    }

    fun play(context: Context, path: String, surface: Surface, tag: String) {
        if (isBusy) return
        stop()
        
        isBusy = true
        MediaPlayer().apply {
            setDataSource(context, path.toUri())
            setSurface(surface)
            isLooping = true
            mediaPlayer = this
            
            setOnPreparedListener {
                isBusy = false
                try {
                    it.start()
                    log(tag, "Player ACTIVE")
                } catch (e: Throwable) {
                    log(tag, "Start failed: ${e.message}")
                }
            }
            
            setOnErrorListener { _, what, extra ->
                isBusy = false
                log(tag, "Player Error ($what, $extra)")
                stop()
                true
            }
            
            try {
                prepareAsync()
            } catch (e: Exception) {
                isBusy = false
                log(tag, "Prepare failed: ${e.message}")
            }
        }
    }
}
