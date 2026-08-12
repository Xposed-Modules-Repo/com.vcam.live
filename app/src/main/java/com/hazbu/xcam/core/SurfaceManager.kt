package com.hazbu.xcam.core

import android.view.Surface
import com.hazbu.xcam.utils.SystemUtils

class SurfaceManager(private val logAction: (String) -> Unit) {
    private val previewSurfaceIds = mutableSetOf<Long>()
    /** Outputs owned by ImageReader are capture/analysis buffers, never viewfinders. */
    private val nonPreviewSurfaceIds = mutableSetOf<Long>()
    var previewSwapped = false

    var sessionGeneration = 0
        private set
    private var lastIncrementTime = 0L
    private var lastClearedGen = -1

    private fun logSurface(msg: String) {
        logAction("[SURFACE] $msg")
    }

    fun incrementSessionGeneration(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastIncrementTime < 500) return false // Debounce rapid triggers
        
        sessionGeneration++
        lastIncrementTime = now
        logSurface("[*] NEW CAMERA SESSION (Gen $sessionGeneration)")
        return true
    }

    fun registerPreviewSurface(surface: Surface) {
        if (surface.isValid) {
            val id = SystemUtils.getSurfaceId(surface)
            if (nonPreviewSurfaceIds.contains(id)) {
                logSurface("[!] Ignoring non-preview output | ID: $id")
                return
            }
            if (previewSurfaceIds.add(id)) {
                logSurface("[*] REGISTER: id=$id surface=$surface")
                logSurface("[+] Registered as PREVIEW | ID: $id")
            }
        }
    }

    fun registerImageReaderSurface(surface: Surface, format: Int, width: Int, height: Int) {
        if (!surface.isValid) return
        val id = SystemUtils.getSurfaceId(surface)
        nonPreviewSurfaceIds.add(id)
        previewSurfaceIds.remove(id)
        logSurface("[+] ImageReader Registered | ID: $id | ${width}x${height} | format=0x${format.toString(16)}")
    }

    fun isPreviewSurface(surface: Surface?): Boolean {
        if (surface == null) return false
        val id = SystemUtils.getSurfaceId(surface)
        val exists = previewSurfaceIds.contains(id)

        logSurface("[*] CHECK: id=$id preview=$exists")

        if (nonPreviewSurfaceIds.contains(id)) return false
        if (!exists) {
            if (surface.toString().contains("SurfaceTexture")) {
                registerPreviewSurface(surface)
                return true
            }
            logSurface("[!] ID $id is NOT in preview list | Current IDs: $previewSurfaceIds")
        }
        return exists
    }

    fun logSessionOutput(surface: Surface) {
        val id = SystemUtils.getSurfaceId(surface)
        logSurface("[*] SESSION OUTPUT: id=$id preview=${previewSurfaceIds.contains(id)} surface=$surface")
    }

    fun clearPreviewSurfaces(isEnginePlaying: Boolean) {
        if (lastClearedGen == sessionGeneration) return // Already handled this generation
        lastClearedGen = sessionGeneration
        previewSwapped = false // Always allow re-swapping on a new generation

        if (isEnginePlaying) {
            logSurface("[*] Keep IDs (Engine is playing)")
            return
        }
        logSurface("[*] Clearing Surface IDs")
        previewSurfaceIds.clear()
    }
}
