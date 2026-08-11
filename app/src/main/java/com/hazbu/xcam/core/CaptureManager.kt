package com.hazbu.xcam.core

import android.content.Context
import android.os.Handler
import android.os.Looper

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

    private val uiHandler = Handler(Looper.getMainLooper())

    fun triggerCaptureState(currentPositionProvider: () -> Int) {
        val now = System.currentTimeMillis()
        if (now - lastCapturePulseTime < 2000) return
        lastCapturePulseTime = now

        captureTimeMs = try { currentPositionProvider() } catch (_: Throwable) { 0 }
        logAction("Capture pulse detected! Target Frame Time: $captureTimeMs ms")

        isCapturing = true
        cachedCaptureFrame = null

        contextProvider()?.let { refreshSettingsAction(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            isCapturing = false
            uiHandler.postDelayed({
                cachedCaptureFrame = null
                logAction("Capture Cache Cleared")
            }, 5000)
            logAction("Capture pulse ended")
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

            logAction("Hunter: One-time extraction at $captureTimeMs ms ($targetW x $targetH)")

            return try {
                setIgnoringHooks(true)
                val result = XCamCapture.createJpeg(context, actualPath, targetW, targetH, rotationAngle, isMirrored, captureTimeMs) { logAction(it) }
                cachedCaptureFrame = result
                result
            } catch (e: Throwable) {
                logAction("Hunter: Extraction failed: ${e.message}")
                null
            } finally {
                setIgnoringHooks(false)
            }
        }
    }
}
