package com.hazbu.xcam

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hazbu.xcam.Constants.AUTHORITY
import com.hazbu.xcam.Constants.KEY_IS_ENABLED
import com.hazbu.xcam.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.Constants.KEY_ROTATION_ANGLE
import com.hazbu.xcam.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.Constants.PREFS_NAME
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs?.getString(KEY_MEDIA_PATH, "") ?: ""
        val fileName = if (path.isNotEmpty()) File(path).name else "media"
        
        val cursor = MatrixCursor(arrayOf(KEY_MEDIA_PATH, KEY_IS_ENABLED, KEY_IS_MIRRORED, KEY_ROTATION_ANGLE))
        val videoUri = "content://$AUTHORITY/$fileName"
        
        cursor.addRow(arrayOf(
            videoUri,
            "1",
            if (prefs?.getBoolean(KEY_IS_MIRRORED, false) == true) "1" else "0",
            (prefs?.getInt(KEY_ROTATION_ANGLE, 0) ?: 0).toString()
        ))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val fileName = uri.lastPathSegment ?: "virtual.mp4"
        val file = File(context.filesDir, fileName)
        
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
