package app.shortcam.capture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writes GIFs and reads them back with the JDK's own decoder. */
class GifWriterTest {
    private val width = 160
    private val height = 120

    private fun frame(pixel: (x: Int, y: Int) -> Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = pixel(x, y)
            buffer.put((rgb shr 16).toByte()).put((rgb shr 8).toByte()).put(rgb.toByte()).put(0xFF.toByte())
        }
        buffer.rewind()
        return buffer
    }

    private fun decode(bytes: ByteArray): List<IntArray> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        return List(reader.getNumImages(true)) { i ->
            val image = reader.read(i)
            assertEquals(width, image.width)
            assertEquals(height, image.height)
            image.getRGB(0, 0, width, height, null, 0, width)
        }
    }

    private fun worstError(expected: (Int, Int) -> Int, actual: IntArray): Int {
        var worst = 0
        for (y in 0 until height) for (x in 0 until width) {
            val e = expected(x, y)
            val a = actual[y * width + x]
            for (shift in intArrayOf(16, 8, 0)) {
                worst = maxOf(worst, abs((e shr shift and 0xFF) - (a shr shift and 0xFF)))
            }
        }
        return worst
    }

    @Test
    fun fewColoursComeBackAlmostExactly() {
        // Fewer than 256 colours: each gets its own palette entry, off only by the 5-bit binning.
        val bars: (Int, Int) -> Int = { x, y -> ((x / 20) * 36 shl 16) or ((y / 20) * 40 shl 8) or 0x80 }
        val moved: (Int, Int) -> Int = { x, y -> bars((x + 40) % width, y) }
        val out = ByteArrayOutputStream()
        val writer = GifWriter(out, width, height, 10)
        writer.addFrame(frame(bars))
        writer.addFrame(frame(moved))
        writer.finish()

        val frames = decode(out.toByteArray())
        assertEquals(2, frames.size)
        assertTrue(worstError(bars, frames[0]) <= 4)
        assertTrue(worstError(moved, frames[1]) <= 4)
    }

    @Test
    fun noiseFillsTheCodeTableAndStillDecodes() {
        // Random pixels force the code table to fill and restart many times in one frame.
        val rnd = Random(7)
        val noise = IntArray(width * height) { rnd.nextInt(0x1000000) }
        val out = ByteArrayOutputStream()
        val writer = GifWriter(out, width, height, 10)
        repeat(3) { writer.addFrame(frame { x, y -> noise[y * width + x] }) }
        writer.finish()

        val frames = decode(out.toByteArray())
        assertEquals(3, frames.size)
        // 256 colours cannot hold noise exactly, but every pixel must land near its own colour.
        var total = 0L
        for (i in noise.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                total += abs((noise[i] shr shift and 0xFF) - (frames[2][i] shr shift and 0xFF))
            }
        }
        assertTrue("mean error ${total / (noise.size * 3)}", total / (noise.size * 3) < 40)
    }

    @Test
    fun smoothGradientStaysClose() {
        val gradient: (Int, Int) -> Int = { x, y -> (x * 255 / width shl 16) or (y * 255 / height shl 8) or ((x + y) and 0xFF) }
        val out = ByteArrayOutputStream()
        val writer = GifWriter(out, width, height, 10)
        writer.addFrame(frame(gradient))
        writer.finish()
        assertTrue(worstError(gradient, decode(out.toByteArray())[0]) < 64)
    }
}
