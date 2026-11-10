package cc.hyoban.moments.ui.format

import cc.hyoban.moments.res.*
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
fun failureReason(error: Throwable): String = textOf(
    when (error) {
        is NoTokenException -> Res.string.reason_no_token
        is HttpException -> when (error.code) {
            401, 403 -> Res.string.reason_token
            404 -> Res.string.reason_not_found
            in 500..599 -> Res.string.reason_server
            else -> Res.string.reason_rejected
        }
        is SocketTimeoutException -> Res.string.reason_timeout
        is UnknownHostException, is ConnectException, is SSLException -> Res.string.reason_unreachable
        is SerializationException -> Res.string.reason_unreadable
        else -> Res.string.reason_unknown
    },
)
