package cc.hyoban.moments.ui.viewer

import androidx.exifinterface.media.ExifInterface
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.exifTime
import java.io.InputStream

actual fun PhotoDetails.withExif(input: InputStream): PhotoDetails {
    val exif = ExifInterface(input)
    fun text(tag: String) = exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }
    fun number(tag: String) = exif.getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() && it != 0.0 }
    val zone = text(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: text(ExifInterface.TAG_OFFSET_TIME)
    val turned = exif.rotationDegrees == 90 || exif.rotationDegrees == 270
    val w = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
        ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
    val h = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
        ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
    val make = text(ExifInterface.TAG_MAKE)
    return copy(
        takenAt = exifTime(text(ExifInterface.TAG_DATETIME_ORIGINAL) ?: text(ExifInterface.TAG_DATETIME), zone) ?: takenAt,
        offset = zone ?: offset,
        make = make ?: this.make,
        // A model that repeats its make (Apple's "iPhone", Sony's "ILCE-…" don't) is written once.
        model = text(ExifInterface.TAG_MODEL)?.let { m -> if (make != null && m.startsWith(make, ignoreCase = true)) m.substring(make.length).trim() else m } ?: model,
        lens = text(ExifInterface.TAG_LENS_MODEL) ?: lens,
        focal = number(ExifInterface.TAG_FOCAL_LENGTH) ?: focal,
        focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 } ?: focal35,
        aperture = number(ExifInterface.TAG_F_NUMBER) ?: aperture,
        exposure = number(ExifInterface.TAG_EXPOSURE_TIME) ?: exposure,
        iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 } ?: iso,
        bias = number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE) ?: bias,
        fix = exif.latLong?.let { (lat, lng) -> Fix(lat, lng) }?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 } ?: fix,
        width = width ?: (if (turned) h else w),
        height = height ?: (if (turned) w else h),
    )
}
