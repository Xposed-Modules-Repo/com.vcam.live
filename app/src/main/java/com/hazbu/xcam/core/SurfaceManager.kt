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

    fun incrementSessionGeneration() {
        sessionGeneration++
        logAction("[System] NEW CAMERA SESSION | Generation: $sessionGeneration")
    }

    fun registerPreviewSurface(surface: Surface) {
        if (surface.isValid) {
            val id = SystemUtils.getSurfaceId(surface)
            logAction(
                "[SURFACE REGISTER] " +
                        "identity=${System.identityHashCode(surface)} " +
                        "id=$id " +
                        "surface=$surface"
            )
            if (nonPreviewSurfaceIds.contains(id)) {
                logAction("[Surface] Ignoring non-preview output | ID: $id")
                return
            }
            if (previewSurfaceIds.add(id)) {
                logAction("[Step 1] Surface Registered as PREVIEW | ID: $id")
            }
        }
    }

    fun registerImageReaderSurface(surface: Surface, format: Int, width: Int, height: Int) {
        if (!surface.isValid) return
        val id = SystemUtils.getSurfaceId(surface)
        nonPreviewSurfaceIds.add(id)
        previewSurfaceIds.remove(id)
        logAction("[ImageReader] Registered output | ID: $id | ${width}x${height} | format=0x${format.toString(16)}")
    }

    fun isPreviewSurface(surface: Surface?): Boolean {
        if (surface == null) return false
        val id = SystemUtils.getSurfaceId(surface)
        val exists = previewSurfaceIds.contains(id)

        logAction(
            "[SURFACE DEBUG] " +
                    "identity=${System.identityHashCode(surface)} " +
                    "id=$id " +
                    "surface=$surface " +
                    "preview=$exists"
        )

        if (nonPreviewSurfaceIds.contains(id)) return false
        if (!exists) {
            if (surface.toString().contains("SurfaceTexture")) {
                registerPreviewSurface(surface)
                return true
            }
            logAction("[Check] Surface ID $id is NOT in preview list | Current IDs: $previewSurfaceIds")
        }
        return exists
    }

    fun logSessionOutput(surface: Surface) {
        val id = SystemUtils.getSurfaceId(surface)
        logAction(
            "[SESSION OUTPUT]\n" +
                    "identity=${System.identityHashCode(surface)}\n" +
                    "id=$id\n" +
                    "preview=${previewSurfaceIds.contains(id)}\n" +
                    "surface=$surface"
        )
    }

    fun clearPreviewSurfaces(isEnginePlaying: Boolean) {
        if (isEnginePlaying) {
            logAction("[System] Keep IDs (Engine is playing)")
            return
        }
        logAction("[System] Clearing Surface IDs")
        previewSurfaceIds.clear()
        // ImageReader is usually created before CameraDevice creates its
        // capture session. Keep those identities so discovery cannot later
        // mistake the same output for a preview surface.
        previewSwapped = false
    }
}
