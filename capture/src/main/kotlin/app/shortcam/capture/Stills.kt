package app.shortcam.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.shortcam.engine.StillFrame
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.ExifInterface
import java.nio.ByteBuffer

/** Who made a photo: the camera body, and a description of the patch that was on its board. */
class StillMeta(val make: String, val model: String, val description: String)

object Stills {
    const val ALBUM = "Short"
    private const val HEALTHY_QUALITY = 92

    /**
     * Turns a rendered frame into the bytes of the saved photo. The frame is encoded in
     * sensor orientation with an orientation tag, as a real camera does, then the
     * compressor fault is applied to the encoded bytes. The result always decodes.
     */
    fun encode(frame: StillFrame, meta: StillMeta? = null): ByteArray {
        val fault = frame.compressor
        // Wrecked quantisation tables are the real thing here: the encoder is told to throw detail away.
        val quality = if (fault.quantise > 0f) (30f - 28f * fault.quantise).toInt().coerceIn(1, 30) else HEALTHY_QUALITY
        val clean = encodeJpeg(frame, quality)
        val bent = JpegBender.bend(clean, fault)
        val exif = exifSegment(frame.turn, meta) ?: return bent
        return bent.copyOfRange(0, 2) + exif + bent.copyOfRange(2, bent.size)
    }

    /**
     * The patch description stored in a Short photo, or null if the image has none.
     * This is whatever was passed as [StillMeta.description] when it was taken.
     */
    fun readDescription(context: Context, photo: Uri): String? = try {
        context.contentResolver.openInputStream(photo)?.use { stream ->
            ExifInterface(stream).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
        }?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }

    private fun encodeJpeg(frame: StillFrame, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            frame.rgba.rewind()
            bitmap.copyPixelsFromBuffer(frame.rgba)
            val out = ByteArrayOutputStream(frame.width * frame.height / 4)
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encode failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Builds the Exif segment that goes right after the start-of-image marker: the
     * orientation, and the camera and patch that made the photo. [turn] is the
     * counter-clockwise turn that makes the image upright. Null if there is nothing to store.
     */
    private fun exifSegment(turn: Int, meta: StillMeta?): ByteArray? {
        class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

        fun ascii(tag: Int, text: String): Entry {
            val bytes = text.toByteArray(Charsets.UTF_8) + 0.toByte()
            return Entry(tag, 2, bytes.size, bytes)
        }

        // Entries must be in tag order.
        val entries = ArrayList<Entry>()
        if (meta != null) {
            entries += ascii(0x010E, meta.description)
            entries += ascii(0x010F, meta.make)
            entries += ascii(0x0110, meta.model)
        }
        val orientation = when (turn) {
            90 -> 8   // viewer rotates 270 clockwise
            180 -> 3
            270 -> 6  // viewer rotates 90 clockwise
            else -> 0
        }
        if (orientation != 0) entries += Entry(0x0112, 3, 1, byteArrayOf(0, orientation.toByte()))
        if (entries.isEmpty()) return null

        val directorySize = 2 + 12 * entries.size + 4
        val directory = ByteBuffer.allocate(8 + directorySize)        // big-endian by default
        directory.put(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))
        directory.putInt(8)                                           // first directory at 8
        directory.putShort(entries.size.toShort())
        val values = ByteArrayOutputStream()
        for (e in entries) {
            directory.putShort(e.tag.toShort())
            directory.putShort(e.type.toShort())
            directory.putInt(e.count)
            if (e.data.size <= 4) {
                directory.put(e.data.copyOf(4))
            } else {
                // Too big to sit in the entry: stored after the directory, at an even offset.
                directory.putInt(8 + directorySize + values.size())
                values.write(e.data)
                if (e.data.size and 1 == 1) values.write(0)
            }
        }
        directory.putInt(0)                                           // no further directory

        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + directory.array() + values.toByteArray()
        val length = body.size + 2
        if (length > 0xFFFF) return null
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    /**
     * Writes an encoded image into the Short album in shared photo storage and returns
     * the new item. It is a JPEG unless [extension] and [mime] say otherwise.
     */
    fun saveToAlbum(
        context: Context,
        jpeg: ByteArray,
        takenAt: Date = Date(),
        suffix: String = "",
        extension: String = "jpg",
        mime: String = "image/jpeg",
    ): Uri {
        val name = "SHORT_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(takenAt) + suffix + "." + extension
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt.time)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) }
                ?: throw IOException("could not open $uri")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
