package cc.hyoban.moments.footprints

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.Writer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** Local, bounded JSONL. A single IO worker preserves event order without blocking location callbacks. */
class FootprintDiagnostics(
    private val directory: File,
    scope: CoroutineScope,
    private val fileLimit: Int = 1024 * 1024,
) {
    private sealed interface Command {
        data class Event(val line: String) : Command
        data class Export(val writer: Writer, val done: CompletableDeferred<Unit>) : Command
    }
    private val queue = Channel<Command>(128)
    private val dropped = AtomicLong()
    private val current get() = File(directory, "current.jsonl")
    private val previous get() = File(directory, "previous.jsonl")

    init {
        require(fileLimit >= 256)
        scope.launch(Dispatchers.IO) {
            for (command in queue) {
                val lost = dropped.getAndSet(0)
                try {
                    if (lost > 0) append(encode("diagnostic_events_lost", mapOf("count" to lost)))
                    when (command) {
                        is Command.Event -> append(command.line)
                        is Command.Export -> {
                            repairTail()
                            for (file in listOf(previous, current)) {
                                if (file.exists()) file.bufferedReader().use { it.copyTo(command.writer) }
                            }
                            command.done.complete(Unit)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    dropped.addAndGet(lost + if (command is Command.Event) 1 else 0)
                    if (command is Command.Export) command.done.completeExceptionally(e)
                    // Full/unavailable storage must never stop the recorder or its event loop.
                }
            }
        }
    }

    fun record(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (queue.trySend(Command.Event(encode(event, fields))).isFailure) dropped.incrementAndGet()
    }

    /** Drains all previously queued events before exporting. The caller owns the writer. */
    suspend fun export(writer: Writer) {
        val done = CompletableDeferred<Unit>()
        queue.send(Command.Export(writer, done))
        done.await()
    }

    private fun encode(event: String, fields: Map<String, Any?>): String = buildJsonObject {
        put("schema", 1)
        put("time_ms", System.currentTimeMillis())
        put("event", event.take(80))
        for ((key, value) in fields) {
            // Callers supply selected scalar diagnostics, never Location objects or exception messages.
            when (value) {
                is Boolean -> put(key, value)
                is Number -> if (value.toDouble().isFinite()) put(key, value)
                is String -> put(key, value.take(240))
                null -> put(key, JsonNull)
            }
        }
    }.toString() + "\n"

    private fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > fileLimit) throw IOException("Diagnostic event exceeds file limit")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic directory unavailable")
        repairTail()
        if (current.length() + bytes.size > fileLimit) {
            if (previous.exists() && !previous.delete()) throw IOException("Could not rotate diagnostics")
            if (current.exists() && !current.renameTo(previous)) throw IOException("Could not rotate diagnostics")
        }
        current.appendBytes(bytes)
    }

    /** An interrupted append must not corrupt every JSON line written after a process restart. */
    private fun repairTail() {
        if (!current.exists() || current.length() == 0L) return
        RandomAccessFile(current, "rw").use { file ->
            file.seek(file.length() - 1)
            if (file.read() == '\n'.code) return
            // Files written here are bounded; only the incomplete tail is discarded.
            val bytes = ByteArray(file.length().coerceAtMost(fileLimit.toLong()).toInt())
            val offset = file.length() - bytes.size
            file.seek(offset)
            file.readFully(bytes)
            val lastLine = bytes.indexOfLast { it == '\n'.code.toByte() }
            file.setLength(if (lastLine < 0) 0 else offset + lastLine + 1)
        }
    }
}
