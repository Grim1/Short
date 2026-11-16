package app.shortcam.engine

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.shortcam.engine.gl.EglCore
import app.shortcam.engine.gl.FrameChain
import app.shortcam.engine.gl.Framebuffer
import app.shortcam.engine.gl.Program
import app.shortcam.engine.gl.Quad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Base settings for the simulated camera, in sensor (landscape) orientation. These come
 * from the body, never from the engine.
 *
 * The preview size is also the fault grid: faults quantise to it, so the viewfinder and
 * a full-resolution still show the same fault.
 */
data class EngineConfig(
    val sensorWidth: Int,
    val sensorHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    /** Stages this body has. Order is fixed by [StageId]. */
    val stages: Set<StageId> = StageId.entries.toSet(),
    /** 0 is hopelessly flaky, 1 is rock solid. Sets how far bends drift and how easily the camera crashes. */
    val stability: Float = 0.8f,
    /** Bits per channel the A/D converter resolves when healthy, 1 to 8. */
    val bitDepth: Int = 8,
    /** How far a healthy colour encoder smears chroma sideways, 0 for a clean digital one, 1 for cheap analogue. */
    val chromaSoftness: Float = 0f,
)

/**
 * A rendered still in sensor orientation: tightly packed RGBA rows, top row first.
 * The compressor stage is not in the pixels. It is applied to the encoded file,
 * as [compressor] describes.
 */
class StillFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteBuffer,
    /** Counter-clockwise turn, in degrees, that makes the frame upright. */
    val turn: Int,
    val compressor: CompressorFault,
)

/**
 * The simulated camera's signal chain. Owns a GL thread, takes camera frames through a
 * SurfaceTexture, runs them through the stage passes, and presents the result to a
 * window surface. All public methods are safe to call from any thread.
 *
 * The chain always runs in sensor orientation. When the camera is held in portrait the
 * sensor is sideways, as in a real camera, and the frame is turned upright only when
 * it is shown or saved. Scan-line faults therefore run vertically in portrait photos.
 */
class Engine(private val config: EngineConfig) {

    private class Stage(val def: StageDef, val program: Program) {
        val values = FloatArray(def.params.size)

        /** [values] with drift applied, as of the last viewfinder frame. This is what the shaders see. */
        val effective = FloatArray(def.params.size)

        /** Two random phases per parameter, so every session drifts differently. */
        val driftPhase = FloatArray(def.params.size * 2) { Random.nextFloat() * 6.2832f }

        /** What this stage read as uPrev on the last viewfinder frame, and what it reads next. */
        var historyRead: Framebuffer? = null
        var historyNext: Framebuffer? = null
    }

    /** Called about once a second on the engine thread with viewfinder frames per second. */
    @Volatile
    var onFrameRate: ((Float) -> Unit)? = null

    /** Called on the engine thread when the camera crashes, and again when it has rebooted. */
    @Volatile
    var onCrash: (() -> Unit)? = null

    @Volatile
    var onReboot: (() -> Unit)? = null

    private enum class Phase { RUNNING, FROZEN, GARBLED, DARK }

    private val thread = HandlerThread("short-engine").apply { start() }
    private val handler = Handler(thread.looper)

    // Everything below is touched only on the GL thread.
    private var egl: EglCore? = null
    private var window: EGLSurface? = null
    private var windowWidth = 0
    private var windowHeight = 0

    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var quad: Quad? = null
    private var ingest: Program? = null
    private var copy: Program? = null
    private val stages = ArrayList<Stage>()
    private var previewChain: FrameChain? = null
    private var captureChain: FrameChain? = null
    private var stillTarget: Framebuffer? = null
    private var lastOutput: Framebuffer? = null

    private var bufferWidth = 0
    private var bufferHeight = 0
    private var swapped = false
    private var extraRotation = 0

    private val stMatrix = FloatArray(16)
    private val shapeMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private var hasFrame = false
    private var historyPrimed = false
    private var frameTime = 0f
    private val startNanos = System.nanoTime()
    private var rateWindowStart = 0L
    private var rateFrames = 0

