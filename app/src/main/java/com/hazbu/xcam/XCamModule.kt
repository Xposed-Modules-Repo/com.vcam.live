package com.hazbu.xcam

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.view.Surface
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class XCamModule : XposedModule() {

    private var mediaPath: String? = null
    private var isMirrored = false
    private var rotationAngle = 0
    private var isInitialized = false
    private var mContext: Context? = null
    private var glThread: GLInjectionThread? = null

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        android.util.Log.i("xCam", ">>> MODULE ACTIVE IN: ${param.packageName} <<<")

        // Jangan lapor jika ini adalah aplikasi manager itu sendiri
        if (param.packageName != "com.hazbu.xcam") {
            registerActivity(param.packageName)
        }

        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
        } else {
            hookCameraFeeds(param)
        }
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.hazbu.xcam.MainActivity")
            val method = clazz.getDeclaredMethod("isModuleActive")
            hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any = true
            })
        } catch (e: Exception) {
            android.util.Log.e("xCam", "Failed to hook manager app: ${e.message}")
        }
    }

    private fun hookCameraFeeds(param: XposedModuleInterface.PackageReadyParam) {
        try {
            // Hook Context sesegera mungkin agar registerActivity bisa dipanggil
            val attachMethod = Class.forName("android.content.ContextWrapper").getDeclaredMethod("attachBaseContext", Context::class.java)
            hook(attachMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (!isInitialized) {
                        mContext = chain.thisObject as? Context
                        mContext?.let { 
                            refreshSettings(it)
                            registerActivity(it.packageName) // Lapor diri saat konteks siap
                        }
                        isInitialized = true
                    }
                    return result
                }
            })

            val cameraDeviceImpl = param.classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")
            val method1 = cameraDeviceImpl.getDeclaredMethod("createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java)
            val method2 = cameraDeviceImpl.getDeclaredMethod("createCaptureSession", SessionConfiguration::class.java)
            
            val camera2Hooker = object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!mediaPath.isNullOrEmpty()) {
                        val surfaces = mutableListOf<Surface>()
                        val arg0 = chain.args[0]
                        if (arg0 is SessionConfiguration) {
                            arg0.outputConfigurations.forEach { it.surface?.let { s -> surfaces.add(s) } }
                        } else if (arg0 is List<*>) {
                            arg0.forEach { item ->
                                when (item) {
                                    is Surface -> surfaces.add(item)
                                    is OutputConfiguration -> item.surface?.let { surfaces.add(it) }
                                }
                            }
                        }
                        if (surfaces.isNotEmpty()) startInjection(surfaces)
                    }
                    return chain.proceed()
                }
            }

            hook(method1).intercept(camera2Hooker)
            hook(method2).intercept(camera2Hooker)
        } catch (e: Exception) {
            android.util.Log.e("xCam", "Camera hooks failed: ${e.message}")
        }
    }

    private fun startInjection(surfaces: List<Surface>) {
        val path = mediaPath ?: return
        val context = mContext ?: return
        val target = surfaces.firstOrNull { it.isValid } ?: return
        stopCurrentInjection()
        glThread = GLInjectionThread(context, path, target, isMirrored, rotationAngle, this).apply { start() }
    }

    private fun stopCurrentInjection() {
        glThread?.stopInjection()
        glThread = null
    }

    private fun registerActivity(packageName: String) {
        try {
            val uri = "content://$AUTHORITY".toUri()
            // Mengirim perintah register_scope ke SettingsProvider
            mContext?.contentResolver?.call(uri, "register_scope", packageName, null)
        } catch (_: Exception) {}
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

    class GLInjectionThread(
        private val context: Context,
        private val path: String,
        private val targetSurface: Surface,
        private val isMirrored: Boolean,
        private val rotationAngle: Int,
        private val module: XposedInterface
    ) : Thread("xCam-GL") {
        private val running = AtomicBoolean(true)
        private var eglDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var textureId = -1
        private var surfaceTexture: SurfaceTexture? = null
        private var mediaPlayer: MediaPlayer? = null
        private val mvpMatrix = FloatArray(16)
        private val stMatrix = FloatArray(16)
        private var viewportW = 0f
        private var viewportH = 0f
        private var videoW = 0
        private var videoH = 0
        private var aPositionLoc = -1
        private var aTextureCoordLoc = -1
        private var uMVPMatrixLoc = -1
        private var uSTMatrixLoc = -1
        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texBuffer: FloatBuffer
        private val frameAvailable = AtomicBoolean(false)

        fun stopInjection() {
            running.set(false)
            interrupt()
        }

        override fun run() {
            try {
                initEGL()
                initBuffers()
                if (path.lowercase().endsWith(".mp4")) {
                    setupVideo()
                    videoLoop()
                } else {
                    setupImage()
                    imageLoop()
                }
            } catch (e: Exception) {
                module.log(6, "xCam", "GL Error: ${e.message}")
            } finally {
                releaseResources()
            }
        }

        private fun initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(eglDisplay, null, 0, null, 0)
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], targetSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            val dims = IntArray(2)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, dims, 0)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, dims, 1)
            viewportW = dims[0].toFloat()
            viewportH = dims[1].toFloat()
            GLES20.glViewport(0, 0, dims[0], dims[1])
        }

        private fun initBuffers() {
            vertexBuffer = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)).position(0)
            }
            texBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
            }
            Matrix.setIdentityM(stMatrix, 0)
            Matrix.setIdentityM(mvpMatrix, 0)
        }

        private fun initShaders(isOES: Boolean) {
            val vs = "attribute vec4 aPosition; attribute vec2 aTextureCoord; varying vec2 vTextureCoord; uniform mat4 uSTMatrix; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * aPosition; vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy; }"
            val fs = if (isOES) "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTextureCoord; uniform samplerExternalOES sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }"
            else "precision mediump float; varying vec2 vTextureCoord; uniform sampler2D sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }"
            program = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, loadShader(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(this, loadShader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(this)
            }
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        }

        private fun loadShader(type: Int, code: String) = GLES20.glCreateShader(type).apply {
            GLES20.glShaderSource(this, code)
            GLES20.glCompileShader(this)
        }

        private fun setupVideo() {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            surfaceTexture = SurfaceTexture(textureId).apply { setOnFrameAvailableListener { frameAvailable.set(true) } }
            initShaders(true)
            mediaPlayer = MediaPlayer().apply {
                val uri = Uri.parse(path)
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setSurface(Surface(surfaceTexture))
                isLooping = true
                setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h }
                setOnPreparedListener { videoW = it.videoWidth; videoH = it.videoHeight; it.start() }
                prepareAsync()
            }
        }

        private fun setupImage() {
            val bitmap = context.contentResolver.openInputStream(Uri.parse(path))?.use { BitmapFactory.decodeStream(it) } ?: return
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            initShaders(false)
            updateMVPMatrix(bitmap.width, bitmap.height)
            bitmap.recycle()
        }

        private fun updateMVPMatrix(srcW: Int, srcH: Int) {
            if (srcW <= 0 || srcH <= 0 || viewportW <= 0 || viewportH <= 0) return
            val effRatio = if (rotationAngle % 180 == 0) srcW.toFloat() / srcH else srcH.toFloat() / srcW
            val dstRatio = viewportW / viewportH
            Matrix.setIdentityM(mvpMatrix, 0)
            if (effRatio > dstRatio) Matrix.scaleM(mvpMatrix, 0, effRatio / dstRatio, 1f, 1f)
            else Matrix.scaleM(mvpMatrix, 0, 1f, dstRatio / effRatio, 1f)
            Matrix.rotateM(mvpMatrix, 0, -rotationAngle.toFloat(), 0f, 0f, 1f)
            if (isMirrored) Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        }

        private fun videoLoop() {
            var lastTransformApplied = false
            while (running.get() && !isInterrupted) {
                if (!targetSurface.isValid) break
                if (frameAvailable.compareAndSet(true, false)) {
                    if (!lastTransformApplied && videoW > 0 && videoH > 0) {
                        updateMVPMatrix(videoW, videoH)
                        lastTransformApplied = true
                    }
                    try {
                        surfaceTexture?.updateTexImage()
                        surfaceTexture?.getTransformMatrix(stMatrix)
                    } catch (_: Exception) { continue }
                    drawFrame(true)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                } else { sleep(10) }
            }
        }

        private fun imageLoop() {
            while (running.get() && !isInterrupted) {
                drawFrame(false)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                sleep(33)
            }
        }

        private fun drawFrame(isOES: Boolean) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (isOES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
            GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texBuffer)
            GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        }

        private fun releaseResources() {
            try {
                mediaPlayer?.release()
                surfaceTexture?.release()
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureId != -1) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (_: Exception) {}
        }
    }
}
