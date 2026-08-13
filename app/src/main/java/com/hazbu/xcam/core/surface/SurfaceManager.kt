package com.hazbu.xcam.core.surface

import android.view.Surface
import com.hazbu.xcam.utils.SystemUtils

/**
 * Manages Surface identification and classification (Preview vs. ImageReader).
 * Uses native object IDs to track surfaces across different API hooks.
 */
class SurfaceManager(private val logAction: (String) -> Unit) {
    private val previewIds = mutableSetOf<Long>()
    private val nonPreviewIds = mutableSetOf<Long>()
    
    var previewSwapped = false
    var sessionGeneration = 0
        private set
        
    private var lastIncrementTime = 0L
    private var lastClearedGen = -1

    private val Surface.id get() = SystemUtils.getSurfaceId(this)
    private fun log(msg: String) = logAction("[SURFACE] $msg")

    fun incrementSessionGeneration(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastIncrementTime < 500) return false
        
        sessionGeneration++
        lastIncrementTime = now
        log("[*] NEW CAMERA SESSION (Gen $sessionGeneration)")
        return true
    }

    fun registerPreviewSurface(surface: Surface) {
        val id = surface.id
        if (surface.isValid && !nonPreviewIds.contains(id) && previewIds.add(id)) {
            log("[+] Registered PREVIEW | ID: $id")
        }
    }

    fun registerImageReaderSurface(surface: Surface, format: Int, width: Int, height: Int) {
        val id = surface.id
        if (surface.isValid) {
            nonPreviewIds.add(id)
            previewIds.remove(id)
            log("[+] ImageReader Registered | ID: $id | ${width}x${height} | 0x${format.toString(16)}")
        }
    }

    fun isPreviewSurface(surface: Surface?): Boolean {
        val id = surface?.id ?: return false
        if (nonPreviewIds.contains(id)) return false
        
        return previewIds.contains(id) || surface.toString().contains("SurfaceTexture").also {
            if (it) registerPreviewSurface(surface)
        }
    }

    fun logSessionOutput(surface: Surface) {
        log("[*] SESSION OUTPUT: id=${surface.id} preview=${previewIds.contains(surface.id)}")
    }

    fun clearPreviewSurfaces(isEnginePlaying: Boolean) {
        if (lastClearedGen == sessionGeneration) return
        lastClearedGen = sessionGeneration
        previewSwapped = false

        if (!isEnginePlaying) {
            log("[*] Clearing Surface IDs")
            previewIds.clear()
        }
    }
}
