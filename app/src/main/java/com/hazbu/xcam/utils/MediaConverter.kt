package com.hazbu.xcam.utils

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

@OptIn(UnstableApi::class)
object MediaConverter {

    fun convertImageToMp4(
        context: Context,
        imageUri: Uri,
        outputFile: File,
        onComplete: (Boolean) -> Unit
    ) {
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(imageUri)
            .setImageDurationMs(5000)
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setFrameRate(30)
            .build()

        val transformer = Transformer.Builder(context).build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                onComplete(true)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                onComplete(false)
            }
        })

        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (_: Exception) {
            onComplete(false)
        }
    }
}
