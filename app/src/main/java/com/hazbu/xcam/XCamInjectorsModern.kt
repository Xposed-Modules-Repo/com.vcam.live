package com.hazbu.xcam

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.view.Surface
import android.view.SurfaceHolder
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectorsModern(private val module: XCamModule) {

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
                    val sessionType = if (method.name.contains("Internal")) "INTERNAL" else "STANDARD"
                    module.printLog("[Discovery] CameraDeviceImpl#${method.name} ($sessionType) | Args: ${chain.args.size}")
                    module.clearPreviewSurfaces()
                    chain.args.forEach { arg -> inspectSessionArgument(arg) }
                    chain.proceed()
                }
            }

            val closeMethod = clazz.getDeclaredMethod("close")
            module.hook(closeMethod).intercept { chain ->
                module.printLog("Hooked: CameraDeviceImpl#close()")
                module.stopEngineWithDelay()
                chain.proceed()
            }
        } catch (e: Throwable) {
            module.printLog("Discovery Hook failed", e)
        }
    }

    private fun inspectSessionArgument(arg: Any?) {
        when (arg) {
            is Surface -> {
                module.printLog("Modern Discovery: Surface detected (${arg.hashCode()}) - ${arg}")
                module.registerPreviewSurface(arg)
            }
            is OutputConfiguration -> {
                val surface = arg.surface
                module.printLog("Modern Discovery: OutputConfiguration surface (${surface?.hashCode()}) - ${surface}")
                surface?.let { module.registerPreviewSurface(it) }
            }
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
                        module.printLog("Hooked: CaptureRequest.Builder#addTarget [STILL_CAPTURE] on Surface(${surface.hashCode()})")
                        module.triggerCaptureState()
                        return@intercept chain.proceed()
                    }

                    if (module.isPreviewSurface(surface)) {
                        module.printLog("Hooked: CaptureRequest.Builder#addTarget [PREVIEW_DIVERT] on Surface(${surface.hashCode()})")
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
                            val isST = surface.toString().contains("SurfaceTexture")
                            module.printLog("[Hijack] OutputConfiguration Constructor | Surface: $surface | isST: $isST")
                            
                            module.registerPreviewSurface(surface)
                            
                            if (isST && !module.previewSwapped) {
                                module.printLog("[Hijack] ACTION: Swapping Preview Surface...")
                                module.previewSwapped = true
                                module.handleModernPreview(surface)
                                val newArgs = Array(chain.args.size) { i -> if (i == 0) module.getDummySurface() else chain.args[i] }
                                return@intercept chain.proceed(newArgs)
                            }
                        }
                    } catch (e: Throwable) {
                        module.printLog("[Hijack] Error in constructor hook", e)
                    }
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
                if (st != null) {
                    module.printLog("UI Hook: TextureView setSurfaceTexture (ST: ${st.hashCode()})")
                    module.handleCamera1Preview(st)
                }
                chain.proceed()
            }

            val svClass = param.classLoader.loadClass("android.view.SurfaceView")
            val getHolder = svClass.getDeclaredMethod("getHolder")
            module.hook(getHolder).intercept { chain ->
                val holder = chain.proceed() as? SurfaceHolder
                if (holder != null) {
                    module.printLog("UI Hook: SurfaceView getHolder (Holder: ${holder.hashCode()})")
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { 
                            module.printLog("UI Hook: SurfaceView Callback -> surfaceCreated (Surface: ${h.surface.hashCode()})")
                            // Wait a bit to see if this surface gets registered in a camera session
                            uiHandler.postDelayed({
                                module.handleSurfaceViewPreview(h)
                            }, 500)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) { 
                            module.printLog("UI Hook: SurfaceView Callback -> surfaceChanged ($w x $h2)")
                            module.handleSurfaceViewPreview(h) 
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) { 
                            module.printLog("UI Hook: SurfaceView Callback -> surfaceDestroyed")
                            module.stopCamera1Engine() 
                        }
                    })
                }
                holder
            }
        } catch (e: Throwable) {
            module.printLog("UI Fallback Hooks failed", e)
        }
    }
}
