package com.hazbu.xcam

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
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

    private val xcamVersion = "v22.5-master"

    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0

    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false

    var previewSwapped = false
    private val previewSurfaceIds = mutableSetOf<Long>()

    @Volatile
    private var isCapturing = false
    
    @Volatile
    private var isPlayerBusy = false

    @Volatile
    private var cachedCaptureFrame: ByteArray? = null
    private var lastCapturePulseTime = 0L
    private var captureTimeMs = 0

    private val ignoreHooks = ThreadLocal.withInitial { false }

    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false
    fun setIgnoringHooks(ignore: Boolean) { ignoreHooks.set(ignore) }

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

    fun showToast(message: String) {
        uiHandler.post {
            try {
                mContext?.let {
                    android.widget.Toast.makeText(it, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                printLog("Failed to show toast: ${e.message}")
            }
        }
    }

    fun isCapturingState(): Boolean = isCapturing

    fun triggerCaptureState() {
        val now = System.currentTimeMillis()
        if (now - lastCapturePulseTime < 2000) return 
        lastCapturePulseTime = now
        
        captureTimeMs = try { c1MediaPlayer?.currentPosition ?: 0 } catch (_: Throwable) { 0 }
        printLog("Capture pulse detected! Target Frame Time: $captureTimeMs ms")
        
        isCapturing = true
        cachedCaptureFrame = null
        
        mContext?.let { refreshSettings(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            isCapturing = false
            // Beri toleransi cache selama 5 detik tambahan untuk app yang lambat menyimpan
            uiHandler.postDelayed({
                cachedCaptureFrame = null
                printLog("Capture Cache Cleared")
            }, 5000)
            printLog("Capture pulse ended")
        }, 3000)
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun getSurfaceId(surface: Surface?): Long {
        if (surface == null) return -1L
        return try {
            val field = Surface::class.java.getDeclaredField("mNativeObject")
            field.isAccessible = true
            field.getLong(surface)
        } catch (_: Throwable) {
            surface.hashCode().toLong()
        }
    }

    fun registerPreviewSurface(surface: Surface) {
        if (surface.isValid) {
            val id = getSurfaceId(surface)
            if (previewSurfaceIds.add(id)) {
                printLog("[Step 1] Surface Registered as PREVIEW | ID: $id")
            }
        }
    }

    fun isPreviewSurface(surface: Surface?): Boolean {
        if (surface == null) return false
        val id = getSurfaceId(surface)
        val exists = previewSurfaceIds.contains(id)
        if (!exists) {
            // Jika ID tidak ada, kita cek apakah ini SurfaceTexture. Jika ya, daftarkan otomatis.
            if (surface.toString().contains("SurfaceTexture")) {
                registerPreviewSurface(surface)
                return true
            }
            printLog("[Check] Surface ID $id is NOT in preview list | Current IDs: $previewSurfaceIds")
        }
        return exists
    }

    fun clearPreviewSurfaces() {
        // Kita tidak menghapus ID jika engine sedang aktif, agar tidak memutus preview yang sedang jalan
        if (c1MediaPlayer?.isPlaying == true) {
            printLog("[System] Keep IDs (Engine is playing)")
            return
        }
        printLog("[System] Clearing Surface IDs")
        previewSurfaceIds.clear()
        previewSwapped = false
    }


    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = getProcessNameStrict()
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
                    printLog("Context Initialized: ${mContext?.packageName}")
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
        uiHandler.removeCallbacksAndMessages("STOP_SIGNAL")
        printLog("[Step 4] Engine STOP triggered")
        try {
            isPlayerBusy = true
            c1MediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.reset()
                player.release()
            }
        } catch (e: Throwable) {
            printLog("Error stopping engine", e)
        }
        c1MediaPlayer = null
        isPlayerBusy = false
        c1Surface = null
        lastST = null
        lastModernSurface = null
    }

    private fun cancelPendingStop() {
        uiHandler.removeCallbacksAndMessages("STOP_SIGNAL")
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
        val surface = Surface(st)

        if (!isPreviewSurface(surface)) {
            printLog("UI Hook: Ignoring non-preview SurfaceTexture (${surface.hashCode()})")
            surface.release()
            return
        }
        
        if (st == lastST && (c1MediaPlayer?.isPlaying == true || isPlayerBusy)) return
        lastST = st

        try {
            stopCamera1Engine() 
            c1Surface = surface
            
            if (path.lowercase().endsWith(".mp4")) {
                isPlayerBusy = true
                c1MediaPlayer = MediaPlayer().apply {
                    setDataSource(context, path.toUri())
                    setSurface(c1Surface)
                    isLooping = true
                    prepareAsync()
                    setOnPreparedListener { 
                        isPlayerBusy = false
                        it.start() 
                    }
                    setOnErrorListener { _, _, _ -> 
                        isPlayerBusy = false
                        stopCamera1Engine(); true 
                    }
                }
            }
        } catch (e: Exception) {
            isPlayerBusy = false
            printLog("Legacy Error", e)
        }
    }

    fun handleModernPreview(surface: Surface) {
        if (surface == lastModernSurface && (c1MediaPlayer?.isPlaying == true || isPlayerBusy)) return
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
            val surface = holder.surface
            if (!surface.isValid) return@post
            
            if (!isPreviewSurface(surface)) {
                printLog("UI Hook: Ignoring non-preview SurfaceView (${surface.hashCode()})")
                return@post
            }

            val context = mContext ?: return@post
            val path = mediaPath ?: return@post
            injectToSurface(surface, context, path)
        }
    }

    private fun injectToSurface(surface: Surface, context: Context, path: String) {
        synchronized(this) {
            val surfaceId = getSurfaceId(surface)
            val currentEngineId = getSurfaceId(c1Surface)
            
            printLog("[Inject] Request for ID: $surfaceId | Current playing ID: $currentEngineId")
            
            if (!surface.isValid) {
                printLog("[Inject] ABORTED: Surface is INVALID")
                return
            }
            
            // Jika ID sama dan sedang jalan, jangan restart
            if (surfaceId == currentEngineId && c1MediaPlayer?.isPlaying == true) {
                printLog("[Inject] IGNORED: Already playing on this surface")
                return
            }

            try {
                printLog("[Inject] FORCING RESTART for new session...")
                stopCamera1Engine()
                
                isPlayerBusy = true
                c1Surface = surface
                val newPlayer = MediaPlayer()
                c1MediaPlayer = newPlayer
                cancelPendingStop() 
                
                newPlayer.apply {
                    setDataSource(context, path.toUri())
                    
                    try {
                        setSurface(surface)
                    } catch (e: Exception) {
                        printLog("[Inject] FATAL: setSurface failed: ${e.message}")
                        isPlayerBusy = false
                        return
                    }
                    
                    isLooping = true
                    setOnPreparedListener {
                        isPlayerBusy = false
                        try { 
                            it.start()
                            printLog("[Step 3] Engine ACTIVE: Playing on ID: $surfaceId") 
                        } catch (e: Throwable) {
                            printLog("Engine: Start failed", e)
                        }
                    }
                    setOnErrorListener { _, what, extra ->
                        isPlayerBusy = false
                        printLog("Engine: Error ($what, $extra)")
                        stopCamera1Engine(); true
                    }
                    try {
                        printLog("[Step 2] Engine: Preparing for ID: $surfaceId")
                        prepareAsync()
                    } catch (_: Exception) {
                        isPlayerBusy = false
                        printLog("Engine: prepareAsync fatal error")
                    }
                }
            } catch (e: Throwable) {
                isPlayerBusy = false
                printLog("Modern Injection Error", e)
            }
        }
    }

    fun getDummyST(): SurfaceTexture {
        if (dummyST == null) {
            dummyST = SurfaceTexture(999).apply {
                setOnFrameAvailableListener { try { updateTexImage() } catch (_: Exception) {} }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                try { dummyST?.detachFromGLContext() } catch (_: Throwable) {}
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
        // Jika kita sedang dalam proses internal xCam, abaikan hook agar tidak rekursi
        if (isIgnoringHooks()) return null

        val path = mediaPath ?: return null
        val context = mContext ?: return null

        synchronized(this) {
            // Gunakan cache jika sudah ada
            cachedCaptureFrame?.let { return it }

            val targetW = if (width > 0) width else 1280
            val targetH = if (height > 0) height else 1280

            printLog("Hunter: One-time extraction at $captureTimeMs ms ($targetW x $targetH)")
            
            return try {
                // SET FLAG IGNORE sebelum operasi yang memicu ContentResolver
                setIgnoringHooks(true)
                val result = XCamCapture.createJpeg(context, path, targetW, targetH, rotationAngle, isMirrored, captureTimeMs) { printLog(it) }
                cachedCaptureFrame = result
                result
            } catch (e: Throwable) {
                printLog("Hunter: Extraction failed", e)
                null
            } finally {
                // RESET FLAG IGNORE setelah selesai
                setIgnoringHooks(false)
            }
        }
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