    private var recording: EGLSurface? = null
    private var recordingWidth = 0
    private var recordingHeight = 0

    private class Burst(
        val target: Framebuffer,
        val intervalNanos: Long,
        var left: Int,
        val portrait: Boolean,
        val onFrame: (Int, Int, ByteBuffer) -> Unit,
        val onEnd: () -> Unit,
    ) {
        var nextAt = 0L
    }

    private var burst: Burst? = null
    private var zoom = 1f

    /** What the viewfinder last showed the output through; null for black. */
    private var shown: Program? = null

    private var garble: Program? = null
    private var phase = Phase.RUNNING
    private var phaseStart = 0L
    private var lastFrameNanos = 0L
    /** No overload crashes before this time, so wires can be pulled after a reboot. */
    private var graceUntil = 0L

    init {
        handler.post { initGl() }
    }

    /**
     * Hands out a Surface for the camera to write [width] x [height] buffers into.
     * [onSurface] runs on the engine thread; the caller releases the Surface when the
     * camera is done with it.
     */
    fun provideInputSurface(width: Int, height: Int, onSurface: (Surface) -> Unit) {
        handler.post {
            val st = surfaceTexture ?: return@post
            bufferWidth = width
            bufferHeight = height
            st.setDefaultBufferSize(width, height)
            onSurface(Surface(st))
        }
    }

    /**
     * How the camera buffer relates to the upright image. [swapped] is true when the
     * upright image has the buffer's width and height exchanged. [extraRotation] is a
     * counter-clockwise rotation in degrees applied on top of the SurfaceTexture transform.
     */
    fun setInputTransform(swapped: Boolean, extraRotation: Int) {
        handler.post {
            this.swapped = swapped
            this.extraRotation = extraRotation
        }
    }

    /**
     * Magnifies the middle of the camera image before it reaches the sensor stage.
     * 1 is the whole image. This is for whatever zoom the phone's own lens cannot give.
     */
    fun setZoom(factor: Float) {
        handler.post { zoom = factor.coerceAtLeast(1f) }
    }

    /**
     * Collects up to [frames] viewfinder frames, [interval] seconds apart, upright and
     * no larger than [maxSide] on the long edge. Each arrives on the engine thread as
     * tightly packed RGBA rows, top row first. [onEnd] runs once, after the last frame,
     * when the count is reached, [stopBurst] is called, or the camera is turned.
     * A crash during the burst is recorded as it looks.
     */
    fun startBurst(
        maxSide: Int,
        interval: Float,
        frames: Int,
        onFrame: (width: Int, height: Int, rgba: ByteBuffer) -> Unit,
        onEnd: () -> Unit,
    ) {
        val posted = handler.post {
            endBurst()
            if (!hasFrame || egl == null) {
                onEnd()
                return@post
            }
            val portrait = isPortrait()
            val w = if (portrait) config.previewHeight else config.previewWidth
            val h = if (portrait) config.previewWidth else config.previewHeight
            val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            burst = Burst(
                Framebuffer((w * scale).toInt(), (h * scale).toInt()),
                (interval * 1e9f).toLong(), frames, portrait, onFrame, onEnd,
            )
        }
        if (!posted) onEnd()
    }

    /** Ends a burst early. Does nothing if none is running. */
    fun stopBurst() {
        handler.post { endBurst() }
    }

    /** Sets one fault parameter. 0 is healthy, 1 is the hardest fault. */
    fun setFault(param: FaultParam, value: Float) {
        handler.post {
            val stage = stages.firstOrNull { it.def.id == param.stage } ?: return@post
            val index = stage.def.params.indexOf(param)
            if (index >= 0) stage.values[index] = value.coerceIn(0f, 1f)
        }
    }

