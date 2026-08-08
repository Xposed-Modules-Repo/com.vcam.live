package com.hazbu.xcam

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XCamModule : XposedModule() {

    private val xcamVersion = "v7.1-universal-discovery"

    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0
    private var isInitialized = false
    private var mContext: Context? = null
    
    private var xRenderer: XCamRenderer? = null
    private val injectors = XCamInjectors(this)

    fun printLog(msg: String, tr: Throwable? = null) {
        val fullMsg = "[$xcamVersion] $msg"
        log(PRIORITY_HIGHEST, "xCam", fullMsg)
        if (tr != null) Log.e("xCam", fullMsg, tr) else Log.e("xCam", fullMsg)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        printLog(">>> MODULE ACTIVE IN: ${param.packageName} (Process: ${getProcessName()}) <<<")

        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
        } else {
            hookContextInit()
            injectors.installLegacyHooks(param)
            injectors.installUniversalDiscoveryHooks(param)
        }
    }

    private fun getProcessName(): String {
        return try {
            val file = java.io.File("/proc/self/cmdline")
            file.readText().trim { it <= ' ' }
        } catch (e: Exception) { "unknown" }
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.hazbu.xcam.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (e: Exception) {
            printLog("Failed to hook manager app", e)
        }
    }

    @SuppressLint("PrivateApi")
    private fun hookContextInit() {
        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    mContext?.let { refreshSettings(it) }
                    isInitialized = true
                    printLog("Target Context initialized. Current Media: $mediaPath")
                }
                result
            }
        } catch (e: Exception) {
            printLog("Context hook failure", e)
        }
    }

    fun handlePreview(width: Int, height: Int): Boolean {
        val path = mediaPath ?: return false
        val context = mContext ?: return false
        return try {
            if (xRenderer == null || xRenderer?.currentPath != path) {
                xRenderer?.release()
                xRenderer = XCamRenderer(context, path, isMirrored, rotationAngle) { printLog(it) }
            }
            xRenderer?.draw(width, height) ?: false
        } catch (e: Throwable) { false }
    }

    fun handleCapture(width: Int, height: Int): ByteArray? {
        val path = mediaPath ?: return null
        val context = mContext ?: return null
        return XCamCapture.createJpeg(context, path, width, height, rotationAngle, isMirrored) { printLog(it) }
    }

    private fun refreshSettings(context: Context) {
        try {
            val uri = "content://$AUTHORITY".toUri()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mediaPath = cursor.getString(0)
                    isMirrored = cursor.getString(2) == "1"
                    rotationAngle = cursor.getString(3).toIntOrNull() ?: 0
                }
            }
        } catch (_: Exception) {}
    }
}
