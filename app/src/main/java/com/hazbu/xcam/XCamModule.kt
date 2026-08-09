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
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XCamModule : XposedModule() {

    private val xcamVersion = "v18.1-clean"

    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0

    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false

    var previewSwapped = false
    private val previewSurfaceHashes = mutableSetOf<Int>()

    @Volatile
    private var isCapturing = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var xRenderer: XCamRenderer? = null
    private val injectors = XCamInjectors(this)

    private var c1MediaPlayer: MediaPlayer? = null
    private var c1Surface: Surface? = null
    private var lastST: SurfaceTexture? = null
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
        mContext?.let { refreshSettings(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            isCapturing = false
            printLog("Capture pulse ended")
        }, 3000)
    }

    fun registerPreviewSurface(surface: Surface) {
        if (surface.isValid) {
            val hash = surface.hashCode()
            if (previewSurfaceHashes.add(hash)) {
                printLog("Surface registered as PREVIEW: $hash")
            }
        }
    }

    fun isPreviewSurface(surface: Surface?): Boolean {
        if (surface == null) return false
        val hash = surface.hashCode()
        if (previewSurfaceHashes.contains(hash)) return true
        if (surface.toString().contains("SurfaceTexture")) {
            registerPreviewSurface(surface)
            return true
        }
        return false
    }

    fun clearPreviewSurfaces() {
        previewSurfaceHashes.clear()
        previewSwapped = false
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = getProcessNameStrict()
        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
            return
        }
        if (param.packageName != processName) return
        if (hooksInstalled) return
        hooksInstalled = true
        clearPreviewSurfaces()
        printLog(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")
        hookContextInit()
        injectors.install(param)
    }

    private fun getProcessNameStrict(): String {
        return if (Build.VERSION.SDK_INT < 31) {
            try {
                java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' }
            } catch (_: Exception) {
                Application.getProcessName()
            }
        } else {
            Application.getProcessName()
        }
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.hazbu.xcam.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (_: Throwable) {}
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
                }
                result
            }
        } catch (e: Exception) {
            printLog("Context hook failure", e)
        }
    }

    fun stopCamera1Engine() {
        try {
            c1MediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.reset()
                player.release()
            }
        } catch (_: Throwable) {}
        c1MediaPlayer = null
        try { c1Surface?.release() } catch (_: Throwable) {}
        c1Surface = null
        lastST = null
        lastModernSurface = null
    }

    fun handlePreview(width: Int, height: Int): Boolean {
        val path = mediaPath ?: return false
        if (!path.lowercase().endsWith(".mp4")) return false
        val context = mContext ?: return false
        return try {
            if (xRenderer == null || xRenderer?.currentPath != path) {
                xRenderer?.release()
                xRenderer = XCamRenderer(context, path, isMirrored, rotationAngle) { printLog(it) }
            }
            xRenderer?.draw(width, height) ?: false
        } catch (_: Throwable) { false }
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        val path = mediaPath ?: return
        val context = mContext ?: return
        if (st == lastST && c1MediaPlayer?.isPlaying == true) return
        lastST = st
        try {
            stopCamera1Engine() 
            c1Surface = Surface(st)
            if (path.lowercase().endsWith(".mp4")) {
                c1MediaPlayer = MediaPlayer().apply {
                    setDataSource(context, path.toUri())
                    setSurface(c1Surface)
                    isLooping = true
                    prepareAsync()
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ -> stopCamera1Engine(); true }
                }
            } else {
                val bitmap = context.contentResolver.openInputStream(path.toUri())?.use { BitmapFactory.decodeStream(it) }
                bitmap?.let {
                    val canvas = c1Surface?.lockCanvas(null)
                    canvas?.drawBitmap(it, null, android.graphics.Rect(0, 0, canvas.width, canvas.height), null)
                    c1Surface?.unlockCanvasAndPost(canvas)
                    it.recycle()
                }
            }
        } catch (e: Exception) {
            printLog("Method 2 fatal error", e)
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

    fun handleSurfaceViewPreview(holder: SurfaceHolder) {
        uiHandler.post {
            if (!holder.surface.isValid) return@post
            val context = mContext ?: return@post
            val path = mediaPath ?: return@post
            injectToSurface(holder.surface, context, path)
        }
    }

    private fun injectToSurface(surface: Surface, context: Context, path: String) {
        if (!surface.isValid) return
        try {
            stopCamera1Engine()
            android.os.SystemClock.sleep(50)
            if (!surface.isValid) return
            c1Surface = surface
            c1MediaPlayer = MediaPlayer().apply {
                setDataSource(context, path.toUri())
                setSurface(surface)
                isLooping = true
                setOnPreparedListener {
                    try { it.start(); printLog("Modern Engine Running") } catch (_: Throwable) {}
                }
                setOnErrorListener { _, what, extra ->
                    printLog("Modern Player Error: $what, $extra")
                    stopCamera1Engine(); true
                }
                prepareAsync()
            }
        } catch (e: Throwable) {
            printLog("Modern Surface Injection Error", e)
        }
    }

    fun getDummyST(): SurfaceTexture {
        if (dummyST == null) {
            dummyST = SurfaceTexture(999).apply {
                setOnFrameAvailableListener { try { updateTexImage() } catch (_: Exception) {} }
            }
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
        } catch (_: Throwable) { null }
    }

    private fun refreshSettings(context: Context) {
        try {
            val uri = "content://$AUTHORITY".toUri()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mediaPath = cursor.getString(0)
                    isMirrored = cursor.getString(2) == "1"
                    rotationAngle = cursor.getString(3).toIntOrNull() ?: 0
                    printLog("Settings Sync: $mediaPath")
                }
            }
        } catch (_: Exception) {}
    }
}
