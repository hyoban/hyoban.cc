package cc.hyoban.moments.data.exchange

import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** What leaves the phone and what comes back: bundles, receipts, backups. */
class Exchange(
    private val repository: MomentsRepository,
    private val store: DataStore,
    private val media: MediaFiles,
    private val exports: File,
    private val generator: String,
) {
    /** A bundle of the Moments to publish, as a zip under the exports directory. */
    suspend fun exportBundle(onlyUnpublished: Boolean): Pair<File, BundleWriter.Result>? = withContext(Dispatchers.IO) {
        val moments = repository.moments.value.filter { !onlyUnpublished || it.published == null || it.updatedAt > it.published.at }
        if (moments.isEmpty()) return@withContext null
        exports.mkdirs()
        val file = File(exports, "moments-${LocalDate.now()}.zip")
        val result = file.outputStream().use { output ->
            BundleWriter(media, generator).write(output, repository.places.value, repository.trips.value, moments)
        }
        file to result
    }

    suspend fun importReceipt(input: InputStream): ReceiptImporter.Summary {
        val receipt = withContext(Dispatchers.IO) { input.use(ReceiptImporter::read) }
        return ReceiptImporter(repository).apply(receipt)
    }

    /** Everything, with the local references and every media file: the same layout, all of it. */
    suspend fun backup(output: OutputStream): BundleWriter.Result = withContext(Dispatchers.IO) {
        output.use {
            BundleWriter(media, generator).write(
                it,
                repository.places.value,
                repository.trips.value,
                repository.moments.value,
                BundleWriter.Options(includeLocal = true, allMedia = true),
            )
        }
    }

    /** Replaces the store with a backup's records; its media files become the app's own copies. */
    suspend fun restore(input: InputStream) {
        withContext(Dispatchers.IO) {
            val root = store.root
            root.deleteRecursively()
            val copies = File(root.parentFile, "media").also { it.mkdirs() }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removePrefix("./")
                    if (entry.isDirectory || name.contains("..")) continue
                    val target = if (name.startsWith("media/")) File(copies, name.removePrefix("media/")) else File(root, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    zip.closeEntry()
                }
            }
        }
        repository.load()
    }
}
