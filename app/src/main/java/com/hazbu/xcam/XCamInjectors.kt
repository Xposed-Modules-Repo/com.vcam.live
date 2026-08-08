package com.hazbu.xcam

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import io.github.libxposed.api.XposedModule
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
        } catch (e: Throwable) {}
    }

    fun installUniversalDiscoveryHooks(param: XposedModuleInterface.PackageReadyParam) {
        // 1. Camera1 Discovery (Classic)
        try {
            val cameraClass = param.classLoader.loadClass("android.hardware.Camera")
            
            val setPreviewDisplay = cameraClass.getDeclaredMethod("setPreviewDisplay", SurfaceHolder::class.java)
            module.hook(setPreviewDisplay).intercept { chain ->
                module.printLog("Camera1 Discovery: setPreviewDisplay detected (SurfaceView path)")
                chain.proceed()
            }

            val setPreviewTexture = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)
            module.hook(setPreviewTexture).intercept { chain ->
                module.printLog("Camera1 Discovery: setPreviewTexture detected (TextureView path)")
                chain.proceed()
            }
        } catch (e: Throwable) {}

        // 2. Camera2 Modern Discovery
        try {
            val managerClass = param.classLoader.loadClass("android.hardware.camera2.CameraManager")
            val openCamera = managerClass.getDeclaredMethods().find { it.name == "openCamera" && it.parameterTypes.size >= 3 }
            openCamera?.let { method ->
                module.hook(method).intercept { chain ->
                    module.printLog("Camera2 Modern: openCamera detected for ID ${chain.args[0]}")
                    chain.proceed()
                }
            }

            val builderClass = param.classLoader.loadClass("android.hardware.camera2.CaptureRequest\$Builder")
            val addTarget = builderClass.getDeclaredMethod("addTarget", Surface::class.java)
            module.hook(addTarget).intercept { chain ->
                module.printLog("Camera2 Modern: addTarget detected (Surface: ${chain.args[0]})")
                chain.proceed()
            }
        } catch (e: Throwable) {}

        // 3. SurfaceTexture Sync (The Ultimate Gate)
        try {
            val stClass = SurfaceTexture::class.java
            val updateTexImage = stClass.getDeclaredMethod("updateTexImage")
            var lastLog = 0L
            module.hook(updateTexImage).intercept { chain ->
                val now = System.currentTimeMillis()
                if (now - lastLog > 5000) { // Log every 5 seconds to avoid flooding
                    module.printLog("SurfaceTexture: updateTexImage is being called frequently (UI is active)")
                    lastLog = now
                }
                chain.proceed()
            }
        } catch (e: Throwable) {}
    }
}
