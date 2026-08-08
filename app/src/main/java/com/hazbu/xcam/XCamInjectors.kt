package com.hazbu.xcam

import android.graphics.BitmapFactory
import android.os.Build
import io.github.libxposed.api.XposedModuleInterface

/**
 * Polisi Lalu Lintas: Mendeteksi Versi OS dan Mengarahkan ke Injector yang Tepat.
 */
class XCamInjectors(private val module: XCamModule) {

    private val legacyInjector = XCamInjectorsLegacy(module)
    private val modernInjector = XCamInjectorsModern(module)

    fun installLegacyHooks(param: XposedModuleInterface.PackageReadyParam) {
        // Hanya install jika OS di bawah Android 12 (API 31)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            legacyInjector.install(param)
        }
    }

    fun installCamera1Hooks(param: XposedModuleInterface.PackageReadyParam) {
        // Camera1 tetap universal karena banyak aplikasi lama masih pakai di OS baru
        legacyInjector.install(param)
    }

    fun installUniversalCaptureHooks(param: XposedModuleInterface.PackageReadyParam) {
        // 1. Hunter Hook: BitmapFactory (Universal - Semua Versi Android)
        installBitmapHunter()

        // 2. Modern Hooks: Hanya untuk Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernInjector.install(param)
        }
    }

    fun installAndroid16UIHooks(param: XposedModuleInterface.PackageReadyParam) {
        // Nama method dipertahankan agar XCamModule tidak perlu dirubah drastis.
        // Redirect ke modern injector jika OS mendukung.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernInjector.install(param)
        }
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
        } catch (_: Throwable) {}
    }
}
