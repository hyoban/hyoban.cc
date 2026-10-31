package cc.hyoban.moments.accessibility

import cc.hyoban.moments.R

internal enum class PaymentCapture(val message: Int) {
    OPENED(R.string.payment_capture_opened),
    DISCONNECTED(R.string.payment_capture_disconnected),
    BUSY(R.string.payment_capture_busy),
    LOCKED(R.string.payment_capture_locked),
    NOT_FOUND(R.string.payment_capture_not_found),
    ALREADY_SAVED(R.string.payment_capture_already_saved),
    FAILED(R.string.payment_capture_failed),
}

/** What a side-key press found in front: the app, and whether its page shows a payment made. */
internal data class SideKeyFront(val packageName: String?, val paymentPage: Boolean)
