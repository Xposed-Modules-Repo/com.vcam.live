package com.hazbu.xcam.core.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.*
import android.view.Surface
import androidx.core.net.toUri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class XCamRenderer(
    private val context: Context,
    val currentPath: String,
    private val isMirrored: Boolean,
    private val rotationAngle: Int,
    private val printLog: (String) -> Unit
) {
    private var program = 0
    private var textureId = -1
    private var isOES = false
    private var surfaceTexture: SurfaceTexture? = null
    private var mediaPlayer: MediaPlayer? = null
    private val frameAvailable = AtomicBoolean(false)
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)).position(0)
    }
    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
    }
    private var mediaW = 0
    private var mediaH = 0
    private var initialized = false

    init {
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    private fun logRender(msg: String) {
        printLog("[*] xCam [RENDER] $msg")
    }

    private fun setup() {
        logRender("Starting Renderer Setup for: $currentPath")
        try {
            if (currentPath.lowercase().endsWith(".mp4")) {
                isOES = true
                val tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }
                textureId = tex[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                surfaceTexture = SurfaceTexture(textureId).apply { setOnFrameAvailableListener { frameAvailable.set(true) } }
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
                val bitmap = context.contentResolver.openInputStream(currentPath.toUri())?.use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) {
                    mediaW = bitmap.width; mediaH = bitmap.height
                    logRender("[+] Image Loaded: ${mediaW}x${mediaH}")
                    val tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }
                    textureId = tex[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    bitmap.recycle()
                }
            }
            initShaders()
            initialized = true
            logRender("[+] Renderer Setup Complete")
        } catch (e: Exception) { logRender("[!] Renderer Setup Failed: ${e.message}") }
    }

    private fun initShaders() {
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
        
        program = GLES20.glCreateProgram().apply {
            val vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).apply { 
                GLES20.glShaderSource(this, vs)
                GLES20.glCompileShader(this) 
            }
            val fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).apply { 
                GLES20.glShaderSource(this, fs)
                GLES20.glCompileShader(this) 
            }
            GLES20.glAttachShader(this, vShader)
            GLES20.glAttachShader(this, fShader)
            GLES20.glLinkProgram(this)
        }
    }

    fun draw(viewportW: Int, viewportH: Int): Boolean {
        if (!initialized) setup()
        if (!initialized) return false
        try {
            if (isOES && frameAvailable.compareAndSet(true, false)) { surfaceTexture?.updateTexImage(); surfaceTexture?.getTransformMatrix(stMatrix) }
            updateMVPMatrix(viewportW.toFloat(), viewportH.toFloat())
            GLES20.glViewport(0, 0, viewportW, viewportH)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val aPos = GLES20.glGetAttribLocation(program, "aPosition"); val aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTex); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, texBuffer)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVPMatrix"), 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSTMatrix"), 1, false, stMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(if (isOES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            return true
        } catch (_: Exception) { return false }
    }

    private fun updateMVPMatrix(viewW: Float, viewH: Float) {
        if (mediaW <= 0 || mediaH <= 0) return
        Matrix.setIdentityM(mvpMatrix, 0)
        
        val rotatedSourceAspect = if (rotationAngle % 180 != 0) mediaH.toFloat() / mediaW else mediaW.toFloat() / mediaH
        val targetAspect = viewW / viewH

        if (rotatedSourceAspect > targetAspect) {
            Matrix.scaleM(mvpMatrix, 0, 1f, targetAspect / rotatedSourceAspect, 1f)
        } else {
            Matrix.scaleM(mvpMatrix, 0, rotatedSourceAspect / targetAspect, 1f, 1f)
        }
        
        Matrix.rotateM(mvpMatrix, 0, -rotationAngle.toFloat(), 0f, 0f, 1f)
        
        if (isMirrored) Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
    }

    fun release() { try { mediaPlayer?.release(); surfaceTexture?.release(); GLES20.glDeleteProgram(program) } catch (_: Exception) {} }
}
