package cc.hyoban.moments.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.data.local.toPhotoRef
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The pictures a computer's files are, by their names' endings. */
private val PICTURES = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")

private fun picturesIn(transferable: Transferable): List<PhotoRef> = runCatching {
    when {
        transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
                .filter { it.extension.lowercase() in PICTURES }.map { it.toPhotoRef() }
        transferable.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
            // An image copied rather than a file: kept as a PNG of its own to be added like one.
            val image = transferable.getTransferData(DataFlavor.imageFlavor) as Image
            val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
            val file = File.createTempFile("pasted-", ".png").apply { deleteOnExit() }
            ImageIO.write(buffered, "png", file)
            listOf(file.toPhotoRef())
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
actual fun Modifier.dropPhotos(onPhotos: (List<PhotoRef>) -> Unit): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { true },
    target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val photos = picturesIn(event.awtTransferable)
            if (photos.isNotEmpty()) onPhotos(photos)
            return photos.isNotEmpty()
        }
    },
)

actual fun clipboardPhotos(): List<PhotoRef> = runCatching {
    picturesIn(Toolkit.getDefaultToolkit().systemClipboard.getContents(null))
}.getOrDefault(emptyList())
