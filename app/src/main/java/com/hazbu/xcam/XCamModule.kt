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

    private val xcamVersion = "v14.1-video-pro"

    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    
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
    
    private val targetSurfaces = mutableSetOf<Int>()

    fun printLog(msg: String, tr: Throwable? = null) {
        val fullMsg = "xCam: [$xcamVersion] $msg"
        log(PRIORITY_HIGHEST, "xCam", fullMsg)
        if (tr != null) Log.e("xCam", fullMsg, tr) else Log.e("xCam", fullMsg)
    }

    fun isCapturingState(): Boolean = isCapturing

    fun triggerCaptureState() {
        printLog("xCam STILL_CAPTURE Active (Window: 4s)")
        isCapturing = true
        refreshSettings()
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ isCapturing = false }, 4000)
    }

    fun markAsTargetSurface(s: Surface) {
        if (s.isValid) targetSurfaces.add(s.hashCode())
    }

    fun isTargetSurface(s: Surface): Boolean {
        return targetSurfaces.contains(s.hashCode())
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
        printLog(">>> STARTING VIDEO ENGINE v14.1 IN: $currentProcess <<<")
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
        } catch (e: Exception) {
            printLog("xCam Failed to hook manager app", e)
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
                    refreshSettings()
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            printLog("xCam Context hook failure", e)
        }
    }

    fun stopCamera1Engine() {
        try {
            c1MediaPlayer?.let {
                it.setSurface(null)
                if (it.isPlaying) it.stop()
                it.release()
            }
            c1MediaPlayer = null
            c1Surface = null
            lastST = null
            lastHolder = null
            lastModernSurface = null
        } catch (e: Exception) {}
    }

    fun handlePreview(width: Int, height: Int): Boolean {
        if (isCapturing) return false
        val path = mediaPath ?: return false
        if (!path.lowercase().endsWith(".mp4")) return false
        
        val context = mContext ?: return false
        return try {
            if (xRenderer == null || xRenderer?.currentPath != path) {
                xRenderer?.release()
                xRenderer = XCamRenderer(context, path, isMirrored, rotationAngle) { printLog(it) }
            }
            xRenderer?.draw(width, height) ?: false
        } catch (e: Throwable) { false }
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        if (isCapturing) return
        val path = mediaPath ?: return
        if (!path.lowercase().endsWith(".mp4")) return
        
        val h = st.hashCode()
        if (h == (lastST?.hashCode() ?: 0) && c1MediaPlayer?.isPlaying == true) return
        lastST = st
        uiHandler.post { injectToSurface(Surface(st), mContext ?: return@post, path) }
    }

    fun handleSurfaceViewPreview(holder: SurfaceHolder) {
        if (isCapturing) return
        val path = mediaPath ?: return
        if (!path.lowercase().endsWith(".mp4")) return
        
        val h = holder.hashCode()
        if (h == (lastHolder?.hashCode() ?: 0) && c1MediaPlayer?.isPlaying == true) return
        lastHolder = holder
        uiHandler.post {
            if (holder.surface.isValid) injectToSurface(holder.surface, mContext ?: return@post, path)
        }
    }

    fun handleModernPreview(surface: Surface) {
        if (isCapturing) return
        val path = mediaPath ?: return
        if (!path.lowercase().endsWith(".mp4")) return
        
        if (surface == lastModernSurface && c1MediaPlayer?.isPlaying == true) return
        lastModernSurface = surface
        uiHandler.post {
            if (surface.isValid) injectToSurface(surface, mContext ?: return@post, path)
        }
    }

    private fun injectToSurface(surface: Surface, context: Context, path: String) {
        if (!surface.isValid || isCapturing) return
        try {
            stopCamera1Engine()
            c1Surface = surface
            c1MediaPlayer = MediaPlayer().apply {
                setDataSource(context, path.toUri())
                if (surface.isValid) {
                    setSurface(surface)
                    isLooping = true
                    setOnPreparedListener { it.start(); printLog("xCam Video Engine Running") }
                    setOnErrorListener { _, what, extra ->
                        printLog("xCam Player Error: $what, $extra")
                        stopCamera1Engine()
                        true 
                    }
                    prepareAsync()
                }
            }
        } catch (e: Exception) {
            printLog("xCam Surface Injection Error", e)
        }
    }

    fun getDummyST(): SurfaceTexture {
        if (dummyST == null) {
            dummyST = SurfaceTexture(1337).apply { detachFromGLContext() }
        }
        return dummyST!!
    }

    fun handleCapture(width: Int, height: Int): ByteArray? {
        val path = mediaPath
        val context = mContext
        if (path.isNullOrEmpty() || context == null) return null
        if (!path.lowercase().endsWith(".mp4")) return null
        return XCamCapture.createJpeg(context, path, width, height, rotationAngle, isMirrored) { printLog(it) }
    }

    private fun refreshSettings() {
        try {
            val uri = "content://$AUTHORITY".toUri()
            mContext?.contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mediaPath = cursor.getString(0)
                    isMirrored = cursor.getString(2) == "1"
                    rotationAngle = cursor.getString(3).toIntOrNull() ?: 0
                    printLog("xCam Settings Synced")
                }
            }
        } catch (e: Exception) {}
    }
}
