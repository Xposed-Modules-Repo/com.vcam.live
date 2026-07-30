package com.hazbu.xcam

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hazbu.xcam.Constants.AUTHORITY
import com.hazbu.xcam.Constants.KEY_IS_ENABLED
import com.hazbu.xcam.Constants.KEY_VIDEO_PATH
import android.os.ParcelFileDescriptor
import java.io.File

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(KEY_VIDEO_PATH, KEY_IS_ENABLED))
        
        // Return a stable URI for the video file that our provider will handle
        val videoUri = "content://$AUTHORITY/video"
        
        cursor.addRow(arrayOf(
            videoUri,
            "1" // Always enabled
        ))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val file = File(context.filesDir, "virtual.mp4")
        
        if (!file.exists()) {
            return null
        }
        
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
