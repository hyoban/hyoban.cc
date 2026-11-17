package cc.hyoban.moments.data.local

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

/** The Mac finds no faces or plates and offers no 打码. */
actual object Masking {
    actual val available: Boolean = false

    actual suspend fun find(ref: PhotoRef): FoundPatches? = null

    actual suspend fun preview(ref: PhotoRef, masks: List<Mask>): Map<Mask, ImageBitmap>? = null

    actual suspend fun pixelate(source: File, target: File, masks: List<Mask>): CopiedPhoto =
        error("Photos can't be pixelated on this computer")
}
