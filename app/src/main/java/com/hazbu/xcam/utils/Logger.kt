package com.hazbu.xcam.utils

import android.util.Log
import io.github.libxposed.api.XposedInterface

object Logger {
    private const val TAG = "xCam"
    private const val VERSION = "v22.10-master"

    fun printLog(xi: XposedInterface?, msg: String, tr: Throwable? = null) {
        val fullMsg = "xCam: [$VERSION] $msg"
        xi?.log(XposedInterface.PRIORITY_HIGHEST, TAG, fullMsg)
        if (tr != null) Log.e(TAG, fullMsg, tr) else Log.e(TAG, fullMsg)
    }
}
