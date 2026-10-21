package cc.hyoban.moments.ui.format

import android.content.Context
import cc.hyoban.moments.R
import cc.hyoban.moments.data.HttpException
import cc.hyoban.moments.data.api.NoTokenException
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Why something failed, in words a reader can act on rather than the exception's own,
// which is English and names requests and status codes.

/** What went wrong with [error], to follow "Couldn't …:" in a message. */
fun failureReason(context: Context, error: Throwable): String = context.getString(
    when (error) {
        is NoTokenException -> R.string.reason_no_token
        is HttpException -> when (error.code) {
            401, 403 -> R.string.reason_token
            404 -> R.string.reason_not_found
            in 500..599 -> R.string.reason_server
            else -> R.string.reason_rejected
        }
        is SocketTimeoutException -> R.string.reason_timeout
        is UnknownHostException, is ConnectException, is SSLException -> R.string.reason_unreachable
        is SerializationException -> R.string.reason_unreadable
        else -> R.string.reason_unknown
    },
)
