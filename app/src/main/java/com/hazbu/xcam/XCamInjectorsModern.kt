package com.hazbu.xcam

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.view.Surface
import android.view.SurfaceHolder
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectorsModern(private val module: XCamModule) {

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        installModernDiscovery(param)
        installSurgicalDiverter()
        installModernHijack(param)
        installUIFallbackHooks(param)
    }

    @SuppressLint("PrivateApi")
    private fun installModernDiscovery(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")
            val methods = clazz.declaredMethods.filter { it.name.startsWith("createCaptureSession") }
            methods.forEach { method ->
                module.hook(method).intercept { chain ->
                    module.clearPreviewSurfaces()
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
                        module.printLog("Modern Diverter: Redirecting Preview")
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
                                module.printLog("Modern Hijack: Swapping Preview")
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

    private fun installUIFallbackHooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val tvClass = param.classLoader.loadClass("android.view.TextureView")
            val setSt = tvClass.getDeclaredMethod("setSurfaceTexture", SurfaceTexture::class.java)
            module.hook(setSt).intercept { chain ->
                val st = chain.args[0] as? SurfaceTexture
                if (st != null) module.handleCamera1Preview(st)
                chain.proceed()
            }

            val svClass = param.classLoader.loadClass("android.view.SurfaceView")
            val getHolder = svClass.getDeclaredMethod("getHolder")
            module.hook(getHolder).intercept { chain ->
                val holder = chain.proceed() as? SurfaceHolder
                holder?.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { module.handleSurfaceViewPreview(h) }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) { module.handleSurfaceViewPreview(h) }
                    override fun surfaceDestroyed(h: SurfaceHolder) { module.stopCamera1Engine() }
                })
                holder
            }
        } catch (_: Throwable) {}
    }
}
