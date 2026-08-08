package com.hazbu.xcam

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XCamModule : XposedModule() {

    private val xcamVersion = "v16.4-stable"

    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0

    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false

    var previewSwapped = false
    
    // Registry untuk melacak Surface
    private val previewSurfaceHashes = mutableSetOf<Int>()
    private val captureSurfaceHashes = mutableSetOf<Int>()

    @Volatile
    private var isCapturing = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var xRenderer: XCamRenderer? = null
    private val injectors = XCamInjectors(this)

    private var c1MediaPlayer: MediaPlayer? = null
    private var c1Surface: Surface? = null

    private var lastST: SurfaceTexture? = null
    private var lastHolder: SurfaceHolder? = null
    private var lastModernSurface: Surface? = null

    private var dummyST: SurfaceTexture? = null
    private var dummySurface: Surface? = null


    fun printLog(msg: String, tr: Throwable? = null) {
        val fullMsg = "xCam: [$xcamVersion] $msg"
        log(PRIORITY_HIGHEST, "xCam", fullMsg)
        if (tr != null) Log.e("xCam", fullMsg, tr) else Log.e("xCam", fullMsg)
    }

    fun isCapturingState(): Boolean = isCapturing

    fun triggerCaptureState() {
        printLog("Capture pulse detected")
        isCapturing = true
        refreshSettings()
        uiHandler.removeCallbacksAndMessages(null)
        // Window 3 detik untuk membiarkan data asli mengalir ke Hunter
        uiHandler.postDelayed({
            isCapturing = false
            printLog("Capture pulse ended")
        }, 3000)
    }

    // =========================================================
    // SURFACE REGISTRY
    // =========================================================

    fun registerPreviewSurface(surface: Surface) {
        if (surface.isValid) {
            val hash = surface.hashCode()
            if (previewSurfaceHashes.add(hash)) {
                printLog("Surface registered as PREVIEW: $hash")
            }
        }
    }

    fun markAsCaptureSurface(surface: Surface) {
        if (surface.isValid) {
            val hash = surface.hashCode()
            if (captureSurfaceHashes.add(hash)) {
                printLog("Surface marked as CAPTURE (Safe Path): $hash")
            }
        }
    }

    fun isCaptureSurface(surface: Surface?): Boolean {
        if (surface == null) return false
        return captureSurfaceHashes.contains(surface.hashCode())
    }

    fun isPreviewSurface(surface: Surface?): Boolean {
        if (surface == null) return false
        val hash = surface.hashCode()
        if (previewSurfaceHashes.contains(hash)) return true
        
        // Dynamic discovery: Jika mengandung SurfaceTexture, kemungkinan besar preview
        if (surface.toString().contains("SurfaceTexture")) {
            registerPreviewSurface(surface)
            return true
        }
        return false
    }

    fun clearPreviewSurfaces() {
        previewSurfaceHashes.clear()
        captureSurfaceHashes.clear()
        previewSwapped = false
    }


    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val currentProcess = getProcessNameSafe()
        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
            return
        }
        if (!currentProcess.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        clearPreviewSurfaces()
        printLog("STARTING ENGINE v16.4: $currentProcess")
        hookContextInit()
        injectors.installLegacyHooks(param)
        injectors.installCamera1Hooks(param)
        injectors.installUniversalCaptureHooks(param)
        injectors.installAndroid16UIHooks(param)
    }

    private fun getProcessNameSafe(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
            else java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' }
        } catch (e: Exception) { "" }
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.hazbu.xcam.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (e: Throwable) {}
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
                    refreshSettings()
                    isInitialized = true
                }
                result
            }
        } catch (e: Throwable) {}
    }

    fun stopCamera1Engine() {
        try {
            c1MediaPlayer?.let { player ->
                try { player.setSurface(null) } catch (_: Throwable) {}
                try { if (player.isPlaying) player.stop() } catch (_: Throwable) {}
                try { player.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        c1MediaPlayer = null
        c1Surface = null
        lastST = null
        lastHolder = null
        lastModernSurface = null
    }

    fun handlePreview(width: Int, height: Int): Boolean {
        if (isCapturing) return false
        val path = mediaPath ?: return false
        if (!path.lowercase().endsWith(".mp4")) return false
        val context = mContext ?: return false
        return try {
            if (xRenderer == null || xRenderer?.currentPath != path) {
                printLog("PREVIEW: creating XCamRenderer")
                xRenderer?.release()
                xRenderer = XCamRenderer(context, path, isMirrored, rotationAngle) { printLog(it) }
            }
            xRenderer?.draw(width, height) ?: false
        } catch (e: Throwable) { false }
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        if (st == lastST && c1MediaPlayer?.isPlaying == true) return
        lastST = st
        uiHandler.post {
            val context = mContext ?: return@post
            val path = mediaPath ?: return@post
            injectToSurface(Surface(st), context, path)
        }
    }

    fun handleSurfaceViewPreview(holder: SurfaceHolder) {
        if (holder == lastHolder && c1MediaPlayer?.isPlaying == true) return
        lastHolder = holder
        uiHandler.post {
            if (!holder.surface.isValid) return@post
            val context = mContext ?: return@post
            val path = mediaPath ?: return@post
            injectToSurface(holder.surface, context, path)
        }
    }

    fun handleModernPreview(surface: Surface) {
        if (surface == lastModernSurface && c1MediaPlayer?.isPlaying == true) return
        lastModernSurface = surface
        uiHandler.post {
            if (!surface.isValid) return@post
            val context = mContext ?: return@post
            val path = mediaPath ?: return@post
            injectToSurface(surface, context, path)
        }
    }

    private fun injectToSurface(surface: Surface, context: Context, path: String) {
        if (!surface.isValid) return
        try {
            stopCamera1Engine()
            SystemClock.sleep(50)
            if (!surface.isValid) return
            c1Surface = surface
            c1MediaPlayer = MediaPlayer().apply {
                setDataSource(context, path.toUri())
                setSurface(surface)
                isLooping = true
                setOnPreparedListener {
                    try { it.start(); printLog("xCam Video Engine Running") } catch (e: Throwable) {}
                }
                setOnErrorListener { _, what, extra ->
                    printLog("xCam Player Error: $what, $extra")
                    stopCamera1Engine(); true
                }
                prepareAsync()
            }
        } catch (e: Throwable) {
            printLog("Surface Injection Error", e)
        }
    }

    fun getDummyST(): SurfaceTexture {
        if (dummyST == null) {
            dummyST = SurfaceTexture(999).apply {
                setOnFrameAvailableListener {
                    try { updateTexImage() } catch (_: Throwable) {}
                }
            }
            try { dummyST?.detachFromGLContext() } catch (_: Throwable) {}
        }
        return dummyST!!
    }

    fun getDummySurface(): Surface {
        if (dummySurface == null || !dummySurface!!.isValid) {
            dummySurface = Surface(getDummyST())
        }
        return dummySurface!!
    }

    fun handleCapture(width: Int, height: Int): ByteArray? {
        val path = mediaPath ?: return null
        val context = mContext ?: return null
        return try {
            XCamCapture.createJpeg(context, path, width, height, rotationAngle, isMirrored) { printLog(it) }
        } catch (e: Throwable) { null }
    }

    private fun refreshSettings() {
        try {
            val uri = "content://$AUTHORITY".toUri()
            mContext?.contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mediaPath = cursor.getString(0)
                    isMirrored = cursor.getString(2) == "1"
                    rotationAngle = cursor.getString(3).toIntOrNull() ?: 0
                    printLog("Settings synced: $mediaPath")
                }
            }
        } catch (e: Throwable) {}
    }
}
