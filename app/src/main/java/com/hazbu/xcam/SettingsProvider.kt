package com.hazbu.xcam
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hazbu.xcam.Constants.AUTHORITY
import com.hazbu.xcam.Constants.KEY_IS_ENABLED
import com.hazbu.xcam.Constants.KEY_VIDEO_PATH
import com.hazbu.xcam.Constants.PREFS_NAME
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

import android.util.Log

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        Log.d("xCamProvider", "Query received: $uri")
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf(KEY_VIDEO_PATH, KEY_IS_ENABLED))
        
        // Return a stable URI for the video file that our provider will handle
        val videoUri = "content://$AUTHORITY/video"
        
        cursor.addRow(arrayOf(
            videoUri,
            if (prefs?.getBoolean(KEY_IS_ENABLED, false) == true) "1" else "0"
        ))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        Log.d("xCamProvider", "openFile called: $uri")
        val context = context ?: return null
        val file = File(context.filesDir, "virtual.mp4")
        
        if (!file.exists()) {
            Log.e("xCamProvider", "File does not exist at: ${file.absolutePath}")
            return null
        }
        
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            Log.d("xCamProvider", "File opened successfully: ${file.absolutePath}")
            pfd
        } catch (e: Exception) {
            Log.e("xCamProvider", "Error opening file: ${e.message}")
            null
        }
    }

    override fun getType(uri: Uri): String = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
