package com.hazbu.xcam.core

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.net.toUri
import com.hazbu.xcam.utils.SystemUtils

class XCamEngine(
    private val contextProvider: () -> Context?,
    private val settingsProvider: () -> SettingsManager,
    private val surfaceManager: SurfaceManager,
    private val logAction: (String) -> Unit
) {
    private var c1MediaPlayer: MediaPlayer? = null
    private var c1Surface: Surface? = null
    private var lastST: SurfaceTexture? = null
    private var lastModernSurface: Surface? = null
    private var dummyST: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    
    private var xRenderer: XCamRenderer? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    
    @Volatile
    private var isPlayerBusy = false
    private var lastInjectedGeneration = -1

    fun isPlaying() = c1MediaPlayer?.isPlaying == true
    fun getCurrentPosition() = c1MediaPlayer?.currentPosition ?: 0

    private fun logPipe(msg: String) {
        logAction("[PIPELINE] $msg")
    }

    private fun logMedia(msg: String) {
        logAction("[MEDIA] $msg")
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        logAction("[PIPELINE] [!] ERROR: $msg ${tr?.message ?: ""}")
    }

    fun stopCamera1Engine() {
        uiHandler.removeCallbacksAndMessages("STOP_SIGNAL")
        logPipe("Engine STOP triggered")
        try {
            isPlayerBusy = true
            c1MediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.reset()
                player.release()
            }
        } catch (e: Throwable) {
            logError("Stopping engine failed", e)
        }
        c1MediaPlayer = null
        isPlayerBusy = false
        c1Surface = null
        lastST = null
        lastModernSurface = null
    }

    private fun cancelPendingStop() {
        uiHandler.removeCallbacksAndMessages("STOP_SIGNAL")
    }

    fun handlePreview(width: Int, height: Int): Boolean {
        val settings = settingsProvider()
        val path = settings.mediaPath ?: return false
        if (!path.lowercase().endsWith(".mp4")) return false
        val context = contextProvider() ?: return false

        return try {
            if (xRenderer == null || xRenderer?.currentPath != path) {
                xRenderer?.release()
                xRenderer = XCamRenderer(context, path, settings.isMirrored, settings.rotationAngle) { logAction(it) }
            }
            xRenderer?.draw(width, height) ?: false
        } catch (_: Throwable) { false }
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        val settings = settingsProvider()
        val path = settings.mediaPath ?: return
        val context = contextProvider() ?: return
        val surface = Surface(st)

        if (!surfaceManager.isPreviewSurface(surface)) {
            logPipe("Legacy Hook: Ignoring non-preview SurfaceTexture (${surface.hashCode()})")
            surface.release()
            return
        }

        if (st == lastST && (c1MediaPlayer?.isPlaying == true || isPlayerBusy)) return
        lastST = st

        try {
            stopCamera1Engine()
            c1Surface = surface

            if (path.lowercase().endsWith(".mp4")) {
                isPlayerBusy = true
                c1MediaPlayer = MediaPlayer().apply {
                    setDataSource(context, path.toUri())
                    setSurface(c1Surface)
                    isLooping = true
                    prepareAsync()
                    setOnPreparedListener {
                        isPlayerBusy = false
                        it.start()
                        logMedia("Legacy Player ACTIVE")
                    }
                    setOnErrorListener { _, what, extra ->
                        isPlayerBusy = false
                        logMedia("Legacy Player Error ($what, $extra)")
                        stopCamera1Engine(); true
                    }
                }
            }
        } catch (e: Exception) {
            isPlayerBusy = false
            logPipe("Legacy Error: ${e.message}")
        }
    }

    fun handleModernPreview(surface: Surface) {
        if (surface == lastModernSurface && (c1MediaPlayer?.isPlaying == true || isPlayerBusy)) return
        lastModernSurface = surface
        uiHandler.post {
            if (!surface.isValid) return@post
            val context = contextProvider() ?: return@post
            val settings = settingsProvider()
            val path = settings.mediaPath ?: return@post
            injectToSurface(surface, context, path)
        }
    }

    fun handleSurfaceViewPreview(holder: SurfaceHolder) {
        uiHandler.post {
            val surface = holder.surface
            if (!surface.isValid) return@post

            if (!surfaceManager.isPreviewSurface(surface)) {
                logPipe("Modern Hook: Ignoring non-preview SurfaceView (${surface.hashCode()})")
                return@post
            }

            val context = contextProvider() ?: return@post
            val settings = settingsProvider()
            val path = settings.mediaPath ?: return@post
            injectToSurface(surface, context, path)
        }
    }

    fun injectToSurface(surface: Surface, context: Context, path: String) {
        synchronized(this) {
            val surfaceId = SystemUtils.getSurfaceId(surface)
            val currentEngineId = SystemUtils.getSurfaceId(c1Surface)
            val currentGeneration = surfaceManager.sessionGeneration

            logPipe("Injection Request | ID: $surfaceId | Gen: $currentGeneration | Last: $lastInjectedGeneration")

            if (!surface.isValid) {
                logPipe("Injection ABORTED: Surface is INVALID")
                return
            }

            if (surfaceId == currentEngineId && 
                c1MediaPlayer?.isPlaying == true && 
                currentGeneration == lastInjectedGeneration) {
                logPipe("Injection IGNORED: Already playing on this surface/session")
                return
            }

            try {
                if (currentGeneration != lastInjectedGeneration) {
                    logPipe("New Session Detected (Gen $currentGeneration). Forcing restart.")
                } else {
                    logPipe("Forcing restart for new surface...")
                }
                
                stopCamera1Engine()
                lastInjectedGeneration = currentGeneration

                isPlayerBusy = true
                c1Surface = surface
                val newPlayer = MediaPlayer()
                c1MediaPlayer = newPlayer
                cancelPendingStop()

                newPlayer.apply {
                    setDataSource(context, path.toUri())
                    try {
                        setSurface(surface)
                    } catch (e: Exception) {
                        logPipe("FATAL: setSurface failed: ${e.message}")
                        isPlayerBusy = false
                        return
                    }

                    isLooping = true
                    setOnPreparedListener {
                        isPlayerBusy = false
                        try {
                            it.start()
                            logMedia("Engine ACTIVE: Playing on ID: $surfaceId")
                        } catch (e: Throwable) {
                            logMedia("Engine Start Failed: ${e.message}")
                        }
                    }
                    setOnErrorListener { _, what, extra ->
                        isPlayerBusy = false
                        logError("Engine Error ($what, $extra)")
                        stopCamera1Engine(); true
                    }
                    try {
                        logPipe("Engine Preparing for ID: $surfaceId")
                        prepareAsync()
                    } catch (e: Exception) {
                        isPlayerBusy = false
                        logError("Engine prepareAsync fatal error", e)
                    }
                }
            } catch (e: Throwable) {
                isPlayerBusy = false
                logError("Modern Injection Error", e)
            }
        }
    }

    fun getDummyST(): SurfaceTexture {
        if (dummyST == null) {
            dummyST = SurfaceTexture(999).apply {
                setOnFrameAvailableListener { try { updateTexImage() } catch (_: Exception) {} }
            }
            try { dummyST?.detachFromGLContext() } catch (_: Throwable) {}
        }
        return dummyST!!
    }

    fun getDummySurface(): Surface {
        if (dummySurface == null || !dummySurface!!.isValid) {
            dummySurface = Surface(getDummyST())
        }
        return dummySurface!!
    }
}
