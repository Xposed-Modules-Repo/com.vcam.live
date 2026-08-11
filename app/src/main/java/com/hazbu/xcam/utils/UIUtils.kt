package com.hazbu.xcam.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object UIUtils {
    private val uiHandler = Handler(Looper.getMainLooper())

    fun showToast(context: Context?, message: String, logAction: ((String) -> Unit)? = null) {
        uiHandler.post {
            try {
                context?.let {
                    Toast.makeText(it, message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                logAction?.invoke("Failed to show toast: ${e.message}")
            }
        }
    }
}
