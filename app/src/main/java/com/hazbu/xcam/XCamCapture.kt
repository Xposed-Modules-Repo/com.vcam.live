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
        printLog: (String) -> Unit
    ): ByteArray? {
        return try {
            val rawBitmap: Bitmap? = if (path.lowercase().endsWith(".mp4")) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, path.toUri())
                val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame
            } else {
                context.contentResolver.openInputStream(path.toUri())?.use { BitmapFactory.decodeStream(it) }
            }

            if (rawBitmap == null) return null

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
            
            if (rawBitmap != finalBitmap) rawBitmap.recycle()
            finalBitmap.recycle()
            
            out.toByteArray()
        } catch (e: Exception) {
            printLog("createCaptureJpeg error: ${e.message}")
            null
        }
    }
}
