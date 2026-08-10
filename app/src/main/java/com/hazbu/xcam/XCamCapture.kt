package com.hazbu.xcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AndroidMatrix
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream

object XCamCapture {

    fun createJpeg(
        context: Context,
        path: String,
        targetW: Int,
        targetH: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Int = 1000,
        printLog: (String) -> Unit
    ): ByteArray? {
        printLog("Capture Process: Starting for $path (Time: $timeMs ms)")
        return try {
            val rawBitmap: Bitmap? = if (path.lowercase().endsWith(".mp4")) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, path.toUri())
                
                // We use OPTION_CLOSEST_SYNC but try a few ms before to avoid black frames
                val targetUs = if (timeMs > 100) (timeMs - 100) * 1000L else timeMs * 1000L
                printLog("Capture Process: Extracting frame at $targetUs us (PREVIOUS_SYNC)")
                
                var frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                
                if (frame == null) {
                    printLog("Capture Process: PREVIOUS_SYNC failed, trying absolute CLOSEST")
                    frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                }

                retriever.release()
                if (frame == null) printLog("Capture Process: FATAL - No frame retrieved")
                frame
            } else {
                printLog("Capture Process: Decoding image from $path")
                context.contentResolver.openInputStream(path.toUri())?.use { BitmapFactory.decodeStream(it) }
            }

            if (rawBitmap == null) {
                printLog("Capture Process: Raw bitmap is null")
                return null
            }
            
            printLog("Capture Process: Source Size ${rawBitmap.width}x${rawBitmap.height}")

            val sourceW = rawBitmap.width
            val sourceH = rawBitmap.height
            val sourceRatio = sourceW.toFloat() / sourceH
            val targetRatio = targetW.toFloat() / targetH

            var cropW = sourceW
            var cropH = sourceH
            var offsetX = 0
            var offsetY = 0

            if (sourceRatio > targetRatio) {
                cropW = (sourceH * targetRatio).toInt()
                offsetX = (sourceW - cropW) / 2
            } else {
                cropH = (sourceW / targetRatio).toInt()
                offsetY = (sourceH - cropH) / 2
            }

            val matrix = AndroidMatrix()
            matrix.postScale(targetW.toFloat() / cropW, targetH.toFloat() / cropH)
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (mirrored) matrix.postScale(-1f, 1f)

            val finalBitmap = Bitmap.createBitmap(rawBitmap, offsetX, offsetY, cropW, cropH, matrix, true)
            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            val result = out.toByteArray()
            
            printLog("Capture Process: SUCCESS. Final Size ${finalBitmap.width}x${finalBitmap.height} (${result.size} bytes)")
            
            if (rawBitmap != finalBitmap) rawBitmap.recycle()
            finalBitmap.recycle()
            
            result
        } catch (e: Exception) {
            printLog("createCaptureJpeg error: ${e.message}")
            null
        }
    }
}
