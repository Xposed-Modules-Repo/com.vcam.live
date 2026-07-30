package com.hazbu.xcam

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.view.Surface
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.atomic.AtomicBoolean

class XCamModule : IXposedHookLoadPackage {
    private var mediaPath: String? = null
    private var isMirrored = false
    private var isInitialized = false
    private var mediaPlayer: MediaPlayer? = null
    private var mContext: Context? = null
    private var imageInjectionThread: Thread? = null
    private val isInjectingImage = AtomicBoolean(false)

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
                    // Index 1 is isEnabled (unused), Index 2 is isMirrored
                    isMirrored = cursor.getString(2) == "1"
                    XposedBridge.log("xCam: Settings refreshed: $mediaPath, mirrored=$isMirrored")
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
                        if (System.currentTimeMillis() % 2000 < 33) {
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
        
        stopCurrentInjection()
        
        XposedBridge.log("xCam: startInjection with path: $path")
        
        if (path.endsWith(".mp4", ignoreCase = true)) {
            surfaces.firstOrNull { it.isValid }?.let { startMediaPlayer(context, path, it) }
        } else {
            // For images, only inject into the first valid surface to avoid buffer overflows
            val target = surfaces.firstOrNull { it.isValid }
            if (target != null) {
                startImageLoop(path, target)
            }
        }
    }

    private fun stopCurrentInjection() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            
            isInjectingImage.set(false)
            imageInjectionThread?.interrupt()
            imageInjectionThread = null
        } catch (_: Exception) {}
    }

    private fun startMediaPlayer(context: Context, path: String, surface: Surface) {
        try {
            mediaPlayer = MediaPlayer().apply {
                val videoUri = Uri.parse(path)
                context.contentResolver.openAssetFileDescriptor(videoUri, "r")?.use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } ?: return
                setSurface(surface)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
                XposedBridge.log("xCam: Video started")
            }
        } catch (e: Exception) {
            XposedBridge.log("xCam: Video error: ${e.message}")
        }
    }

    private fun startImageLoop(path: String, surface: Surface) {
        val context = mContext ?: return
        isInjectingImage.set(true)
        
        imageInjectionThread = Thread {
            try {
                val uri = Uri.parse(path)
                val bitmap = context.contentResolver.openInputStream(uri)?.use { 
                    BitmapFactory.decodeStream(it)
                }
                
                if (bitmap == null) return@Thread
                
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                XposedBridge.log("xCam: Image injection active")
                
                while (isInjectingImage.get() && !Thread.currentThread().isInterrupted) {
                    if (!surface.isValid) break
                    
                    var canvas: Canvas? = null
                    try {
                        // Use standard lockCanvas which is more stable across different surface types
                        canvas = surface.lockCanvas(null)
                        if (canvas != null) {
                            val destRect = calculateDestRect(bitmap.width, bitmap.height, canvas.width, canvas.height)
                            
                            if (isMirrored) {
                                canvas.save()
                                // Mirror horizontally
                                canvas.scale(-1f, 1f, canvas.width / 2f, canvas.height / 2f)
                                canvas.drawBitmap(bitmap, srcRect, destRect, null)
                                canvas.restore()
                            } else {
                                canvas.drawBitmap(bitmap, srcRect, destRect, null)
                            }
                        }
                    } catch (e: Exception) {
                        // If we can't lock, wait a bit longer
                        Thread.sleep(500)
                        continue
                    } finally {
                        if (canvas != null) {
                            try {
                                surface.unlockCanvasAndPost(canvas)
                            } catch (_: Exception) {}
                        }
                    }
                    // Slow refresh for static images (2 FPS) to keep buffers healthy
                    Thread.sleep(500)
                }
            } catch (_: Exception) {
            } finally {
                XposedBridge.log("xCam: Image injection stopped")
            }
        }.apply { 
            name = "xCam-Img"
            start() 
        }
    }

    private fun calculateDestRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = dstW.toFloat() / dstH
        return if (srcRatio > dstRatio) {
            val finalW = dstH * srcRatio
            val offset = (finalW - dstW) / 2
            Rect(-offset.toInt(), 0, (dstW + offset).toInt(), dstH)
        } else {
            val finalH = dstW / srcRatio
            val offset = (finalH - dstH) / 2
            Rect(0, -offset.toInt(), dstW, (dstH + offset).toInt())
        }
    }
}
