package cc.hyoban.moments

import android.util.Log

actual fun logWarning(tag: String, message: String, error: Throwable?) {
    Log.w(tag, message, error)
}
