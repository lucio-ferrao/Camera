package app.grapheneos.camera.capturer

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.internal.compat.workaround.ExifRotationAvailability
import androidx.camera.core.internal.utils.ImageUtil
import androidxc.camera.core.impl.utils.Exif
import app.grapheneos.camera.clearExif
import app.grapheneos.camera.fixExif
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * Fork: the JPEG of a photo requested by another app (ACTION_IMAGE_CAPTURE), prepared as ImageSaver prepares the
 * photos the camera saves itself: the camera's JPEG is kept without being encoded again, with its EXIF (unless the
 * setting to remove it is on), the orientation, and the mirroring of front camera photos when they are saved as
 * previewed. Upstream decoded it into a bitmap and encoded it again, without any EXIF and never mirrored.
 *
 * The [image] is not closed here.
 */
@SuppressLint("RestrictedApi")
fun captureIntentJpeg(
    image: ImageProxy,
    jpegQuality: Int,
    metadata: ImageCapture.Metadata,
    removeExif: Boolean,
    captureTime: Date,
): ByteArray {
    // Same steps as ImageSaver.extractJpegBytes()
    val cropRect: Rect? = if (ImageUtil.shouldCropImage(image)) image.cropRect else null
    val uncroppedJpegBytes = when (image.format) {
        ImageFormat.JPEG -> withoutTrailingPadding(ImageUtil.jpegImageToJpegByteArray(image))
        ImageFormat.YUV_420_888 -> ImageUtil.yuvImageToJpegByteArray(image, cropRect, jpegQuality, 0)
        else -> throw IllegalStateException("unknown imageFormat ${image.format}")
    }
    val shouldUseExifOrientation = ExifRotationAvailability().shouldUseExifOrientation(image)
    val orientation = image.imageInfo.rotationDegrees

    // Same steps as ImageSaver.saveImageInner() and processExif()
    val jpegBytes = if (cropRect != null) {
        val cropJpegByteArray = ImageUtil::class.java.getDeclaredMethod(
            "cropJpegByteArray",
            ByteArray::class.java, Rect::class.java, Int::class.javaPrimitiveType)
        cropJpegByteArray.isAccessible = true
        cropJpegByteArray.invoke(null, uncroppedJpegBytes, cropRect, jpegQuality) as ByteArray
    } else {
        uncroppedJpegBytes
    }

    val exif = Exif.createFromInputStream(ByteArrayInputStream(jpegBytes))
    if (cropRect != null) {
        Exif.createFromInputStream(ByteArrayInputStream(uncroppedJpegBytes)).copyToCroppedImage(exif)
    }
    if (!shouldUseExifOrientation) {
        exif.rotate(orientation)
    }
    if (metadata.isReversedHorizontal) {
        exif.flipHorizontally()
    }
    if (metadata.isReversedVertical) {
        exif.flipVertically()
    }
    if (removeExif) {
        exif.exifInterface.clearExif()
    } else {
        exif.exifInterface.fixExif(captureTime)
    }
    // As in ImageSaver, the location ignores the setting to remove the EXIF
    metadata.location?.let { exif.attachLocation(it) }

    val output = ByteArrayOutputStream(jpegBytes.size + 100 * 1024)
    exif.exifInterface.saveAttributes(ByteArrayInputStream(jpegBytes), output)
    return output.toByteArray()
}

// Same as ImageSaver.withoutTrailingPadding()
private fun withoutTrailingPadding(jpeg: ByteArray): ByteArray {
    val length = jpeg.indexOfLast { it != 0.toByte() } + 1
    if (length == jpeg.size) {
        return jpeg
    }
    val endsWithEoiMarker = length >= 2 && jpeg[length - 2] == 0xff.toByte() && jpeg[length - 1] == 0xd9.toByte()
    if (!endsWithEoiMarker) {
        return jpeg
    }
    return jpeg.copyOf(length)
}
