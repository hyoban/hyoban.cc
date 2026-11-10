package cc.hyoban.moments.accessibility

import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.res.*


internal enum class PaymentCapture(val message: StringResource) {
    OPENED(Res.string.payment_capture_opened),
    DISCONNECTED(Res.string.payment_capture_disconnected),
    BUSY(Res.string.payment_capture_busy),
    LOCKED(Res.string.payment_capture_locked),
    NOT_FOUND(Res.string.payment_capture_not_found),
    ALREADY_SAVED(Res.string.payment_capture_already_saved),
    FAILED(Res.string.payment_capture_failed),
}

/** What a side-key press found in front: the app, and whether its page shows a payment made. */
internal data class SideKeyFront(val packageName: String?, val paymentPage: Boolean)
