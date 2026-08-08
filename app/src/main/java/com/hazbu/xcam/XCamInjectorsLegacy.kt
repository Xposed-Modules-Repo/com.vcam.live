package com.hazbu.xcam

import android.graphics.SurfaceTexture
import io.github.libxposed.api.XposedModuleInterface

/**
 * Jalur Tempur Khusus Android 9, 10, 11 (API 28-30)
 */
class XCamInjectorsLegacy(private val module: XCamModule) {

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        installCamera1Hooks(param)
        installLegacyCamera2Hooks(param)
    }

    private fun installCamera1Hooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val cameraClass = param.classLoader.loadClass("android.hardware.Camera")
            val setPreviewTexture = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)

            module.hook(setPreviewTexture).intercept { chain ->
                val originalST = chain.args.getOrNull(0) as? SurfaceTexture
                if (originalST != null && module.mediaPath?.lowercase()?.endsWith(".mp4") == true) {
                    module.printLog("Legacy: Camera1 Preview Hijack")
                    val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummyST() else chain.args[i] }
                    module.handleCamera1Preview(originalST)
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }
        } catch (_: Throwable) {}
    }

    private fun installLegacyCamera2Hooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val rendererClass = param.classLoader.loadClass("android.hardware.camera2.legacy.SurfaceTextureRenderer")
            val drawFrame = rendererClass.getDeclaredMethod("drawFrame", SurfaceTexture::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            
            module.hook(drawFrame).intercept { chain ->
                val width = (chain.args.getOrNull(1) as? Number)?.toInt() ?: 0
                val height = (chain.args.getOrNull(2) as? Number)?.toInt() ?: 0
                if (module.handlePreview(width, height)) null else chain.proceed()
            }
        } catch (_: Throwable) {}
    }
}
