package com.hazbu.xcam

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.view.Surface
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class XCamModule : IXposedHookLoadPackage {
    private var mediaPath: String? = null
    private var isMirrored = false
    private var rotationAngle = 0
    private var isInitialized = false
    private var mContext: Context? = null
    private var glThread: GLInjectionThread? = null

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == "com.hazbu.xcam") return

        XposedHelpers.findAndHookMethod(
            "android.content.ContextWrapper",
            lpparam.classLoader,
            "attachBaseContext",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isInitialized) {
                        mContext = param.thisObject as Context
                        refreshSettings(mContext!!)
                        isInitialized = true
                    }
                }
            }
        )

        hookCamera1(lpparam)
        hookCamera2(lpparam)
    }

    private fun refreshSettings(context: Context) {
        try {
            val uri = "content://$AUTHORITY".toUri()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mediaPath = cursor.getString(0)
                    isMirrored = cursor.getString(2) == "1"
                    rotationAngle = cursor.getString(3).toIntOrNull() ?: 0
                    XposedBridge.log("xCam: Settings refreshed: $mediaPath, mirrored=$isMirrored, rotation=$rotationAngle")
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("xCam: Settings access failed: ${e.message}")
        }
    }

    private fun hookCamera1(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera",
                lpparam.classLoader,
                "open",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("xCam: Camera1.open() detected")
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookCamera2(lpparam: LoadPackageParam) {
        XposedBridge.log("xCam: Initializing Camera2 hooks for ${lpparam.packageName}")

        try {
            val cameraDeviceImpl = "android.hardware.camera2.impl.CameraDeviceImpl"

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (mediaPath.isNullOrEmpty()) return

                    val surfaces = mutableListOf<Surface>()
                    val arg0 = param.args[0]

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

                    if (surfaces.isNotEmpty()) {
                        mContext?.let { refreshSettings(it) }
                        startInjection(surfaces)
                    }
                }
            }

            XposedHelpers.findAndHookMethod(cameraDeviceImpl, lpparam.classLoader, "createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, hook)
            XposedHelpers.findAndHookMethod(cameraDeviceImpl, lpparam.classLoader, "createCaptureSession", SessionConfiguration::class.java, hook)

            try {
                XposedHelpers.findAndHookMethod(cameraDeviceImpl, lpparam.classLoader, "createCaptureSessionByOutputConfigurations", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, hook)
            } catch (_: Throwable) {}

        } catch (e: Throwable) {
            XposedBridge.log("xCam: Camera2 hooks failed: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.media.ImageReader",
                lpparam.classLoader,
                "acquireNextImage",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val image = param.result as? Image ?: return
                        if (System.currentTimeMillis() % 5000 < 33) {
                            XposedBridge.log("xCam: ImageReader processing ${image.width}x${image.height}")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun startInjection(surfaces: List<Surface>) {
        val path = mediaPath ?: return
        val context = mContext ?: return
        val target = surfaces.firstOrNull { it.isValid } ?: return

        stopCurrentInjection()

        XposedBridge.log("xCam: startGLInjection with path: $path")
        glThread = GLInjectionThread(context, path, target, isMirrored, rotationAngle).apply {
            start()
        }
    }

    private fun stopCurrentInjection() {
        try {
            glThread?.stopInjection()
            glThread = null
        } catch (_: Exception) {}
    }

    class GLInjectionThread(
        private val context: Context,
        private val path: String,
        private val targetSurface: Surface,
        private val isMirrored: Boolean,
        private val rotationAngle: Int
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
                XposedBridge.log("xCam: GL Error: ${e.message}")
            } finally {
                releaseResources()
            }
        }

        private fun checkEglError(msg: String) {
            val error = EGL14.eglGetError()
            if (error != EGL14.EGL_SUCCESS) {
                throw RuntimeException("$msg: EGL error: 0x${Integer.toHexString(error)}")
            }
        }

        private fun initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("unable to get EGL14 display")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                eglDisplay = EGL14.EGL_NO_DISPLAY
                throw RuntimeException("unable to initialize EGL14")
            }
            XposedBridge.log("xCam: EGL initialized v${version[0]}.${version[1]}")

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
            }

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            checkEglError("eglCreateContext")
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("null context")

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], targetSurface, surfaceAttribs, 0)
            checkEglError("eglCreateWindowSurface")
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("null surface")

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
            XposedBridge.log("xCam: EGL context and surface bound")

            val dims = IntArray(2)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, dims, 0)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, dims, 1)
            viewportW = dims[0].toFloat()
            viewportH = dims[1].toFloat()
            XposedBridge.log("xCam: Viewport size: ${viewportW}x${viewportH}")
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
            XposedBridge.log("xCam: Initializing shaders (OES=$isOES)")
            val vs = "attribute vec4 aPosition; attribute vec2 aTextureCoord; varying vec2 vTextureCoord; uniform mat4 uSTMatrix; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * aPosition; vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy; }"
            val fs = if (isOES) "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTextureCoord; uniform samplerExternalOES sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }"
            else "precision mediump float; varying vec2 vTextureCoord; uniform sampler2D sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }"
            program = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, loadShader(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(this, loadShader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(this)

                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(this, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    val info = GLES20.glGetProgramInfoLog(this)
                    XposedBridge.log("xCam: Could not link program: $info")
                    GLES20.glDeleteProgram(this)
                    program = 0
                    throw RuntimeException("Could not link program")
                }
            }
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        }

        private fun loadShader(type: Int, code: String) = GLES20.glCreateShader(type).apply {
            GLES20.glShaderSource(this, code)
            GLES20.glCompileShader(this)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(this, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(this)
                XposedBridge.log("xCam: Could not compile shader $type: $info")
                GLES20.glDeleteShader(this)
                throw RuntimeException("Could not compile shader $type")
            }
        }

        private fun setupVideo() {
            XposedBridge.log("xCam: Setting up video injection")
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
                setOnVideoSizeChangedListener { _, w, h ->
                    XposedBridge.log("xCam: Video size changed: ${w}x${h}")
                    videoW = w
                    videoH = h
                }
                setOnPreparedListener {
                    XposedBridge.log("xCam: MediaPlayer prepared, starting")
                    videoW = it.videoWidth
                    videoH = it.videoHeight
                    it.start()
                }
                setOnErrorListener { _, what, extra ->
                    XposedBridge.log("xCam: MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                setOnInfoListener { _, what, extra ->
                    XposedBridge.log("xCam: MediaPlayer info: what=$what, extra=$extra")
                    true
                }
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

            // 3. Fill scaling (Center Crop) - Applied last to vertices
            try {
                if (effRatio > dstRatio) {
                    Matrix.scaleM(mvpMatrix, 0, effRatio / dstRatio, 1f, 1f)
                } else {
                    Matrix.scaleM(mvpMatrix, 0, 1f, dstRatio / effRatio, 1f)
                }
            } catch (e: Exception) {
                XposedBridge.log("xCam: Matrix scaling failed: ${e.message}")
            }

            // 2. Rotation - Applied second to vertices
            // Matches ImageView.setRotation() which is clockwise
            Matrix.rotateM(mvpMatrix, 0, -rotationAngle.toFloat(), 0f, 0f, 1f)

            // 1. Mirroring - Applied first to vertices
            // Matches ImageView.setScaleX(-1) behavior
            if (isMirrored) {
                Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
            }
        }

        private fun videoLoop() {
            var swapErrorCount = 0
            var lastTransformApplied = false

            while (running.get() && !isInterrupted) {
                if (!targetSurface.isValid) {
                    XposedBridge.log("xCam: Target surface is invalid, stopping")
                    break
                }

                if (frameAvailable.compareAndSet(true, false)) {
                    // Update matrix once we have video dimensions
                    if (!lastTransformApplied && videoW > 0 && videoH > 0) {
                        updateMVPMatrix(videoW, videoH)
                        lastTransformApplied = true
                    }

                    try {
                        surfaceTexture?.updateTexImage()
                    } catch (e: Exception) {
                        XposedBridge.log("xCam: updateTexImage failed: ${e.message}")
                        continue
                    }
                    surfaceTexture?.getTransformMatrix(stMatrix)
                    drawFrame(true)

                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                        val err = EGL14.eglGetError()
                        XposedBridge.log("xCam: eglSwapBuffers failed: 0x${Integer.toHexString(err)}")
                        swapErrorCount++
                        if (swapErrorCount > 10) {
                            XposedBridge.log("xCam: Too many swap errors, stopping injection")
                            break
                        }
                    } else {
                        swapErrorCount = 0
                    }
                } else {
                    sleep(10)
                }
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