package com.hazbu.xcam

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.view.Surface
import io.github.libxposed.api.XposedModuleInterface

/**
 * Jalur Tempur Khusus Android 12 - 16 (API 31+)
 */
class XCamInjectorsModern(private val module: XCamModule) {

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        installModernDiscovery(param)
        installSurgicalDiverter()
        installModernHijack(param)
    }

    private fun installModernDiscovery(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")
            clazz.declaredMethods.filter { it.name.startsWith("createCaptureSession") }.forEach { method ->
                module.hook(method).intercept { chain ->
                    module.printLog("Modern: Session Discovery started")
                    chain.args.forEach { arg -> inspectSessionArgument(arg) }
                    chain.proceed()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun inspectSessionArgument(arg: Any?) {
        when (arg) {
            is Surface -> module.registerPreviewSurface(arg)
            is OutputConfiguration -> arg.surface?.let { module.registerPreviewSurface(it) }
            is Collection<*> -> arg.forEach { inspectSessionArgument(it) }
            is Array<*> -> arg.forEach { inspectSessionArgument(it) }
        }
    }

    private fun installSurgicalDiverter() {
        try {
            val builderClass = CaptureRequest.Builder::class.java
            val addTarget = builderClass.getDeclaredMethod("addTarget", Surface::class.java)
            module.hook(addTarget).intercept { chain ->
                val builder = chain.thisObject as? CaptureRequest.Builder
                val surface = chain.args.getOrNull(0) as? Surface
                
                if (builder != null && surface != null && module.mediaPath != null) {
                    val intent = try { builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT) } catch (_: Throwable) { -1 }
                    
                    if (intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                        module.triggerCaptureState()
                        return@intercept chain.proceed()
                    }

                    if (module.isPreviewSurface(surface)) {
                        module.printLog("Modern: Surgical Diverter redirecting")
                        val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummySurface() else chain.args[i] }
                        return@intercept chain.proceed(newArgs)
                    }
                }
                chain.proceed()
            }
        } catch (_: Throwable) {}
    }

    private fun installModernHijack(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val ocClass = param.classLoader.loadClass("android.hardware.camera2.params.OutputConfiguration")
            ocClass.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    try {
                        val surface = chain.args.getOrNull(0) as? Surface
                        if (surface != null && surface.isValid && module.mediaPath?.lowercase()?.endsWith(".mp4") == true) {
                            if (surface.toString().contains("SurfaceTexture") && !module.previewSwapped) {
                                module.printLog("Modern: Swapping OutputConfiguration")
                                module.previewSwapped = true
                                module.handleModernPreview(surface)
                                val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummySurface() else chain.args[i] }
                                return@intercept chain.proceed(newArgs)
                            }
                        }
                    } catch (_: Throwable) {}
                    chain.proceed()
                }
            }
        } catch (_: Throwable) {}
    }
}
