package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.exifTime
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import java.io.InputStream

/** The computer's reading of the EXIF, by metadata-extractor, the same fields the phone's ExifInterface fills. */
actual fun PhotoDetails.withExif(input: InputStream): PhotoDetails {
    val metadata = ImageMetadataReader.readMetadata(input)
    val sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
    val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
    fun text(tag: Int) = (sub?.getString(tag) ?: ifd0?.getString(tag))?.trim()?.takeIf { it.isNotEmpty() }
    fun number(tag: Int) = (sub?.getDoubleObject(tag) ?: ifd0?.getDoubleObject(tag))?.takeIf { !it.isNaN() && it != 0.0 }
    fun int(tag: Int) = (sub?.getInteger(tag) ?: ifd0?.getInteger(tag))?.takeIf { it > 0 }
    val zone = text(ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL) ?: text(ExifDirectoryBase.TAG_TIME_ZONE)
    val turned = ifd0?.getInteger(ExifDirectoryBase.TAG_ORIENTATION) in setOf(5, 6, 7, 8)
    val w = int(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH) ?: int(ExifDirectoryBase.TAG_IMAGE_WIDTH)
    val h = int(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT) ?: int(ExifDirectoryBase.TAG_IMAGE_HEIGHT)
    val make = text(ExifDirectoryBase.TAG_MAKE)
    return copy(
        takenAt = exifTime(text(ExifDirectoryBase.TAG_DATETIME_ORIGINAL) ?: text(ExifDirectoryBase.TAG_DATETIME), zone) ?: takenAt,
        offset = zone ?: offset,
        make = make ?: this.make,
        model = text(ExifDirectoryBase.TAG_MODEL)?.let { m -> if (make != null && m.startsWith(make, ignoreCase = true)) m.substring(make.length).trim() else m } ?: model,
        lens = text(ExifDirectoryBase.TAG_LENS_MODEL) ?: lens,
        focal = number(ExifDirectoryBase.TAG_FOCAL_LENGTH) ?: focal,
        focal35 = int(ExifDirectoryBase.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH) ?: focal35,
        aperture = number(ExifDirectoryBase.TAG_FNUMBER) ?: aperture,
        exposure = number(ExifDirectoryBase.TAG_EXPOSURE_TIME) ?: exposure,
        iso = int(ExifDirectoryBase.TAG_ISO_EQUIVALENT) ?: iso,
        bias = number(ExifDirectoryBase.TAG_EXPOSURE_BIAS) ?: bias,
        fix = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation
            ?.takeIf { !it.isZero }?.let { Fix(it.latitude, it.longitude) } ?: fix,
        width = width ?: (if (turned) h else w),
        height = height ?: (if (turned) w else h),
    )
}
