package cc.hyoban.moments

actual fun logWarning(tag: String, message: String, error: Throwable?) {
    System.err.println("$tag: $message")
    error?.printStackTrace()
}
