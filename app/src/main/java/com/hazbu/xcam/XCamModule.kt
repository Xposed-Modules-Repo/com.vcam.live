package com.hazbu.xcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XCamModule : XposedModule() {

    private val xcamVersion = "xCam"

    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    
    private var xRenderer: XCamRenderer? = null
    private val injectors = XCamInjectors(this)

    // Camera1 Engine State
    private var c1MediaPlayer: MediaPlayer? = null
    private var c1Surface: Surface? = null
    private var lastST: SurfaceTexture? = null
    private var dummyST: SurfaceTexture? = null

    fun printLog(msg: String, tr: Throwable? = null) {
        val fullMsg = "[$xcamVersion] $msg"
        log(PRIORITY_HIGHEST, "xCam", fullMsg)
        if (tr != null) Log.e("xCam", fullMsg, tr) else Log.e("xCam", fullMsg)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        
        val processName = getProcessName()
        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
            return
        }

        if (param.packageName != processName) return
        if (hooksInstalled) return
        hooksInstalled = true

        printLog(">>> INJECTING UNIVERSAL ENGINE INTO: $processName <<<")
        hookContextInit()
        injectors.installLegacyHooks(param)
        injectors.installCamera1Hooks(param)
    }

    private fun getProcessName(): String {
        return try {
            java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' }
        } catch (e: Exception) { "" }
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
                }
                result
            }
        } catch (e: Exception) {
            printLog("Context hook failure", e)
        }
    }

    private fun stopCamera1Engine() {
        try {
            c1MediaPlayer?.stop()
            c1MediaPlayer?.release()
            c1MediaPlayer = null
            c1Surface?.release()
            c1Surface = null
            lastST = null
        } catch (_: Exception) {}
    }

    // --- INJECTOR HANDLERS ---

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

    fun handleCamera1Preview(st: SurfaceTexture) {
        val path = mediaPath ?: return
        val context = mContext ?: return
        
        if (st == lastST && c1MediaPlayer?.isPlaying == true) return
        lastST = st

        printLog("Active Session: Camera1 Redirect (ST:@${st.hashCode()})")
        
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
                    setOnErrorListener { _, _, _ -> false }
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
            printLog("Camera1 Engine Error", e)
        }
    }

    fun getDummyST(): SurfaceTexture {
        if (dummyST == null) dummyST = SurfaceTexture(10)
        return dummyST!!
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
                    printLog("Settings Sync: $mediaPath")
                }
            }
        } catch (_: Exception) {}
    }
}
