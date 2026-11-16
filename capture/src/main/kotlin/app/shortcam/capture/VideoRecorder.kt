package app.shortcam.capture

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Records whatever is drawn to [inputSurface] as H.264, with microphone audio as AAC
 * if [withAudio] is set (the caller must hold RECORD_AUDIO), into an MP4 in the Short
 * album of the phone's video storage.
 *
 * Draw frames to [inputSurface] with presentation times from System.nanoTime(), which
 * is the clock the audio is stamped with.
 */
class VideoRecorder(private val context: Context, width: Int, height: Int, withAudio: Boolean) {
    private val thread = HandlerThread("short-recorder").apply { start() }
    private val handler = Handler(thread.looper)

    private val uri: Uri
    private val file: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val video: MediaCodec
    private var audio: MediaCodec? = null
    private var microphone: AudioRecord? = null

    // Touched only on the recorder thread.
    private val tracks = HashMap<MediaCodec, Int>()
    private val waiting = ArrayList<Triple<MediaCodec, ByteBuffer, MediaCodec.BufferInfo>>()
    private var muxing = false
    private var trackCount = 1
    private var lastAudioMicros = 0L

    @Volatile
    private var stopping = false
    private val finished: CountDownLatch

    /** The encoder's input. Valid until [stop]. */
    val inputSurface: Surface

    init {
        val name = "SHORT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + Stills.ALBUM)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore insert failed")
        file = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("could not open $uri")
        muxer = MediaMuxer(file.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        video.setCallback(Callback(), handler)
        video.configure(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 10)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        inputSurface = video.createInputSurface()

        if (withAudio) setUpAudio()
        finished = CountDownLatch(trackCount)
    }

    @SuppressLint("MissingPermission")
    private fun setUpAudio() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val mic = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4,
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            null
        }
        if (mic == null) {
            Log.w(TAG, "microphone unavailable; recording without sound")
            return
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.setCallback(Callback(), handler)
        codec.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        microphone = mic
        audio = codec
        trackCount = 2
    }

    fun start() {
        microphone?.startRecording()
        video.start()
        audio?.start()
    }

    /**
     * Finishes the file and publishes it. Nothing may be drawing to [inputSurface] when
     * this is called. Returns the saved video, or null if it could not be finished.
     */
    fun stop(): Uri? {
        stopping = true
        runCatching { video.signalEndOfInputStream() }
        val complete = finished.await(4, TimeUnit.SECONDS)

        val closed = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = runCatching { if (muxing) muxer.stop() }.isSuccess && muxing && complete
            runCatching { muxer.release() }
            runCatching { video.stop() }
            video.release()
            audio?.let { runCatching { it.stop() }; it.release() }
            microphone?.let { runCatching { it.stop() }; it.release() }
            inputSurface.release()
            runCatching { file.close() }
            closed.countDown()
        }
        closed.await(4, TimeUnit.SECONDS)
        thread.quitSafely()

        val resolver = context.contentResolver
        return if (ok) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Only the audio encoder has input buffers; video comes in through the surface.
            val mic = microphone ?: return
            val buffer = codec.getInputBuffer(index) ?: return
            if (stopping) {
                codec.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            val read = mic.read(buffer, minOf(buffer.capacity(), 4096)).coerceAtLeast(0)
            // Stamp the chunk with the time its first sample was captured.
            // The muxer rejects audio that goes backwards in time, so never let it.
            val micros = maxOf(System.nanoTime() / 1000 - read / 2 * 1_000_000L / SAMPLE_RATE, lastAudioMicros + 1)
            lastAudioMicros = micros
            codec.queueInputBuffer(index, 0, read, micros, 0)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            tracks[codec] = muxer.addTrack(format)
            if (tracks.size == trackCount) {
                muxer.start()
                muxing = true
                for ((owner, data, info) in waiting) muxer.writeSampleData(tracks.getValue(owner), data, info)
                waiting.clear()
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val buffer = codec.getOutputBuffer(index)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buffer != null && info.size > 0 && !isConfig) {
                if (muxing) {
                    muxer.writeSampleData(tracks.getValue(codec), buffer, info)
                } else {
                    // The muxer cannot start until every track has announced its format.
                    val bytes = ByteArray(info.size)
                    (buffer as java.nio.Buffer).position(info.offset)
                    buffer.get(bytes)
                    val copy = ByteBuffer.wrap(bytes)
                    val kept = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                    waiting += Triple(codec, copy, kept)
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finished.countDown()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
            finished.countDown()
        }
    }

    private companion object {
        const val TAG = "ShortRecorder"
        const val SAMPLE_RATE = 44_100
    }
}
