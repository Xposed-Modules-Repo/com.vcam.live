package com.hazbu.xcam.core.surface

import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * Provides and manages dummy surfaces used for diverting original camera output.
 */
class SurfaceProvider(private val logAction: (String) -> Unit) {
    private var dummyST: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    private fun log(msg: String) = logAction("[SURFACE-PROVIDER] $msg")

    fun getDummySurface(): Surface {
        if (dummySurface == null || !dummySurface!!.isValid) {
            log("Creating new dummy surface")
            val st = SurfaceTexture(999).apply {
                setOnFrameAvailableListener { 
                    try { updateTexImage() } catch (_: Exception) {} 
                }
            }
            try { st.detachFromGLContext() } catch (_: Throwable) {}
            dummyST?.release()
            dummyST = st
            dummySurface = Surface(st)
        }
        return dummySurface!!
    }

    fun release() {
        dummySurface?.release()
        dummyST?.release()
        dummySurface = null
        dummyST = null
    }
}
