package com.hazbu.xcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.hazbu.xcam.core.*
import com.hazbu.xcam.utils.Logger
import com.hazbu.xcam.utils.SystemUtils
import com.hazbu.xcam.utils.UIUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private val ignoreHooks = ThreadLocal.withInitial { false }
    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false
    fun setIgnoringHooks(ignore: Boolean) { ignoreHooks.set(ignore) }
    private val injectors = XCamInjectors(this)

    // Components
    private val settingsManager = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val captureManager = CaptureManager(
        contextProvider = { mContext },
        refreshSettingsAction = { settingsManager.refreshSettings(it) },
        logAction = { printLog(it) }
    )
    private val engine = XCamEngine(
        contextProvider = { mContext },
        settingsProvider = { settingsManager },
        surfaceManager = surfaceManager,
        logAction = { printLog(it) }
    )

    val mediaPath: String? get() = settingsManager.mediaPath
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) { surfaceManager.previewSwapped = value }

    fun printLog(msg: String, tr: Throwable? = null) {
        Logger.printLog(this, msg, tr)
    }

    fun showToast(message: String) {
        UIUtils.showToast(mContext, message) { printLog(it) }
    }

    fun isCapturingState(): Boolean = captureManager.isCapturing

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition() }
    }

    fun registerPreviewSurface(surface: Surface) = surfaceManager.registerPreviewSurface(surface)
    fun registerImageReaderSurface(surface: Surface, format: Int, width: Int, height: Int) =
        surfaceManager.registerImageReaderSurface(surface, format, width, height)
    fun isPreviewSurface(surface: Surface?): Boolean = surfaceManager.isPreviewSurface(surface)
    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = SystemUtils.getProcessNameStrict()
        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
            return
        }
        
        if (!processName.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        clearPreviewSurfaces()
        printLog(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")
        hookContextInit()
        injectors.install(param)
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.hazbu.xcam.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (_: Throwable) {}
    }

    private fun hookContextInit() {
        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    printLog("Context Initialized: ${mContext?.packageName}")
                    mContext?.let { settingsManager.refreshSettings(it) }
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            printLog("Context hook failure", e)
        }
    }

    fun stopCamera1Engine() = engine.stopCamera1Engine()
    fun handlePreview(width: Int, height: Int): Boolean = engine.handlePreview(width, height)
    fun handleCamera1Preview(st: SurfaceTexture) = engine.handleCamera1Preview(st)
    fun handleModernPreview(surface: Surface) = engine.handleModernPreview(surface)
    fun handleSurfaceViewPreview(holder: SurfaceHolder) = engine.handleSurfaceViewPreview(holder)
    fun getDummySurface(): Surface = engine.getDummySurface()

    fun handleCapture(width: Int, height: Int): ByteArray? {
        return captureManager.handleCapture(
            path = settingsManager.mediaPath,
            width = width,
            height = height,
            rotationAngle = settingsManager.rotationAngle,
            isMirrored = settingsManager.isMirrored,
            isIgnoringHooks = { isIgnoringHooks() },
            setIgnoringHooks = { setIgnoringHooks(it) }
        )
    }

    fun handleStreamFrame(width: Int, height: Int): ByteArray? {
        return captureManager.handleStreamFrame(
            path = settingsManager.mediaPath,
            width = width,
            height = height,
            rotationAngle = settingsManager.rotationAngle,
            isMirrored = settingsManager.isMirrored,
            isIgnoringHooks = { isIgnoringHooks() },
            setIgnoringHooks = { setIgnoringHooks(it) }
        )
    }
}
