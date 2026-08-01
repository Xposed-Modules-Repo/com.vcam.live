package com.hazbu.xcam

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.MediaPlayer
import android.opengl.*
import android.os.Handler
import android.view.Surface
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import com.otaliastudios.opengl.core.*
import com.otaliastudios.opengl.draw.GlRect
import com.otaliastudios.opengl.program.*
import com.otaliastudios.opengl.surface.*
import com.otaliastudios.opengl.texture.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
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
        private var eglCore: EglCore? = null
        private var windowSurface: EglWindowSurface? = null
        private var program: GlTextureProgram? = null
        private var texture: GlTexture? = null
        private var surfaceTexture: SurfaceTexture? = null
        private var mediaPlayer: MediaPlayer? = null
        private val mvpMatrix = FloatArray(16)
        private val stMatrix = FloatArray(16)
        private var viewportW = 0f
        private var viewportH = 0f
        private var videoW = 0
        private var videoH = 0
        private lateinit var drawable: GlRect
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

        private fun initEGL() {
            // Using FLAG_RECORDABLE to ensure compatibility with camera surfaces
            eglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
            windowSurface = EglWindowSurface(eglCore!!, targetSurface)
            windowSurface?.makeCurrent()
            
            viewportW = windowSurface?.getWidth()?.toFloat() ?: 0f
            viewportH = windowSurface?.getHeight()?.toFloat() ?: 0f
            XposedBridge.log("xCam: EGL Bound. Viewport: ${viewportW}x${viewportH}")
            GLES20.glViewport(0, 0, viewportW.toInt(), viewportH.toInt())
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        private fun initBuffers() {
            drawable = GlRect()
            Matrix.setIdentityM(stMatrix, 0)
            Matrix.setIdentityM(mvpMatrix, 0)
        }

        private fun initShaders(isOES: Boolean) {
            val vs = """
                attribute vec4 aPosition;
                attribute vec2 aTextureCoord;
                varying vec2 vTextureCoord;
                uniform mat4 uSTMatrix;
                uniform mat4 uMVPMatrix;
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
                }
            """.trimIndent()
            
            val fs = if (isOES) {
                """
                    #extension GL_OES_EGL_image_external : require
                    precision mediump float;
                    varying vec2 vTextureCoord;
                    uniform samplerExternalOES sTexture;
                    void main() {
                        gl_FragColor = texture2D(sTexture, vTextureCoord);
                    }
                """.trimIndent()
            } else {
                """
                    precision mediump float;
                    varying vec2 vTextureCoord;
                    uniform sampler2D sTexture;
                    void main() {
                        gl_FragColor = texture2D(sTexture, vTextureCoord);
                    }
                """.trimIndent()
            }
            
            program = GlTextureProgram(
                vertexShader = vs,
                fragmentShader = fs,
                textureTransformName = "uSTMatrix"
            )
        }

        private fun setupVideo() {
            texture = GlTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            surfaceTexture = SurfaceTexture(texture!!.id).apply { 
                setOnFrameAvailableListener { frameAvailable.set(true) } 
            }
            initShaders(true)
            mediaPlayer = MediaPlayer().apply {
                val uri = path.toUri()
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { 
                    setDataSource(it.fileDescriptor, it.startOffset, it.length) 
                }
                setSurface(Surface(surfaceTexture))
                isLooping = true
                setOnVideoSizeChangedListener { _, w, h ->
                    videoW = w
                    videoH = h
                }
                setOnPreparedListener {
                    videoW = it.videoWidth
                    videoH = it.videoHeight
                    it.start()
                }
                prepareAsync()
            }
        }

        private fun setupImage() {
            val bitmap = context.contentResolver.openInputStream(path.toUri())?.use {
                BitmapFactory.decodeStream(it) 
            } ?: return
            
            texture = GlTexture(GLES20.GL_TEXTURE_2D).apply {
                bind()
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                unbind()
            }
            
            initShaders(false)
            updateMVPMatrix(bitmap.width, bitmap.height)
            bitmap.recycle()
        }

        private fun updateMVPMatrix(srcW: Int, srcH: Int) {
            if (srcW <= 0 || srcH <= 0 || viewportW <= 0 || viewportH <= 0) return
            val effRatio = if (rotationAngle % 180 == 0) srcW.toFloat() / srcH else srcH.toFloat() / srcW
            val dstRatio = viewportW / viewportH

            Matrix.setIdentityM(mvpMatrix, 0)
            if (effRatio > dstRatio) {
                Matrix.scaleM(mvpMatrix, 0, effRatio / dstRatio, 1f, 1f)
            } else {
                Matrix.scaleM(mvpMatrix, 0, 1f, dstRatio / effRatio, 1f)
            }
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
                    
                    drawFrame()
                    windowSurface?.swapBuffers()
                } else {
                    sleep(10)
                }
            }
        }

        private fun imageLoop() {
            while (running.get() && !isInterrupted) {
                drawFrame()
                windowSurface?.swapBuffers()
                sleep(33)
            }
        }

        private fun drawFrame() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val p = program ?: return
            val t = texture ?: return
            
            p.texture = t
            p.textureTransform = stMatrix
            p.draw(drawable, mvpMatrix)
        }

        private fun releaseResources() {
            try {
                mediaPlayer?.release()
                surfaceTexture?.release()
                program?.release()
                texture?.release()
                windowSurface?.release()
                eglCore?.release()
            } catch (_: Exception) {}
        }
    }
}
