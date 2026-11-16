package app.shortcam.capture

import android.graphics.BitmapFactory
import app.shortcam.engine.CompressorFault
import kotlin.random.Random

/**
 * The compressor stage for stills: damages the bytes of an encoded JPEG, so the saved
 * file is really broken rather than a picture of a broken file.
 */
internal object JpegBender {
    private const val ATTEMPTS = 6

    /**
     * Corrupts entropy-coded bytes at the fault's hit positions. If the result will not
     * decode, tries again more gently, and gives the clean file back if nothing opens.
     */
    fun bend(jpeg: ByteArray, fault: CompressorFault): ByteArray {
        if (fault.hits.isEmpty()) return jpeg
        val scan = scanData(jpeg) ?: return jpeg
        var strength = fault.strength
        var hits = fault.hits
        repeat(ATTEMPTS) { attempt ->
            val out = jpeg.copyOf()
            val rnd = Random(fault.seed + attempt)
            for (hit in hits) {
                // Keep clear of the very start, so the top of the frame survives as it does live.
                val base = scan.first + ((0.03f + 0.96f * hit) * (scan.last - scan.first)).toInt()
                var at = base
                repeat(if (strength > 0.8f) 2 else 1) {
                    damage(out, at.coerceIn(scan.first + 1, scan.last - 1), rnd)
                    at += rnd.nextInt(1, 48)
                }
            }
            if (opens(out)) return out
            strength *= 0.6f
            if (hits.size > 1) hits = hits.dropLast(1)
        }
        return jpeg
    }

    /** True if the bytes decode to a picture. */
    fun opens(jpeg: ByteArray): Boolean {
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            null
        } ?: return false
        bitmap.recycle()
        return true
    }

    /**
     * Overwrites one byte of scan data. Never writes 0xFF and never touches a 0xFF or
     * the byte after one, so no marker is created or destroyed and the file's structure
     * stays intact. Only the picture data is wrong.
     */
    private fun damage(bytes: ByteArray, index: Int, rnd: Random) {
        val ff = 0xFF.toByte()
        if (bytes[index] == ff || bytes[index - 1] == ff) return
        // Mostly a single flipped bit, which shifts and tints what follows; now and then
        // a whole wrong byte, which wrecks it.
        bytes[index] = if (rnd.nextFloat() < 0.2f) {
            rnd.nextInt(0, 0xFF).toByte()
        } else {
            (bytes[index].toInt() xor (1 shl rnd.nextInt(8))).toByte().takeIf { it != ff } ?: 0.toByte()
        }
    }

    /** Index range of the entropy-coded data: after the first scan header, up to the end marker. */
    private fun scanData(jpeg: ByteArray): IntRange? {
        fun u(i: Int) = jpeg[i].toInt() and 0xFF
        if (jpeg.size < 4 || u(0) != 0xFF || u(1) != 0xD8) return null
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (u(pos) != 0xFF) return null
            val marker = u(pos + 1)
            val length = (u(pos + 2) shl 8) or u(pos + 3)
            if (marker == 0xDA) {
                val start = pos + 2 + length
                var end = jpeg.size - 2
                while (end > start && !(u(end) == 0xFF && u(end + 1) == 0xD9)) end--
                return if (end - start > 64) start until end else null
            }
            pos += 2 + length
        }
        return null
    }
}
