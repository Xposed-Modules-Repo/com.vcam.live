package com.hazbu.xcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.opengl.*
import android.util.Log
import android.view.Surface
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class XCamModule : XposedModule() {

    private val xcamVersion = "v5.9.2-final-body-fix"

    private var mediaPath: String? = null
    private var isMirrored = false
    private var rotationAngle = 0
    private var isInitialized = false
    private var mContext: Context? = null
    
    private var xRenderer: XCamRenderer? = null

    private fun printLog(msg: String, tr: Throwable? = null) {
        val fullMsg = "[$xcamVersion] $msg"
        log(PRIORITY_HIGHEST, "xCam", fullMsg)
        if (tr != null) {
            Log.e("xCam", fullMsg, tr)
        } else {
            Log.e("xCam", fullMsg)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        printLog(">>> MODULE ACTIVE IN: ${param.packageName} <<<")

        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
        } else {
            hookCameraFeeds(param)
        }
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
    private fun hookCameraFeeds(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    mContext?.let { refreshSettings(it) }
                    isInitialized = true
                    printLog("Target context initialized. Media: $mediaPath")
                }
                result
            }

            hookLegacyRenderer(param)
            hookCapturePath(param)

        } catch (e: Exception) {
            printLog("hookCameraFeeds critical failure", e)
        }
    }

    private fun hookLegacyRenderer(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val strClass = param.classLoader.loadClass("android.hardware.camera2.legacy.SurfaceTextureRenderer")
            val drawFrame = strClass.getDeclaredMethod(
                "drawFrame",
                SurfaceTexture::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            hook(drawFrame).intercept { chain ->
                val path = mediaPath
                val context = mContext
                
                if (path.isNullOrEmpty() || context == null) {
                    return@intercept chain.proceed()
                }

                try {
                    val width = (chain.args[1] as? Number)?.toInt() ?: 0
                    val height = (chain.args[2] as? Number)?.toInt() ?: 0

                    if (xRenderer == null || xRenderer?.currentPath != path) {
                        printLog("Renderer update for: $path")
                        xRenderer?.release()
                        xRenderer = XCamRenderer(context, path, isMirrored, rotationAngle, this)
                    }

                    val success = xRenderer?.draw(width, height) ?: false
                    if (success) {
                        null // Bypass original
                    } else {
                        chain.proceed()
                    }
                } catch (e: Throwable) {
                    chain.proceed()
                }
            }
            printLog("Legacy renderer hook: OK")
        } catch (e: Throwable) {
            printLog("Legacy renderer hook failed", e)
        }
    }

    private fun hookCapturePath(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val legacyDeviceClass = param.classLoader.loadClass("android.hardware.camera2.legacy.LegacyCameraDevice")
            val produceFrame = legacyDeviceClass.getDeclaredMethods().find { 
                it.name == "produceFrame" && it.parameterTypes.size == 5 && it.parameterTypes[1] == ByteArray::class.java 
            }

            if (produceFrame != null) {
                hook(produceFrame).intercept { chain ->
                    try {
                        val path = mediaPath
                        val context = mContext
                        val width = (chain.args[2] as? Number)?.toInt() ?: 0
                        val height = (chain.args[3] as? Number)?.toInt() ?: 0
                        val format = (chain.args[4] as? Number)?.toInt() ?: 0

                        if (format == 0x21 && !path.isNullOrEmpty() && context != null) {
                            printLog("CAPTURE TRIGGERED: ${width}x${height}")
                            val replacement = createCaptureJpeg(context, path, width, height)
                            if (replacement != null) {
                                // FIX: Buat Array murni secara manual untuk proceed
                                val newArgs = Array(chain.args.size) { i ->
                                    if (i == 1) replacement else chain.args[i]
                                }
                                printLog("CAPTURE SUCCESS: ${replacement.size} bytes injected")
                                return@intercept chain.proceed(newArgs)
                            } else {
                                printLog("CAPTURE FAILED: replacement is null")
                            }
                        }
                    } catch (e: Throwable) {
                        printLog("produceFrame intercept error", e)
                    }
                    chain.proceed()
                }
                printLog("Capture path hook: OK")
            }
        } catch (e: Exception) {
            printLog("Capture path hook failed", e)
        }
    }

    private fun createCaptureJpeg(context: Context, path: String, width: Int, height: Int): ByteArray? {
        return try {
            val bitmap: Bitmap? = if (path.lowercase().endsWith(".mp4")) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, path.toUri())
                val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame
            } else {
                context.contentResolver.openInputStream(path.toUri())?.use {
                    BitmapFactory.decodeStream(it)
                }
            }

            if (bitmap == null) {
                printLog("createCaptureJpeg: Bitmap decoding failed")
                return null
            }

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val out = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            
            if (bitmap != scaledBitmap) bitmap.recycle()
            scaledBitmap.recycle()
            
            out.toByteArray()
        } catch (e: Exception) {
            printLog("createCaptureJpeg execution error", e)
            null
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
        } catch (e: Exception) {
            printLog("refreshSettings failed", e)
        }
    }

    class XCamRenderer(
        private val context: Context,
        val currentPath: String,
        private val isMirrored: Boolean,
        private val rotationAngle: Int,
        private val module: XCamModule
    ) {
        private var program = 0
        private var textureId = -1
        private var isOES = false
        private var surfaceTexture: SurfaceTexture? = null
        private var mediaPlayer: MediaPlayer? = null
        private val frameAvailable = AtomicBoolean(false)
        private val mvpMatrix = FloatArray(16)
        private val stMatrix = FloatArray(16)
        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texBuffer: FloatBuffer
        private var mediaW = 0
        private var mediaH = 0
        private var initialized = false
        private var setupAttempted = false

        init {
            Matrix.setIdentityM(stMatrix, 0)
            Matrix.setIdentityM(mvpMatrix, 0)
            initBuffers()
        }

        private fun initBuffers() {
            vertexBuffer = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)).position(0)
            }
            texBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
            }
        }

        private fun setup() {
            if (setupAttempted) return
            setupAttempted = true
            try {
                if (currentPath.lowercase().endsWith(".mp4")) {
                    isOES = true
                    val tex = IntArray(1)
                    GLES20.glGenTextures(1, tex, 0)
                    textureId = tex[0]
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                    
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                    surfaceTexture = SurfaceTexture(textureId).apply {
                        setOnFrameAvailableListener { frameAvailable.set(true) }
                    }
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(context, currentPath.toUri())
                        setSurface(Surface(surfaceTexture))
                        isLooping = true
                        setOnPreparedListener { 
                            mediaW = it.videoWidth
                            mediaH = it.videoHeight
                            it.start() 
                        }
                        prepareAsync()
                    }
                } else {
                    isOES = false
                    val bitmap = context.contentResolver.openInputStream(currentPath.toUri())?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    if (bitmap != null) {
                        mediaW = bitmap.width
                        mediaH = bitmap.height
                        val tex = IntArray(1)
                        GLES20.glGenTextures(1, tex, 0)
                        textureId = tex[0]
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                        
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                        
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                        bitmap.recycle()
                        module.printLog("Image texture loaded: ${mediaW}x${mediaH}")
                    }
                }
                initShaders()
                initialized = true
                module.printLog("Renderer setup complete")
            } catch (e: Exception) {
                module.printLog("Renderer setup failed", e)
            }
        }

        private fun initShaders() {
            val vs = "attribute vec4 aPosition; attribute vec2 aTextureCoord; varying vec2 vTextureCoord; uniform mat4 uSTMatrix; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * aPosition; vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy; }"
            val fs = if (isOES) "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTextureCoord; uniform samplerExternalOES sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }"
            else "precision mediump float; varying vec2 vTextureCoord; uniform sampler2D sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }"
            program = GLES20.glCreateProgram().apply {
                val vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).apply { GLES20.glShaderSource(this, vs); GLES20.glCompileShader(this) }
                val fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).apply { GLES20.glShaderSource(this, fs); GLES20.glCompileShader(this) }
                GLES20.glAttachShader(this, vShader); GLES20.glAttachShader(this, fShader); GLES20.glLinkProgram(this)
            }
        }

        fun draw(viewportW: Int, viewportH: Int): Boolean {
            if (!initialized) setup()
            if (!initialized) return false
            
            try {
                if (isOES && frameAvailable.compareAndSet(true, false)) { 
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                }
                
                updateMVPMatrix(viewportW.toFloat(), viewportH.toFloat())
                GLES20.glViewport(0, 0, viewportW, viewportH)
                GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_CULL_FACE); GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glUseProgram(program)
                
                val aPos = GLES20.glGetAttribLocation(program, "aPosition")
                val aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
                val uMVP = GLES20.glGetUniformLocation(program, "uMVPMatrix")
                val uST = GLES20.glGetUniformLocation(program, "uSTMatrix")
                val uSampler = GLES20.glGetUniformLocation(program, "sTexture")

                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, texBuffer)
                
                GLES20.glUniformMatrix4fv(uMVP, 1, false, mvpMatrix, 0)
                GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
                
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(if (isOES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glUniform1i(uSampler, 0)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                
                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aTex)
                return true
            } catch (e: Exception) {
                return false
            }
        }

        private fun updateMVPMatrix(viewW: Float, viewH: Float) {
            if (mediaW <= 0 || mediaH <= 0) return
            val effRatio = if (rotationAngle % 180 == 0) mediaW.toFloat() / mediaH else mediaH.toFloat() / mediaW
            val dstRatio = viewW / viewH
            Matrix.setIdentityM(mvpMatrix, 0)
            if (effRatio > dstRatio) Matrix.scaleM(mvpMatrix, 0, effRatio / dstRatio, 1f, 1f) else Matrix.scaleM(mvpMatrix, 0, 1f, dstRatio / effRatio, 1f)
            Matrix.rotateM(mvpMatrix, 0, -rotationAngle.toFloat(), 0f, 0f, 1f)
            if (isMirrored) Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        }

        fun release() { try { mediaPlayer?.release(); surfaceTexture?.release(); GLES20.glDeleteProgram(program); GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) } catch (_: Exception) {} }
    }
}