    /** Sets the viewfinder window, or detaches it when [surface] is null (blocks until detached). */
    fun setOutputSurface(surface: Surface?, width: Int, height: Int) {
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                val egl = egl ?: return@post
                window?.let {
                    egl.makeCurrent(null)
                    egl.destroySurface(it)
                }
                window = null
                if (surface != null && surface.isValid) {
                    window = egl.createWindowSurface(surface)
                    windowWidth = width
                    windowHeight = height
                    egl.makeCurrent(window)
                    presentLast()
                }
            } finally {
                done.countDown()
            }
        }
        if (posted && surface == null) done.await()
    }

    /** Size of the upright viewfinder frame, which is the size video is recorded at. */
    fun frameSize(onResult: (width: Int, height: Int) -> Unit) {
        handler.post {
            if (isPortrait()) onResult(config.previewHeight, config.previewWidth)
            else onResult(config.previewWidth, config.previewHeight)
        }
    }

    /**
     * Sets a second output that receives every viewfinder frame with a timestamp, such
     * as a video encoder's input. Null detaches it and blocks until no more frames are
     * going to it.
     */
    fun setRecordingSurface(surface: Surface?, width: Int, height: Int) {
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                val egl = egl ?: return@post
                recording?.let {
                    egl.makeCurrent(window)
                    egl.destroySurface(it)
                }
                recording = null
                if (surface != null && surface.isValid) {
                    recording = egl.createWindowSurface(surface)
                    recordingWidth = width
                    recordingHeight = height
                }
            } finally {
                done.countDown()
            }
        }
        if (posted && surface == null) done.await()
    }

    /**
     * Renders the frame currently in the viewfinder at output resolution, with the same
     * time and fault state, and delivers it on the engine thread. Null if no camera
     * frame has arrived yet.
     */
    fun captureStill(alsoClean: Boolean = false, onResult: (bent: StillFrame?, clean: StillFrame?) -> Unit) {
        val posted = handler.post {
            if (hasFrame && phase == Phase.RUNNING) {
                // The clean copy is the same frame through the same camera with nothing bent.
                onResult(renderStill(healthy = false), if (alsoClean) renderStill(healthy = true) else null)
            } else {
                onResult(null, null)
            }
        }
        if (!posted) onResult(null, null)
    }

    /** Crashes the camera now: the frame freezes, garbles, and the body reboots. Faults are kept. */
    fun crash() {
        handler.post { if (phase == Phase.RUNNING && hasFrame) beginCrash() }
    }

    fun release() {
        handler.post { releaseGl() }
        thread.quitSafely()
        thread.join(2000)
    }

    // ---- GL thread ----

    private fun initGl() {
        egl = EglCore()
        quad = Quad()
        ingest = Program(Shaders.TRANSFORM_VERTEX, Shaders.INGEST_FRAGMENT)
        copy = Program(Shaders.TRANSFORM_VERTEX, Shaders.COPY_FRAGMENT)
        garble = Program(Shaders.TRANSFORM_VERTEX, Shaders.GARBLE_FRAGMENT)
        stillTarget = Framebuffer(config.sensorWidth, config.sensorHeight)

        previewChain = FrameChain(config.previewWidth, config.previewHeight)
        captureChain = FrameChain(config.sensorWidth, config.sensorHeight)
        for (def in Faults.STAGES) {
            if (def.id !in config.stages) continue
            val stage = Stage(def, Program(Shaders.QUAD_VERTEX, def.fragment))
            if (def.usesHistory) {
                stage.historyRead = Framebuffer(config.previewWidth, config.previewHeight).also { it.clear() }
                stage.historyNext = Framebuffer(config.previewWidth, config.previewHeight).also { it.clear() }
            }
            stages += stage
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTexture).apply {
            setOnFrameAvailableListener({ onCameraFrame() }, handler)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    private fun onCameraFrame() {
        val st = surfaceTexture ?: return
        if (bufferWidth == 0) return
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1e9f
        lastFrameNanos = now

        if (phase != Phase.RUNNING) {
            runCrash(now)
            burstTick(now)
            return
        }

        frameTime = (now - startNanos) / 1e9f
        hasFrame = true
        applyDrift()
        lastOutput = renderChain(previewChain!!, advanceHistory = true)
        presentLast()
        burstTick(now)

        // Overloaded, the camera gets likelier to fall over the longer it runs.
        if (now > graceUntil && Random.nextFloat() < crashRate() * dt) beginCrash()

        rateFrames++
        if (now - rateWindowStart >= 1_000_000_000L) {
            if (rateWindowStart != 0L) onFrameRate?.invoke(rateFrames * 1e9f / (now - rateWindowStart))
            rateWindowStart = now
            rateFrames = 0
        }
    }

    /**
     * Bends drift: each active fault wanders slowly around the value it was set to, so
     * the same patch never looks the same twice. A still reuses the drifted values of
     * the viewfinder frame it reproduces.
     */
    private fun applyDrift() {
        val depth = 0.1f + 0.4f * (1f - config.stability)
        for (stage in stages) {
            for (i in stage.values.indices) {
                val v = stage.values[i]
                stage.effective[i] = if (v <= 0f) 0f else {
                    val wander = 0.6f * sin(frameTime * 0.23f + stage.driftPhase[i * 2]) +
                        0.4f * sin(frameTime * 0.071f + stage.driftPhase[i * 2 + 1])
                    (v * (1f + depth * wander)).coerceIn(0.02f, 1f)
                }
            }
        }
    }

    /** Crashes per second at the current load. Zero until the load passes what the body can take. */
    private fun crashRate(): Float {
        var load = 0f
        for (stage in stages) {
            // Bending the clock or the memory strains the camera more than bending the picture.
            val weight = if (stage.def.id == StageId.SYNC || stage.def.id == StageId.MEMORY) 1.5f else 1f
            for (v in stage.effective) load += v * weight
        }
        val capacity = 1.5f + 3f * config.stability
        return ((load - capacity) * 0.25f).coerceAtLeast(0f)
    }

    /** Takes the next burst frame if one is due: whatever the viewfinder is showing right now. */
    private fun burstTick(now: Long) {
        val b = burst ?: return
        if (b.portrait != isPortrait()) {
            endBurst()
            return
        }
        // Camera frames arrive with some jitter; a frame slightly early is the one to take.
        if (now < b.nextAt - BURST_SLACK_NANOS) return
        b.nextAt = if (b.nextAt == 0L) now + b.intervalNanos else maxOf(b.nextAt + b.intervalNanos, now)

        val target = b.target
        target.clear()
        val out = lastOutput
        val program = shown
        if (out != null && program != null) drawUpright(out, flipY = true, program = program)
        val pixels = ByteBuffer.allocateDirect(target.width * target.height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, target.width, target.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        b.onFrame(target.width, target.height, pixels)
        if (--b.left <= 0) endBurst()
    }

    private fun endBurst() {
        val b = burst ?: return
        burst = null
        b.target.release()
        b.onEnd()
    }

    private fun beginCrash() {
        phase = Phase.FROZEN
        phaseStart = System.nanoTime()
        onCrash?.invoke()
    }

    /** Freeze on the last frame, garble it, go dark, then come back up with faults intact. */
    private fun runCrash(now: Long) {
        val elapsed = (now - phaseStart) / 1e9f
        phase = when {
            elapsed < FREEZE_SECONDS -> Phase.FROZEN
            elapsed < FREEZE_SECONDS + GARBLE_SECONDS -> Phase.GARBLED
            elapsed < FREEZE_SECONDS + GARBLE_SECONDS + DARK_SECONDS -> Phase.DARK
            else -> Phase.RUNNING
        }
        when (phase) {
            Phase.FROZEN -> Unit
            Phase.GARBLED -> {
                frameTime = (now - startNanos) / 1e9f
                presentLast(garble!!)
            }
            Phase.DARK -> presentBlack()
            Phase.RUNNING -> {
                // Memory does not survive a reboot.
                historyPrimed = false
                graceUntil = now + GRACE_NANOS
                onReboot?.invoke()
            }
        }
    }

    private fun renderStill(healthy: Boolean): StillFrame {
        val out = renderChain(captureChain!!, advanceHistory = false, forStill = true, healthy = healthy)

        // Stays in sensor orientation, flipped so GL's bottom-up rows read out top row first.
        val target = stillTarget!!
        target.bind()
        drawUpright(out, flipY = true, turn = false)
        val pixels = ByteBuffer.allocateDirect(target.width * target.height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, target.width, target.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()

        val compressor = stages.firstOrNull { it.def.id == StageId.COMPRESSOR }
        fun amount(key: String) = compressor?.let { s ->
            s.def.params.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { s.effective[it] }
        } ?: 0f
        return StillFrame(
            target.width, target.height, pixels,
            turn = if (isPortrait()) PORTRAIT_TURN.toInt() else 0,
            compressor = if (healthy) CompressorFault.at(frameTime, 0f, 0f)
            else CompressorFault.at(frameTime, amount("uBlockCorrupt"), amount("uQuant")),
        )
    }

    /**
     * Runs ingest plus every stage pass into [chain] and returns the target holding the
     * result. The viewfinder render advances each stage's history; a still re-reads the
     * history the viewfinder frame saw, so it reproduces that frame.
     */
    private fun renderChain(
        chain: FrameChain,
        advanceHistory: Boolean,
        forStill: Boolean = false,
        healthy: Boolean = false,
    ): Framebuffer {
        val quad = quad!!
        val ingest = ingest!!
        updateIngestMatrix()

        var dst = chain.a
        var spare = chain.b
        dst.bind()
        ingest.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(ingest.uniform("uTex"), 0)
        GLES30.glUniformMatrix4fv(ingest.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        quad.draw()

        for (stage in stages) {
            // A still gets the real compressor, on its encoded bytes, not the live approximation.
            if (forStill && stage.def.id == StageId.COMPRESSOR) continue
            val src = dst
            dst = spare
            spare = src
            dst.bind()
            val p = stage.program
            p.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.texture)
            GLES30.glUniform1i(p.uniform("uTex"), 0)
            // After a viewfinder frame the buffers are swapped, so the history that
            // frame read is in historyNext; a still reads that one.
            (if (advanceHistory) stage.historyRead else stage.historyNext)?.let {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                // On the first frame there is no history yet, so the stage sees its own input.
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (historyPrimed) it.texture else src.texture)
                GLES30.glUniform1i(p.uniform("uPrev"), 1)
            }
            GLES30.glUniform2f(p.uniform("uGrid"), config.previewWidth.toFloat(), config.previewHeight.toFloat())
            GLES30.glUniform1f(p.uniform("uBaseBits"), config.bitDepth.toFloat())
            GLES30.glUniform1f(p.uniform("uBaseSoft"), config.chromaSoftness)
            GLES30.glUniform1f(p.uniform("uTime"), frameTime)
            stage.def.params.forEachIndexed { i, param ->
                GLES30.glUniform1f(p.uniform(param.key), if (healthy) 0f else stage.effective[i])
            }
            quad.draw()

            val next = stage.historyNext
            if (advanceHistory && next != null) {
                dst.copyTo(next)
                stage.historyNext = stage.historyRead
                stage.historyRead = next
            }
        }
        if (advanceHistory) historyPrimed = true
        return dst
    }

    /** Shows black on the viewfinder and in any recording. */
    private fun presentBlack() = presentEverywhere(null)

    /** Shows the last chain output, upright and letterboxed, on the viewfinder and in any recording. */
    private fun presentLast(program: Program = copy!!) = presentEverywhere(program)

    private fun presentEverywhere(program: Program?) {
        val egl = egl ?: return
        shown = program
        window?.let {
            drawOutput(windowWidth, windowHeight, program)
            if (!egl.swap(it)) Log.w(TAG, "eglSwapBuffers failed")
        }
        recording?.let {
            egl.makeCurrent(it)
            drawOutput(recordingWidth, recordingHeight, program)
            egl.setPresentationTime(it, System.nanoTime())
            egl.swap(it)
            egl.makeCurrent(window)
        }
    }

    /** Fills the current surface with black, then the last output through [program] if there is one. */
    private fun drawOutput(width: Int, height: Int, program: Program?) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val out = lastOutput
        if (program == null || out == null) return

        val portrait = isPortrait()
        val w = if (portrait) out.height else out.width
        val h = if (portrait) out.width else out.height
        val scale = minOf(width.toFloat() / w, height.toFloat() / h)
        val vw = (w * scale).toInt()
        val vh = (h * scale).toInt()
        GLES30.glViewport((width - vw) / 2, (height - vh) / 2, vw, vh)
        drawUpright(out, flipY = false, program = program)
    }

    /** Draws a sensor-orientation frame into the bound target, turned upright if [turn] is set. */
    private fun drawUpright(
        frame: Framebuffer,
        flipY: Boolean,
        turn: Boolean = isPortrait(),
        program: Program = copy!!,
    ) {
        val copy = program
        Matrix.setIdentityM(shapeMatrix, 0)
        Matrix.translateM(shapeMatrix, 0, 0.5f, 0.5f, 0f)
        if (turn) Matrix.rotateM(shapeMatrix, 0, -PORTRAIT_TURN, 0f, 0f, 1f)
        Matrix.translateM(shapeMatrix, 0, -0.5f, -0.5f, 0f)
        if (flipY) {
            Matrix.translateM(shapeMatrix, 0, 0f, 1f, 0f)
            Matrix.scaleM(shapeMatrix, 0, 1f, -1f, 1f)
        }
        copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.texture)
        GLES30.glUniform1i(copy.uniform("uTex"), 0)
        GLES30.glUniformMatrix4fv(copy.uniform("uTexMatrix"), 1, false, shapeMatrix, 0)
        GLES30.glUniform1f(copy.uniform("uTime"), frameTime)
        quad!!.draw()
    }

    private fun uprightWidth() = if (swapped) bufferHeight else bufferWidth
    private fun uprightHeight() = if (swapped) bufferWidth else bufferHeight
    private fun isPortrait() = uprightHeight() > uprightWidth()

    /**
     * Maps sensor frame coordinates to camera texture coordinates: turn the sensor
     * frame upright, centre-crop to its aspect ratio, rotate to the buffer's
     * orientation, then the SurfaceTexture transform.
     */
    private fun updateIngestMatrix() {
        val portrait = isPortrait()
        val sensorAspect = config.sensorWidth.toFloat() / config.sensorHeight
        val frameAspect = if (portrait) 1f / sensorAspect else sensorAspect
        val sourceAspect = uprightWidth().toFloat() / uprightHeight()
        val sx = if (sourceAspect > frameAspect) frameAspect / sourceAspect else 1f
        val sy = if (sourceAspect > frameAspect) 1f else sourceAspect / frameAspect

        Matrix.setIdentityM(shapeMatrix, 0)
        Matrix.translateM(shapeMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(shapeMatrix, 0, extraRotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(shapeMatrix, 0, sx / zoom, sy / zoom, 1f)
        if (portrait) Matrix.rotateM(shapeMatrix, 0, PORTRAIT_TURN, 0f, 0f, 1f)
        Matrix.translateM(shapeMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, shapeMatrix, 0)
    }

    private fun releaseGl() {
        val egl = egl ?: return
        endBurst()
        surfaceTexture?.release()
        surfaceTexture = null
        previewChain?.release()
        captureChain?.release()
        stillTarget?.release()
        for (stage in stages) {
            stage.program.release()
            stage.historyRead?.release()
            stage.historyNext?.release()
        }
        stages.clear()
        ingest?.release()
        copy?.release()
        garble?.release()
        quad?.release()
        GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
        egl.makeCurrent(null)
        window?.let { egl.destroySurface(it) }
        recording?.let { egl.destroySurface(it) }
        window = null
        recording = null
        egl.release()
        this.egl = null
    }

    private companion object {
        const val TAG = "ShortEngine"

        /** Counter-clockwise turn, in degrees, from the sensor frame to an upright portrait frame. */
        const val PORTRAIT_TURN = 90f

        const val FREEZE_SECONDS = 0.5f
        const val GARBLE_SECONDS = 0.9f
        const val DARK_SECONDS = 0.9f
        const val GRACE_NANOS = 6_000_000_000L
        const val BURST_SLACK_NANOS = 10_000_000L
    }
}
