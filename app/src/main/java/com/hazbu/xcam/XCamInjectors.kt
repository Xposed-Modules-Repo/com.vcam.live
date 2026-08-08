package com.hazbu.xcam

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.view.Surface
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(private val module: XCamModule) {

    fun installLegacyHooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val rendererClass = param.classLoader.loadClass("android.hardware.camera2.legacy.SurfaceTextureRenderer")
            val drawFrame = rendererClass.getDeclaredMethod("drawFrame", SurfaceTexture::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            module.hook(drawFrame).intercept { chain ->
                val width = (chain.args.getOrNull(1) as? Number)?.toInt() ?: 0
                val height = (chain.args.getOrNull(2) as? Number)?.toInt() ?: 0
                if (module.handlePreview(width, height)) null else chain.proceed()
            }
        } catch (e: Throwable) {}
    }

    fun installCamera1Hooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val cameraClass = param.classLoader.loadClass("android.hardware.Camera")
            val setPreviewTexture = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)
            module.hook(setPreviewTexture).intercept { chain ->
                val originalST = chain.args[0] as? SurfaceTexture
                if (originalST != null && module.mediaPath?.lowercase()?.endsWith(".mp4") == true) {
                    val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummyST() else chain.args[i] }
                    module.handleCamera1Preview(originalST)
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }
        } catch (e: Throwable) {}
    }

    fun installUniversalCaptureHooks(param: XposedModuleInterface.PackageReadyParam) {
        installImageReaderHook(param)
        installStillCaptureHook(param)
        installBitmapHunter()
        installModernHijack(param)
        installSurgicalDiverter(param)
    }

    private fun installImageReaderHook(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val irClass = param.classLoader.loadClass("android.media.ImageReader")
            val getSurface = irClass.getDeclaredMethod("getSurface")
            module.hook(getSurface).intercept { chain ->
                val surface = chain.proceed() as? Surface
                if (surface != null) module.markAsCaptureSurface(surface)
                surface
            }
        } catch (e: Throwable) {}
    }

    private fun installStillCaptureHook(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val sessionClass = param.classLoader.loadClass("android.hardware.camera2.impl.CameraCaptureSessionImpl")
            val methods = sessionClass.declaredMethods.filter { it.name == "capture" || it.name == "captureBurst" }
            methods.forEach { method ->
                module.hook(method).intercept { chain ->
                    try {
                        val request = chain.args.firstOrNull { it is CaptureRequest } as? CaptureRequest
                        if (request != null) {
                            val intent = try { request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) } catch (_: Throwable) { null }
                            if (intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                                module.printLog("xCam STILL_CAPTURE detected")
                                module.triggerCaptureState()
                            }
                        }
                    } catch (e: Throwable) {}
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {}
    }

    private fun installBitmapHunter() {
        try {
            val bfClass = BitmapFactory::class.java
            bfClass.getDeclaredMethods().filter { it.name == "decodeByteArray" }.forEach { method ->
                module.hook(method).intercept { chain ->
                    if (module.isCapturingState()) {
                        module.printLog("xCam Hunter: Replacing captured bytes")
                        val replacement = module.handleCapture(1280, 1280)
                        if (replacement != null) {
                            val newArgs = Array(chain.args.size) { i -> 
                                if (i == 0) replacement 
                                else if (i == 2 && chain.args.size >= 3) replacement.size
                                else chain.args[i] 
                            }
                            return@intercept chain.proceed(newArgs)
                        }
                    }
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {}
    }

    private fun installModernHijack(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val ocClass = param.classLoader.loadClass("android.hardware.camera2.params.OutputConfiguration")
            ocClass.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    try {
                        val surface = chain.args.getOrNull(0) as? Surface
                        if (surface != null && surface.isValid && module.mediaPath?.lowercase()?.endsWith(".mp4") == true) {
                            if (module.isCaptureSurface(surface)) return@intercept chain.proceed()
                            
                            if (surface.toString().contains("SurfaceTexture") && !module.previewSwapped) {
                                module.printLog("xCam Modern Hijack: Swapping Preview")
                                module.previewSwapped = true
                                module.handleModernPreview(surface)
                                val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummySurface() else chain.args[i] }
                                return@intercept chain.proceed(newArgs)
                            }
                        }
                    } catch (e: Throwable) {}
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {}
    }

    private fun installSurgicalDiverter(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val builderClass = param.classLoader.loadClass("android.hardware.camera2.CaptureRequest\$Builder")
            val addTarget = builderClass.getDeclaredMethod("addTarget", Surface::class.java)
            module.hook(addTarget).intercept { chain ->
                val builder = chain.thisObject as? CaptureRequest.Builder
                val surface = chain.args[0] as? Surface
                
                if (builder != null && surface != null && module.mediaPath != null) {
                    val intent = try { builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT) } catch (_: Throwable) { -1 }
                    
                    // CRITICAL FIX v16.5: Jika intent adalah STILL_CAPTURE, JANGAN dibelokkan
                    if (intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                        module.printLog("xCam Diverter: Capture target detected - Allowing original data flow")
                        module.triggerCaptureState() // Trigger hunter lebih awal
                        return@intercept chain.proceed()
                    }

                    if (module.isPreviewSurface(surface)) {
                        module.printLog("xCam Diverter: Redirecting Preview to Dummy")
                        val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummySurface() else chain.args[i] }
                        return@intercept chain.proceed(newArgs)
                    }
                }
                chain.proceed()
            }
        } catch (e: Throwable) {}
    }

    fun installAndroid16UIHooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")
            clazz.declaredMethods.filter { it.name.startsWith("createCaptureSession") }.forEach { method ->
                module.hook(method).intercept { chain ->
                    module.printLog("xCam ===== CAMERA SESSION CREATE =====")
                    chain.args.forEach { arg -> inspectSessionArgument(arg) }
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {}
    }

    private fun inspectSessionArgument(arg: Any?) {
        when (arg) {
            is Surface -> module.registerPreviewSurface(arg)
            is OutputConfiguration -> arg.surface?.let { module.registerPreviewSurface(it) }
            is Collection<*> -> arg.forEach { inspectSessionArgument(it) }
            is Array<*> -> arg.forEach { inspectSessionArgument(it) }
        }
    }
}
