package app.shortcam

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.shortcam.bodies.ShutterSpec
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A body's shutter sound, synthesised from its package: two bursts of mechanical noise
 * (the shutter opening and closing) and, for electronic cameras, a short beep.
 */
class ShutterSound(spec: ShutterSpec) {
    private val track: AudioTrack

    init {
        val count = (spec.length * RATE).toInt().coerceAtLeast(256)
        val rnd = Random(count)
        val samples = ShortArray(count) { i ->
            val t = i / RATE.toFloat()
            val open = exp(-t / (spec.length * 0.12f))
            val closeAt = spec.length * 0.55f
            val close = if (t > closeAt) exp(-(t - closeAt) / (spec.length * 0.1f)) else 0f
            var s = (rnd.nextFloat() * 2f - 1f) * spec.noise * (open + 0.8f * close)
            if (spec.beep > 0f && t < spec.length * 0.6f) {
                s += 0.3f * sin(2.0 * PI * spec.beep * t).toFloat()
            }
            (s.coerceIn(-1f, 1f) * 0.8f * Short.MAX_VALUE).toInt().toShort()
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(count * 2)
            .build()
        track.write(samples, 0, count)
    }

    fun play() {
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() = track.release()

    private companion object {
        const val RATE = 44_100
    }
}
