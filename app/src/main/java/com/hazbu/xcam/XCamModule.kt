package com.hazbu.xcam
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
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

class XCamModule : IXposedHookLoadPackage {
    private var videoPath: String? = null
    private var isInitialized = false
    private var mediaPlayer: MediaPlayer? = null
    private var targetSurface: Surface? = null
    private var mContext: Context? = null

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
                    videoPath = cursor.getString(0)
                    XposedBridge.log("xCam: Settings refreshed: $videoPath")
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
                        XposedBridge.log("xCam: Camera1.open() detected in ${lpparam.packageName}")
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookCamera2(lpparam: LoadPackageParam) {
        XposedBridge.log("xCam: Initializing Camera2 hooks for ${lpparam.packageName}")

        try {
            val cameraDeviceImpl = "android.hardware.camera2.impl.CameraDeviceImpl"
            
            // Hook all createCaptureSession variants
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (videoPath.isNullOrEmpty()) return
                    
                    var foundSurface: Surface? = null
                    
                    // Case 1: SessionConfiguration (Android 9+)
                    if (param.args.isNotEmpty() && param.args[0] is SessionConfiguration) {
                        val config = param.args[0] as SessionConfiguration
                        val outputs = config.outputConfigurations
                        if (outputs.isNotEmpty()) {
                            foundSurface = outputs[0].surface
                        }
                    } 
                    // Case 2: List of Surfaces (Legacy)
                    else if (param.args.isNotEmpty() && param.args[0] is List<*>) {
                        val list = param.args[0] as List<*>
                        if (list.isNotEmpty()) {
                            foundSurface = list[0] as? Surface
                            // Try to find a non-ImageReader surface if possible
                            for (item in list) {
                                if (item is Surface && item.toString().contains("SurfaceView", ignoreCase = true)) {
                                    foundSurface = item
                                    break
                                }
                            }
                        }
                    }

                    if (foundSurface != null) {
                        targetSurface = foundSurface
                        startVideoInjection(foundSurface)
                    }
                }
            }

            // Hook common variants
            XposedHelpers.findAndHookMethod(cameraDeviceImpl, lpparam.classLoader, "createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, hook)
            XposedHelpers.findAndHookMethod(cameraDeviceImpl, lpparam.classLoader, "createCaptureSession", SessionConfiguration::class.java, hook)
            
            try {
                XposedHelpers.findAndHookMethod(cameraDeviceImpl, lpparam.classLoader, "createCaptureSessionByOutputConfigurations", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, hook)
            } catch (_: Throwable) {}

        } catch (e: Throwable) {
            XposedBridge.log("xCam: Camera2 capture session hooks failed: ${e.message}")
        }

        try {
            // Hook ImageReader to intercept still captures and logging
            XposedHelpers.findAndHookMethod(
                "android.media.ImageReader",
                lpparam.classLoader,
                "acquireNextImage",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val image = param.result as? Image ?: return
                        // Only log once every 30 frames to avoid spamming
                        if (System.currentTimeMillis() % 1000 < 33) {
                            XposedBridge.log("xCam: ImageReader active: ${image.width}x${image.height}")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun startVideoInjection(surface: Surface) {
        val path = videoPath ?: return
        val context = mContext ?: return
        
        XposedBridge.log("xCam: Starting video injection for surface: $surface")
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val videoUri = Uri.parse(path)
                
                val afd = context.contentResolver.openAssetFileDescriptor(videoUri, "r")
                if (afd != null) {
                    XposedBridge.log("xCam: AssetFileDescriptor opened, size: ${afd.length}")
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    XposedBridge.log("xCam: Failed to open AssetFileDescriptor for $path")
                    return
                }
                
                setSurface(surface)
                isLooping = true
                
                setOnPreparedListener { 
                    it.start()
                    XposedBridge.log("xCam: Virtual feed started")
                }
                
                setOnErrorListener { _, what, extra ->
                    XposedBridge.log("xCam: MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                
                prepareAsync()
            }
        } catch (e: Exception) {
            XposedBridge.log("xCam: Error in startVideoInjection: ${e.message}")
        }
    }
}
