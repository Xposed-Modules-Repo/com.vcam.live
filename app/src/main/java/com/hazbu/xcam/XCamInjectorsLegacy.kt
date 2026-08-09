package com.hazbu.xcam

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectorsLegacy(private val module: XCamModule) {

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        installMethod1(param)
        installMethod2(param)
    }

    @SuppressLint("PrivateApi")
    private fun installMethod1(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val rendererClass = param.classLoader.loadClass("android.hardware.camera2.legacy.SurfaceTextureRenderer")
            val drawFrame = rendererClass.getDeclaredMethod("drawFrame", SurfaceTexture::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            
            module.hook(drawFrame).intercept { chain ->
                val width = (chain.args.getOrNull(1) as? Number)?.toInt() ?: 0
                val height = (chain.args.getOrNull(2) as? Number)?.toInt() ?: 0
                if (module.handlePreview(width, height)) null else chain.proceed()
            }
            module.printLog("Legacy: Method 1 (Renderer) Hook OK")
        } catch (_: Throwable) {}

        try {
            val legacyDeviceClass = param.classLoader.loadClass("android.hardware.camera2.legacy.LegacyCameraDevice")
            val produceFrame = legacyDeviceClass.declaredMethods.find {
                it.name == "produceFrame" && it.parameterTypes.size == 5 && it.parameterTypes[1] == ByteArray::class.java 
            }
            
            produceFrame?.let { method ->
                module.hook(method).intercept { chain ->
                    val format = (chain.args[4] as? Number)?.toInt() ?: 0
                    val width = (chain.args[2] as? Number)?.toInt() ?: 0
                    val height = (chain.args[3] as? Number)?.toInt() ?: 0
                    
                    if (format == 0x21) { 
                        val replacement = module.handleCapture(width, height)
                        if (replacement != null) {
                            module.printLog("Legacy: Method 1 Replacing Capture Data")
                            val newArgs = Array(chain.args.size) { i -> if (i == 1) replacement else chain.args[i] }
                            return@intercept chain.proceed(newArgs)
                        }
                    }
                    chain.proceed()
                }
            }
        } catch (_: Throwable) {}
    }

    fun installMethod2(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val cameraClass = param.classLoader.loadClass("android.hardware.Camera")
            val setPreviewTexture = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)
            module.hook(setPreviewTexture).intercept { chain ->
                val originalST = chain.args.getOrNull(0) as? SurfaceTexture
                if (originalST != null && module.mediaPath != null) {
                    module.printLog("Legacy: Method 2 Hook Detected")
                    val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummyST() else chain.args[i] }
                    module.handleCamera1Preview(originalST)
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }

            val takePicture = cameraClass.getDeclaredMethods().find { it.name == "takePicture" && it.parameterTypes.size >= 4 }
            takePicture?.let { method ->
                module.hook(method).intercept { chain ->
                    module.printLog("Legacy: Method 2 Capture Triggered")
                    module.triggerCaptureState()
                    chain.proceed()
                }
            }
            module.printLog("Legacy: Method 2 (Direct) Hooks OK")
        } catch (e: Throwable) {
            module.printLog("Legacy: Method 2 failed", e)
        }
    }
}
