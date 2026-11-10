package cc.hyoban.moments.publish

import cc.hyoban.moments.data.store.PublishProgress
import cc.hyoban.moments.res.*

/** What publishing is doing: how much of the originals has gone while they upload, then the write. */
fun publishingText(progress: PublishProgress): String =
    if (progress.size > 0 && progress.sent < progress.size) {
        // In MB, the unit once, so it fits the button on one line.
        textOf(Res.string.message_uploading_progress, "%.1f".format(progress.sent / 1e6), "%.0f".format(progress.size / 1e6))
    } else {
        textOf(Res.string.message_publishing)
    }
