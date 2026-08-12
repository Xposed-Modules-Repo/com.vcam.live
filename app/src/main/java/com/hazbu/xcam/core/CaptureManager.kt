package com.hazbu.xcam.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

class CaptureManager(
    private val contextProvider: () -> Context?,
    private val refreshSettingsAction: (Context) -> Unit,
    private val logAction: (String) -> Unit
) {
    @Volatile
    var isCapturing = false
        private set

    @Volatile
    var cachedCaptureFrame: ByteArray? = null
        private set

    var lastCapturePulseTime = 0L
    var captureTimeMs = 0

    @Volatile private var cachedStreamFrame: ByteArray? = null
    private var streamStartedAtMs = 0L
    private var lastStreamRequestAtMs = 0L
    private var streamExtractionRunning = false
    private var streamKey: String? = null
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val streamFrameIntervalMs = 500L

    private val uiHandler = Handler(Looper.getMainLooper())

    private fun logCapture(msg: String) {
        logAction("[CAPTURE] $msg")
    }

    fun triggerCaptureState(currentPositionProvider: () -> Int) {
        val now = System.currentTimeMillis()
        if (now - lastCapturePulseTime < 2000) return
        lastCapturePulseTime = now

        captureTimeMs = try { currentPositionProvider() } catch (_: Throwable) { 0 }
        logCapture("[*] Pulse detected! Target Frame Time: $captureTimeMs ms")

        isCapturing = true
        cachedCaptureFrame = null

        contextProvider()?.let { refreshSettingsAction(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            isCapturing = false
            uiHandler.postDelayed({
                cachedCaptureFrame = null
                logCapture("[*] Cache Cleared")
            }, 5000)
            logCapture("[*] Pulse ended")
        }, 3000)
    }

    fun handleCapture(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit
    ): ByteArray? {
        if (isIgnoringHooks()) return null
        val context = contextProvider() ?: return null
        val actualPath = path ?: return null

        synchronized(this) {
            cachedCaptureFrame?.let { return it }

            val targetW = if (width > 0) width else 1280
            val targetH = if (height > 0) height else 1280

            logCapture("[*] One-time extraction at $captureTimeMs ms ($targetW x $targetH)")

            return try {
                setIgnoringHooks(true)
                val result = XCamCapture.createJpeg(context, actualPath, targetW, targetH, rotationAngle, isMirrored, captureTimeMs) { logCapture(it) }
                cachedCaptureFrame = result
                logCapture("[+] Extraction successful")
                result
            } catch (e: Throwable) {
                logCapture("[!] Extraction failed: " + e.message)
                null
            } finally {
                setIgnoringHooks(false)
            }
        }
    }

    /**
     * Returns the newest decoded video frame without blocking a camera callback.
     * A lower refresh rate is deliberate: converting a high-resolution YUV
     * ImageReader frame in Java is expensive, while the last frame remains safe
     * to deliver until the worker has decoded the next one.
     */
    fun handleStreamFrame(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit
    ): ByteArray? {
        val context = contextProvider() ?: return null
        val actualPath = path ?: return null
        if (!actualPath.lowercase().endsWith(".mp4")) {
            return handleCapture(path, width, height, rotationAngle, isMirrored, isIgnoringHooks, setIgnoringHooks)
        }

        val now = SystemClock.elapsedRealtime()
        val key = "$actualPath|$width|$height|$rotationAngle|$isMirrored"
        var requestPositionMs = -1L
        synchronized(this) {
            if (streamKey != key) {
                streamKey = key
                cachedStreamFrame = null
                streamStartedAtMs = now
                lastStreamRequestAtMs = 0L
                streamExtractionRunning = false
            }
            if (!streamExtractionRunning && now - lastStreamRequestAtMs >= streamFrameIntervalMs) {
                streamExtractionRunning = true
                lastStreamRequestAtMs = now
                requestPositionMs = now - streamStartedAtMs
            }
        }

        if (requestPositionMs >= 0L) {
            val frameTimeMs = requestPositionMs.toInt().coerceAtLeast(0)
            streamExecutor.execute {
                try {
                    setIgnoringHooks(true)
                    val frame = XCamCapture.createJpeg(
                        context, actualPath, width, height, rotationAngle, isMirrored, frameTimeMs
                    ) { logCapture(it) }
                    if (frame != null) cachedStreamFrame = frame
                } catch (e: Throwable) {
                    logCapture("Stream extraction failed: ${e.message}")
                } finally {
                    setIgnoringHooks(false)
                    synchronized(this) { streamExtractionRunning = false }
                }
            }
        }
        return cachedStreamFrame
    }
}
