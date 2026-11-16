package app.shortcam.engine

import kotlin.math.floor

/**
 * What the compressor stage does to a still's encoded file. Worked out with the same
 * arithmetic as the live approximation in [Shaders.COMPRESSOR], so the photo is hit at
 * the same moments, and at about the same places down the frame, as the viewfinder showed.
 */
class CompressorFault(
    /** How badly the quantisation tables are wrecked, 0 for healthy. */
    val quantise: Float,
    /** Where in the encoded stream bytes get corrupted, each 0..1 from start to end. Empty for none. */
    val hits: List<Float>,
    /** How hard each hit is, 0..1. */
    val strength: Float,
    /** Seed for the corruption, fixed for the frame. */
    val seed: Long,
) {
    val active: Boolean get() = quantise > 0f || hits.isNotEmpty()

    internal companion object {
        // These mirror the constants in Shaders.COMPRESSOR and the `bite` function. Keep them in step.
        private const val CORRUPT_SEED = 163f
        private const val CORRUPT_RATE = 4f
        private const val QUANT_SEED = 173f
        private const val QUANT_RATE = 3f

        fun at(time: Float, blockCorrupt: Float, quant: Float): CompressorFault {
            val slot = floor(time * CORRUPT_RATE)
            val a = bite(time, blockCorrupt, CORRUPT_SEED, CORRUPT_RATE)
            val hits = ArrayList<Float>()
            if (a > 0f) {
                for (i in 0 until 4) {
                    if (i / 4f >= a) break
                    hits += hash21(slot, 50f + i)
                }
            }
            return CompressorFault(
                quantise = bite(time, quant, QUANT_SEED, QUANT_RATE),
                hits = hits,
                strength = a,
                seed = (time * 1000f).toLong(),
            )
        }

        private fun fract(x: Float) = x - floor(x)

        private fun hash21(x: Float, y: Float): Float {
            var px = fract(x * 0.1031f)
            var py = fract(y * 0.1031f)
            var pz = fract(x * 0.1031f)
            val d = px * (py + 33.33f) + py * (pz + 33.33f) + pz * (px + 33.33f)
            px += d
            py += d
            pz += d
            return fract((px + py) * pz)
        }

        private fun bite(time: Float, amount: Float, seed: Float, rate: Float): Float {
            if (amount <= 0f) return 0f
            val duty = (amount * 2.2f).coerceIn(0f, 1f)
            return if (hash21(floor(time * rate), seed) < duty) 0.2f + 0.8f * amount else 0f
        }
    }
}
