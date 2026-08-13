package com.hazbu.xcam.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer

/**
 * Handles Media3/ExoPlayer lifecycle and state management for video injection.
 */
@UnstableApi
class MediaEngine(private val logAction: (String) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    
    @Volatile
    private var isBusy = false

    @Volatile
    private var isPlayingInternal = false

    @Volatile
    private var isImageInternal = false

    private var imageBitmap: android.graphics.Bitmap? = null
    private var cachedScaledBitmap: android.graphics.Bitmap? = null
    private var imageLoopRunnable: Runnable? = null

    @Volatile
    private var currentPositionInternal = 0L

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    val isPlaying: Boolean
        get() = isPlayingInternal

    val isImage: Boolean
        get() = isImageInternal

    val currentPosition: Long
        get() = if (Looper.myLooper() == Looper.getMainLooper()) {
            player?.currentPosition ?: 0L
        } else {
            currentPositionInternal
        }

    private fun log(tag: String, msg: String) {
        logAction("[$tag] $msg")
    }

    fun stop() {
        mainHandler.post {
            isBusy = true
            stopImageLoop()
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
                isPlayingInternal = false
                isImageInternal = false
                currentPositionInternal = 0L
                videoWidth = 0
                videoHeight = 0
                isBusy = false
            }
        }
    }

    private fun stopImageLoop() {
        imageLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        imageLoopRunnable = null
        imageBitmap?.recycle()
        imageBitmap = null
        cachedScaledBitmap?.recycle()
        cachedScaledBitmap = null
    }

    private fun startImageLoop(
        context: Context,
        uri: android.net.Uri,
        surface: Surface,
        isMirrored: Boolean,
        rotationAngle: Int,
        tag: String
    ) {
        stopImageLoop()
        try {
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return

            imageBitmap = rawBitmap
            isPlayingInternal = true

            imageLoopRunnable = object : Runnable {
                private var lastTargetW = -1
                private var lastTargetH = -1

                override fun run() {
                    if (!isPlayingInternal || imageBitmap == null || !surface.isValid) return
                    try {
                        val canvas = surface.lockCanvas(null)
                        if (canvas != null) {
                            val targetW = canvas.width
                            val targetH = canvas.height

                            // Re-calculate transformation if surface size changed
                            if (targetW != lastTargetW || targetH != lastTargetH) {
                                lastTargetW = targetW
                                lastTargetH = targetH
                                cachedScaledBitmap?.recycle()

                                val sourceW = imageBitmap!!.width
                                val sourceH = imageBitmap!!.height
                                
                                val rotatedSourceW = if (rotationAngle % 180 != 0) sourceH else sourceW
                                val rotatedSourceH = if (rotationAngle % 180 != 0) sourceW else sourceH

                                // EXACT SCALE from XCamCapture (Fit Center)
                                val scale = Math.min(targetW.toFloat() / rotatedSourceW, targetH.toFloat() / rotatedSourceH)

                                val matrix = android.graphics.Matrix()
                                matrix.postScale(scale, scale)
                                if (rotationAngle != 0) matrix.postRotate(rotationAngle.toFloat())
                                if (isMirrored) matrix.postScale(-1f, 1f)

                                cachedScaledBitmap = android.graphics.Bitmap.createBitmap(imageBitmap!!, 0, 0, sourceW, sourceH, matrix, true)
                                
                                videoWidth = targetW
                                videoHeight = targetH
                            }

                            canvas.drawColor(android.graphics.Color.BLACK)
                            cachedScaledBitmap?.let { b ->
                                // Centering logic
                                val left = (targetW - b.width) / 2f
                                val top = (targetH - b.height) / 2f
                                canvas.drawBitmap(b, left, top, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                            }
                            surface.unlockCanvasAndPost(canvas)
                        }
                    } catch (e: Exception) {
                        log(tag, "Canvas Draw Error: ${e.message}")
                    }
                    mainHandler.postDelayed(this, 100) // 10 FPS
                }
            }
            mainHandler.post(imageLoopRunnable!!)
            log(tag, "Image Loop STARTED (${rawBitmap.width}x${rawBitmap.height})")
        } catch (e: Exception) {
            log(tag, "Image Setup Failed: ${e.message}")
        }
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        onPrepared: ((ExoPlayer?) -> Unit)? = null
    ) {
        mainHandler.post {
            if (isBusy) return@post
            
            // Internal sync: stop before play if called on main thread
            isBusy = true
            stopImageLoop()
            try {
                player?.apply {
                    stop()
                    clearVideoSurface()
                    release()
                }
            } catch (_: Throwable) {}

            try {
                val uri = path.toUri()
                val extension = MimeTypeMap.getFileExtensionFromUrl(path).ifEmpty {
                    path.substringAfterLast('.', "")
                }
                val mimeType = context.contentResolver.getType(uri) ?: 
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())

                isImageInternal = mimeType?.startsWith("image/") == true
                if (!isImageInternal && (extension.equals("jpg", true) || extension.equals("jpeg", true) || extension.equals("png", true))) {
                    isImageInternal = true
                }

                log(tag, "Loading media: $path | Detected MIME: $mimeType | IsImage: $isImageInternal")

                if (isImageInternal) {
                    isBusy = false
                    startImageLoop(context, uri, surface, isMirrored, rotationAngle, tag)
                    onPrepared?.invoke(null)
                    return@post
                }

                val exoPlayer = ExoPlayer.Builder(context.applicationContext).build()
                player = exoPlayer

                // Setup Effects: Mirroring and Rotation
                val effects = mutableListOf<Effect>()
                if (isMirrored || rotationAngle != 0) {
                    val scaleX = if (isMirrored) -1f else 1f
                    effects.add(
                        ScaleAndRotateTransformation.Builder()
                            .setScale(scaleX, 1f)
                            .setRotationDegrees(rotationAngle.toFloat())
                            .build()
                    )
                }
                if (effects.isNotEmpty()) {
                    exoPlayer.setVideoEffects(effects)
                }
                
                exoPlayer.setMediaItem(MediaItem.fromUri(uri))
                exoPlayer.setVideoSurface(surface)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE

                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPlayingInternal = isPlaying
                        if (isPlaying) startPositionPolling() else stopPositionPolling()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            if (!isBusy) return
                            isBusy = false
                            
                            videoWidth = exoPlayer.videoSize.width
                            videoHeight = exoPlayer.videoSize.height

                            try {
                                exoPlayer.play()
                                log(tag, "Player ACTIVE (${videoWidth}x${videoHeight}) - Image: false")
                                onPrepared?.invoke(exoPlayer)
                            } catch (e: Throwable) {
                                log(tag, "Start failed: ${e.message}")
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isBusy = false
                        log(tag, "Player Error: ${error.errorCodeName} | ${error.message}")
                        player?.release()
                        player = null
                        isPlayingInternal = false
                    }
                })

                exoPlayer.prepare()

            } catch (e: Throwable) {
                isBusy = false
                log(tag, "Prepare failed: ${e.message}")
                try { player?.release() } catch (_: Throwable) {}
                player = null
                isPlayingInternal = false
            }
        }
    }

    /**
     * Optional helper if Surface changes while the player is alive.
     */
    fun setSurface(surface: Surface?) {
        mainHandler.post {
            player?.setVideoSurface(surface)
        }
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            player?.let {
                currentPositionInternal = it.currentPosition
                if (isPlayingInternal) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
    }

    private fun startPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
        mainHandler.post(positionPoller)
    }

    private fun stopPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
    }
}
