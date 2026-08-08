package com.hazbu.xcam

import android.graphics.SurfaceTexture
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(private val module: XCamModule) {

    fun installLegacyHooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val rendererClass = param.classLoader.loadClass("android.hardware.camera2.legacy.SurfaceTextureRenderer")
            val drawFrame = rendererClass.getDeclaredMethod("drawFrame", SurfaceTexture::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            
            module.hook(drawFrame).intercept { chain ->
                val width = (chain.args[1] as? Number)?.toInt() ?: 0
                val height = (chain.args[2] as? Number)?.toInt() ?: 0
                if (module.handlePreview(width, height)) null else chain.proceed()
            }
            module.printLog("Legacy Preview Hook: OK")
        } catch (e: Throwable) {}

        try {
            val legacyDeviceClass = param.classLoader.loadClass("android.hardware.camera2.legacy.LegacyCameraDevice")
            val produceFrame = legacyDeviceClass.getDeclaredMethods().find { it.name == "produceFrame" && it.parameterTypes.size == 5 && it.parameterTypes[1] == ByteArray::class.java }
            
            produceFrame?.let { method ->
                module.hook(method).intercept { chain ->
                    val format = (chain.args[4] as? Number)?.toInt() ?: 0
                    val width = (chain.args[2] as? Number)?.toInt() ?: 0
                    val height = (chain.args[3] as? Number)?.toInt() ?: 0
                    
                    if (format == 0x21) { 
                        val replacement = module.handleCapture(width, height)
                        if (replacement != null) {
                            val newArgs = Array(chain.args.size) { i -> if (i == 1) replacement else chain.args[i] }
                            return@intercept chain.proceed(newArgs)
                        }
                    }
                    chain.proceed()
                }
            }
            module.printLog("Legacy Capture Hook: OK")
        } catch (e: Throwable) {}
    }

    fun installCamera1Hooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val cameraClass = param.classLoader.loadClass("android.hardware.Camera")
            
            val setPreviewTexture = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)
            module.hook(setPreviewTexture).intercept { chain ->
                val originalST = chain.args[0] as? SurfaceTexture
                if (originalST != null && module.mediaPath != null) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = module.getDummyST()
                    module.handleCamera1Preview(originalST)
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }

            val takePicture = cameraClass.getDeclaredMethods().find { it.name == "takePicture" && it.parameterTypes.size >= 4 }
            takePicture?.let { method ->
                module.hook(method).intercept { chain ->
                    module.printLog("Camera1 Capture Triggered")
                    chain.proceed()
                }
            }
            
            module.printLog("Camera1 Hooks: OK")
        } catch (e: Throwable) {
            module.printLog("Camera1 Hooks failed")
        }
    }
}
