package app.shortcam.capture

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Writes an endlessly looping animated GIF, one frame at a time. Every frame gets its
 * own 256-colour palette, picked by median cut, so a frame full of wrong colours keeps
 * them. Call [addFrame] for each frame, then [finish].
 */
class GifWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    /** Time each frame is shown, in hundredths of a second. */
    private val delay: Int,
) {
    var frames = 0
        private set

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width)
        short(height)
        out.write(0x70)                 // no global palette, 8 bits per channel
        out.write(0)
        out.write(0)
        // Loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    /** Adds a frame of tightly packed RGBA rows, top row first, of the size given at construction. */
    fun addFrame(rgba: ByteBuffer) {
        val count = width * height
        require(rgba.capacity() >= count * 4) { "frame is smaller than ${width}x$height" }

        // Colours are binned to 5 bits a channel; the palette is chosen from the bins.
        val bins = IntArray(count)
        val histogram = IntArray(BINS)
        for (i in 0 until count) {
            val r = rgba.get(i * 4).toInt() and 0xFF
            val g = rgba.get(i * 4 + 1).toInt() and 0xFF
            val b = rgba.get(i * 4 + 2).toInt() and 0xFF
            val bin = (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
            bins[i] = bin
            histogram[bin]++
        }
        val palette = ByteArray(256 * 3)
        val lookup = medianCut(histogram, palette)
        val indices = ByteArray(count) { lookup[bins[it]] }

        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))    // keep each frame in place
        short(delay)
        out.write(0)
        out.write(0)
        out.write(0x2C)
        short(0)
        short(0)
        short(width)
        short(height)
        out.write(0x87)                 // local palette of 256
        out.write(palette)
        compress(indices)
        frames++
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun short(v: Int) {
        out.write(v and 0xFF)
        out.write(v shr 8 and 0xFF)
    }

    /**
     * Splits the used colours into at most 256 boxes, always cutting the box that
     * spreads the most pixels over the widest range, and fills [palette] with each
     * box's average. Returns the palette index for every colour bin.
     */
    private fun medianCut(histogram: IntArray, palette: ByteArray): ByteArray {
        val colors = (0 until BINS).filter { histogram[it] > 0 }.toIntArray()
        val keys = LongArray(colors.size)

        class Box(val from: Int, val to: Int) {
            var axis = 0
            var score = 0L
            var pixels = 0L

            init {
                val lo = intArrayOf(31, 31, 31)
                val hi = intArrayOf(0, 0, 0)
                for (i in from until to) {
                    val c = colors[i]
                    pixels += histogram[c]
                    for (a in 0..2) {
                        val v = c shr SHIFTS[a] and 31
                        if (v < lo[a]) lo[a] = v
                        if (v > hi[a]) hi[a] = v
                    }
                }
                var widest = 0
                for (a in 0..2) {
                    if (hi[a] - lo[a] > widest) {
                        widest = hi[a] - lo[a]
                        axis = a
                    }
                }
                score = if (to - from < 2) 0L else pixels * widest
            }
        }

        val boxes = ArrayList<Box>()
        boxes += Box(0, colors.size)
        while (boxes.size < 256) {
            val box = boxes.maxByOrNull { it.score } ?: break
            if (box.score == 0L) break
            // Sort the box along its widest channel and cut where half the pixels lie on each side.
            for (i in box.from until box.to) {
                val c = colors[i]
                keys[i] = ((c shr SHIFTS[box.axis] and 31).toLong() shl 32) or c.toLong()
            }
            keys.sort(box.from, box.to)
            for (i in box.from until box.to) colors[i] = keys[i].toInt()
            var seen = 0L
            var cut = box.from + 1
            for (i in box.from until box.to - 1) {
                seen += histogram[colors[i]]
                cut = i + 1
                if (seen * 2 >= box.pixels) break
            }
            boxes.remove(box)
            boxes += Box(box.from, cut)
            boxes += Box(cut, box.to)
        }

        val lookup = ByteArray(BINS)
        boxes.forEachIndexed { index, box ->
            var r = 0L
            var g = 0L
            var b = 0L
            for (i in box.from until box.to) {
                val c = colors[i]
                val n = histogram[c]
                // The middle of each 5-bit bin, back in 8 bits.
                r += n * ((c shr 10 and 31) * 8 + 4L)
                g += n * ((c shr 5 and 31) * 8 + 4L)
                b += n * ((c and 31) * 8 + 4L)
                lookup[c] = index.toByte()
            }
            val total = box.pixels.coerceAtLeast(1)
            palette[index * 3] = (r / total).toByte()
            palette[index * 3 + 1] = (g / total).toByte()
            palette[index * 3 + 2] = (b / total).toByte()
        }
        return lookup
    }

    /** GIF's LZW: variable-width codes, least significant bit first, in blocks of up to 255 bytes. */
    private fun compress(indices: ByteArray) {
        val block = ByteArray(255)
        var blockSize = 0
        var acc = 0
        var bits = 0
        var codeSize = 9
        var next = FIRST_CODE
        var resetSize = false

        fun emit(code: Int) {
            acc = acc or (code shl bits)
            bits += codeSize
            while (bits >= 8) {
                block[blockSize++] = acc.toByte()
                acc = acc ushr 8
                bits -= 8
                if (blockSize == 255) {
                    out.write(255)
                    out.write(block, 0, 255)
                    blockSize = 0
                }
            }
            if (resetSize) {
                codeSize = 9
                resetSize = false
            } else if (next >= (1 shl codeSize) && codeSize < 12) {
                codeSize++
            }
        }

        val keys = IntArray(TABLE) { -1 }
        val codes = IntArray(TABLE)

        out.write(8)
        emit(CLEAR)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var slot = (key * -1640531535) ushr (32 - TABLE_BITS)
            while (keys[slot] != -1 && keys[slot] != key) slot = (slot + 1) and (TABLE - 1)
            if (keys[slot] == key) {
                prefix = codes[slot]
                continue
            }
            emit(prefix)
            if (next < 4096) {
                keys[slot] = key
                codes[slot] = next++
            } else {
                // The code table is full: start it again.
                keys.fill(-1)
                next = FIRST_CODE
                resetSize = true
                emit(CLEAR)
            }
            prefix = c
        }
        emit(prefix)
        emit(CLEAR + 1)
        if (bits > 0) block[blockSize++] = acc.toByte()
        if (blockSize > 0) {
            out.write(blockSize)
            out.write(block, 0, blockSize)
        }
        out.write(0)
    }

    private companion object {
        const val BINS = 32 * 32 * 32
        val SHIFTS = intArrayOf(10, 5, 0)
        const val CLEAR = 256
        const val FIRST_CODE = 258
        const val TABLE_BITS = 13
        const val TABLE = 1 shl TABLE_BITS
    }
}
