package com.hazbu.xcam

import android.graphics.BitmapFactory
import android.os.Build
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(private val module: XCamModule) {

    private val legacyInjector = XCamInjectorsLegacy(module)
    private val modernInjector = XCamInjectorsModern(module)

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        installBitmapHunter()
        if (Build.VERSION.SDK_INT < 31) {
            legacyInjector.install(param)
        } else {
            modernInjector.install(param)
            legacyInjector.installMethod2(param)
        }
    }

    private fun installBitmapHunter() {
        try {
            val bfClass = BitmapFactory::class.java
            val methods = bfClass.declaredMethods.filter { it.name == "decodeByteArray" }
            
            methods.forEach { method ->
                module.hook(method).intercept { chain ->
                    if (module.isCapturingState()) {
                        module.printLog("Hunter: Success! Intercepted decodeByteArray")
                        val replacement = module.handleCapture(1280, 1280)
                        if (replacement != null) {
                            val newArgs = Array(chain.args.size) { i -> 
                                when (i) {
                                    0 -> replacement 
                                    2 -> if (chain.args.size >= 3) replacement.size else chain.args[i]
                                    else -> chain.args[i]
                                }
                            }
                            return@intercept chain.proceed(newArgs)
                        }
                    }
                    chain.proceed()
                }
            }
            module.printLog("Universal Hunter Hook Installed")
        } catch (e: Throwable) {
            module.printLog("Hunter Hook installation failed", e)
        }
    }
}
