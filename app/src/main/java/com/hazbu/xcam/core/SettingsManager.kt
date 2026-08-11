package com.hazbu.xcam.core

import android.content.Context
import androidx.core.net.toUri
import com.hazbu.xcam.Constants.AUTHORITY

class SettingsManager {
    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0

    fun refreshSettings(context: Context) {
        try {
            val uri = "content://$AUTHORITY".toUri()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mediaPath = cursor.getString(0)
                    isMirrored = cursor.getString(2) == "1"
                    rotationAngle = cursor.getString(3).toIntOrNull() ?: 0
                }
            }
        } catch (_: Exception) {}
    }
}
