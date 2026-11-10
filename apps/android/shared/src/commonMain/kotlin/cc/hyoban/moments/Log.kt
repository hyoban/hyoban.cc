package cc.hyoban.moments

/** Notes [message], and [error] if there is one, where the device keeps its warnings: the phone's log, a computer's standard error. */
expect fun logWarning(tag: String, message: String, error: Throwable? = null)
