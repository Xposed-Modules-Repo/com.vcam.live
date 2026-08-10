package com.hazbu.xcam

import android.graphics.BitmapFactory
import com.hazbu.xcam.hooks.*
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(private val module: XCamModule) {

    // Migrated Hooks from folder /hooks
    private val cameraHook = CameraHook(module)
    private val camera2Hook = Camera2Hook(module)
    private val cameraxHook = CameraxHook(module)
    private val intentHook = IntentHook(module)
    private val uiHook = UIHook(module)
    private val captureHook = CaptureHook(module)

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        // Specialized Hooks
        cameraHook.install(param)
        camera2Hook.install(param)
        cameraxHook.install(param)
        intentHook.install(param)
        uiHook.install(param)
        captureHook.install(param)
        
        module.printLog("All integrated hooks installed successfully", null)
    }
}
