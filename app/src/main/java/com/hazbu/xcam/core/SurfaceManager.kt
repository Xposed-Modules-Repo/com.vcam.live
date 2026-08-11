package com.hazbu.xcam.core

import android.view.Surface
import com.hazbu.xcam.utils.SystemUtils

class SurfaceManager(private val logAction: (String) -> Unit) {
    private val previewSurfaceIds = mutableSetOf<Long>()
    var previewSwapped = false

    fun registerPreviewSurface(surface: Surface) {
        if (surface.isValid) {
            val id = SystemUtils.getSurfaceId(surface)
            if (previewSurfaceIds.add(id)) {
                logAction("[Step 1] Surface Registered as PREVIEW | ID: $id")
            }
        }
    }

    fun isPreviewSurface(surface: Surface?): Boolean {
        if (surface == null) return false
        val id = SystemUtils.getSurfaceId(surface)
        val exists = previewSurfaceIds.contains(id)
        if (!exists) {
            if (surface.toString().contains("SurfaceTexture")) {
                registerPreviewSurface(surface)
                return true
            }
            logAction("[Check] Surface ID $id is NOT in preview list | Current IDs: $previewSurfaceIds")
        }
        return exists
    }

    fun clearPreviewSurfaces(isEnginePlaying: Boolean) {
        if (isEnginePlaying) {
            logAction("[System] Keep IDs (Engine is playing)")
            return
        }
        logAction("[System] Clearing Surface IDs")
        previewSurfaceIds.clear()
        previewSwapped = false
    }
}
