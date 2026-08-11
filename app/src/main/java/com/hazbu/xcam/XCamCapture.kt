package com.hazbu.xcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AndroidMatrix
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

object XCamCapture {

    fun createJpeg(
        context: Context,
        path: String,
        targetW: Int,
        targetH: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Int = 1000,
        printLog: (String) -> Unit,
    ): ByteArray? {
        printLog("Capture Process: Starting for $path (Time: $timeMs ms)")
        return try {
            val rawBitmap: Bitmap? = if (path.lowercase().endsWith(".mp4")) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, path.toUri())
                
                // We use OPTION_CLOSEST_SYNC but try a few ms before to avoid black frames
                val targetUs = if (timeMs > 100) (timeMs - 100) * 1000L else timeMs * 1000L
                printLog("Capture Process: Extracting frame at $targetUs us (PREVIOUS_SYNC)")
                
                var frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                
                if (frame == null) {
                    printLog("Capture Process: PREVIOUS_SYNC failed, trying absolute CLOSEST")
                    frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
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
            
            // 1. Calculate effective source dimensions after rotation
            val rotatedSourceW = if (rotation % 180 != 0) sourceH else sourceW
            val rotatedSourceH = if (rotation % 180 != 0) sourceW else sourceH
            
            // 2. Calculate scale factor for Fit Center
            val scale = Math.min(targetW.toFloat() / rotatedSourceW, targetH.toFloat() / rotatedSourceH)
            
            val matrix = AndroidMatrix()
            matrix.postScale(scale, scale)
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (mirrored) matrix.postScale(-1f, 1f)

            // 3. Create a scaled and rotated version of the source
            val transformedSource = Bitmap.createBitmap(rawBitmap, 0, 0, sourceW, sourceH, matrix, true)
            
            // 4. Create target canvas with black background (Letterbox/Pillarbox)
            val finalBitmap = createBitmap(targetW, targetH)
            val canvas = android.graphics.Canvas(finalBitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            
            // Center the transformed source on the canvas
            val left = (targetW - transformedSource.width) / 2f
            val top = (targetH - transformedSource.height) / 2f
            canvas.drawBitmap(transformedSource, left, top, null)

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            val result = out.toByteArray()
            
            printLog("Capture Process: SUCCESS. Final Size ${finalBitmap.width}x${finalBitmap.height} (${result.size} bytes)")
            
            if (rawBitmap != transformedSource) rawBitmap.recycle()
            transformedSource.recycle()
            finalBitmap.recycle()
            
            result
        } catch (e: Exception) {
            printLog("createCaptureJpeg error: ${e.message}")
            null
        }
    }
}
