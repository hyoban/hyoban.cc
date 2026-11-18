package cc.hyoban.moments.nas

import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.data.local.toPhotoRef
import java.io.File
import java.io.OutputStream

/**
 * Where a computer keeps the NAS's photos: a folder of its own ([folder], the Pictures folder's
 * Here), one already there found by its name and size, written under a name of its own until whole.
 */
class FolderKeep(private val folder: File) : NasKeep {
    override fun existing(photo: NasPhoto): PhotoRef? =
        File(folder, photo.name).takeIf { it.isFile && (photo.size <= 0 || it.length() == photo.size) }?.toPhotoRef()

    override suspend fun save(photo: NasPhoto, write: suspend (OutputStream) -> Unit): PhotoRef {
        folder.mkdirs()
        val part = File(folder, ".${photo.name}.part")
        try {
            part.outputStream().buffered(1 shl 20).use { write(it) }
            // A different file of the same name stays: this one takes the next free name.
            val target = generateSequence(0) { it + 1 }.map { n ->
                if (n == 0) File(folder, photo.name)
                else File(folder, "${photo.name.substringBeforeLast('.')} ($n).${photo.name.substringAfterLast('.', "")}".removeSuffix("."))
            }.first { !it.exists() }
            check(part.renameTo(target)) { "the photo couldn't be named ${target.name}" }
            return target.toPhotoRef()
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }
}
