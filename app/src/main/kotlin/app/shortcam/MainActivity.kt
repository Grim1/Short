package app.shortcam

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.shortcam.board.BoardGeometry
import app.shortcam.board.BoardState
import app.shortcam.board.BoardView
import app.shortcam.board.ModStore
import app.shortcam.board.Wire
import app.shortcam.bodies.Body
import app.shortcam.bodies.BodyLoader
import app.shortcam.capture.GifWriter
import app.shortcam.engine.CompressorFault
import app.shortcam.engine.StillFrame
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import kotlin.math.ln
import app.shortcam.capture.StillMeta
import app.shortcam.capture.Stills
import app.shortcam.capture.VideoRecorder
import app.shortcam.engine.Engine
import app.shortcam.engine.Faults
import androidx.compose.runtime.produceState
import java.util.Date
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * Orientation of the camera front. It follows how the phone is held even when
 * auto-rotate is off, as a camera should, so photos come out upright.
 */
private const val FRONT_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ShortApp(this) }
    }
}

@Composable
private fun ShortApp(activity: ComponentActivity) {
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    // Every body package in the app, and one board per body so wires survive a trip to the shelf.
    val bodies = remember { BodyLoader.list(activity.assets).map { BodyLoader.load(activity.assets, it) } }
    val boards = remember { bodies.associate { it.id to BoardState(it) } }
    val notebooks = remember { bodies.associate { it.id to Notebook(activity.filesDir, it) } }
    val mods = remember { ModStore(activity.filesDir) }
    val settings = remember { Settings(activity.filesDir) }
    val last = remember { LastCapture(activity.applicationContext).also { it.findLatest() } }
    var inHand by remember { mutableStateOf<Body?>(null) }
    var started by remember { mutableStateOf(false) }

    // The shelf is portrait; a camera in hand sets its own orientation.
    LaunchedEffect(inHand) {
        if (inHand == null) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
        val body = inHand
        when {
            !started -> MakerSplash { started = true }
            !granted -> BasicText(
                "Short needs the camera. Tap to allow.",
                style = TextStyle(color = Color.White),
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable { launcher.launch(Manifest.permission.CAMERA) }
                    .padding(24.dp),
            )
            body == null -> Shelf(bodies, settings) { inHand = it }
            else -> key(body.id) {
                Camera(
                    activity, body, boards.getValue(body.id), notebooks.getValue(body.id),
                    mods, settings, last, onShelf = { inHand = null },
                )
            }
        }
    }
}

/** One camera body: the front with viewfinder and shutter, and the opened back with the board. */
@Composable
private fun Camera(
    activity: ComponentActivity,
    body: Body,
    board: BoardState,
    notebook: Notebook,
    mods: ModStore,
    settings: Settings,
    last: LastCapture,
    onShelf: () -> Unit,
) {
    val engine = remember { Engine(body.engine) }
    val feed = remember { CameraFeed(activity, engine, Size(body.engine.sensorWidth, body.engine.sensorHeight)) }
    val saver = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    var showBack by remember { mutableStateOf(false) }
    var showMods by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<VideoRecorder?>(null) }
    val turn = remember { Animatable(0f) }
    val sound = remember { ShutterSound(body.viewfinder.shutter) }
    var frontLens by remember { mutableStateOf(false) }
    var shotsLeft by remember { mutableStateOf(body.viewfinder.shots) }
    var zoom by remember { mutableStateOf(1f) }
    var mode by remember { mutableStateOf(ShotMode.STILL) }
    var burstLabel by remember { mutableStateOf("") }
    // The first half of a double exposure, waiting for the second press.
    var firstShot by remember { mutableStateOf<StillFrame?>(null) }
    var gifRunning by remember { mutableStateOf(false) }
    // Counts start-ups, so the boot screen plays again after each crash.
    var boots by remember { mutableStateOf(0) }

    // Seconds the current recording has run, for the on-screen display.
    val recordingSeconds by produceState(0, recorder) {
        value = 0
        while (recorder != null) {
            delay(1000)
            value++
        }
    }

    fun toast(text: String) = activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }

    fun flip() {
        if (turn.isRunning) return
        scope.launch {
            // Turn edge-on, swap faces while nothing is visible, then turn the rest of the way.
            turn.animateTo(90f, tween(200, easing = FastOutLinearInEasing))
            showBack = !showBack
            turn.snapTo(-90f)
            turn.animateTo(0f, tween(260, easing = LinearOutSlowInEasing))
        }
    }

    fun startRecording(withAudio: Boolean) {
        engine.frameSize { width, height ->
            val started = runCatching {
                VideoRecorder(activity, width, height, withAudio).also {
                    engine.setRecordingSurface(it.inputSurface, width, height)
                    it.start()
                }
            }
            started.onFailure { Log.e("Short", "could not start recording", it) }
            activity.runOnUiThread {
                recorder = started.getOrNull()
                if (started.isFailure) toast("Could not start recording")
            }
        }
    }

    fun stopRecording() {
        val active = recorder ?: return
        recorder = null
        saver.execute {
            engine.setRecordingSurface(null, 0, 0)
            val uri = active.stop()
            Log.i("Short", "video saved to $uri")
            if (uri != null) last.set(uri)
            toast(if (uri != null) "Video saved to Short album" else "Could not save video")
        }
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) toast("No microphone access: recording without sound")
        startRecording(withAudio = allowed)
    }

    // Reloads a look from a photo this app took.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val patch = Stills.readDescription(activity, uri)?.let {
            try { JSONObject(it) } catch (e: JSONException) { null }
        }
        when {
            patch == null || !patch.has("wires") -> toast("That photo has no Short patch in it")
            board.applyPatch(patch) -> {
                showMods = false
                toast("Patch loaded from photo")
            }
            else -> toast("That photo was taken on another camera")
        }
    }

    DisposableEffect(Unit) {
        engine.onCrash = {
            Log.i("Short", "camera crashed")
            activity.runOnUiThread {
                board.powered = false
                // A sharper buzz than the ticks for wires.
                activity.getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 160), intArrayOf(0, 255, 0, 255), -1))
            }
        }
        engine.onReboot = {
            Log.i("Short", "camera rebooted")
            activity.runOnUiThread {
                board.powered = true
                boots++
            }
        }
        feed.start()
        onDispose {
            board.powered = true
            feed.stop()
            recorder?.let { active ->
                engine.setRecordingSurface(null, 0, 0)
                active.stop()
            }
            engine.release()
            sound.release()
            saver.shutdown()
        }
    }

    // The front turns with the phone. The board is laid out for portrait only.
    LaunchedEffect(showBack) {
        activity.requestedOrientation = if (showBack) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else FRONT_ORIENTATION
    }

    // The board is the only thing that sets faults.
    LaunchedEffect(board) {
        snapshotFlow { board.faultValues }.collect { values ->
            Faults.ALL.forEach { engine.setFault(it, values[it] ?: 0f) }
        }
    }

    // The notebook writes itself: every pair that does something, every contact touched.
    LaunchedEffect(board) {
        snapshotFlow { board.wires.toList() }.collect { wires -> wires.forEach { notebook.bridged(it.a, it.b) } }
    }
    LaunchedEffect(board) {
        snapshotFlow { board.touches.filterValues { it > 0f }.keys.toSet() }.collect { it.forEach(notebook::touched) }
    }

    // Landing a wire on a crash pair takes the camera down once.
    LaunchedEffect(board) {
        var before = board.crashWires
        snapshotFlow { board.crashWires }.collect { now ->
            if ((now - before).isNotEmpty()) engine.crash()
            before = now
        }
    }

    BackHandler {
        when {
            showMods -> showMods = false
            showNotes -> showNotes = false
            showBack -> flip()
            else -> onShelf()
        }
    }

    fun takePhoto() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // The photo carries the camera and the patch that made it.
        val meta = StillMeta(body.maker, body.model, board.toPatch().toString())
        sound.play()
        engine.captureStill(alsoClean = settings.cleanCopy) { frame, clean ->
            if (frame == null) return@captureStill
            activity.runOnUiThread { shotsLeft = (shotsLeft - 1).coerceAtLeast(0) }
            saver.execute {
                val taken = Date()
                val result = runCatching {
                    // The clean copy carries no patch: nothing was bent to make it.
                    if (clean != null) {
                        Stills.saveToAlbum(activity, Stills.encode(clean), taken, suffix = "_clean")
                    }
                    Stills.saveToAlbum(activity, Stills.encode(frame, meta), taken)
                }
                result.onSuccess {
                    Log.i("Short", "saved ${frame.width}x${frame.height} to $it, clean copy=${clean != null}")
                    last.set(it)
                }
                result.onFailure { Log.e("Short", "save failed", it) }
                toast(if (result.isSuccess) "Saved to Short album" else "Could not save photo")
            }
        }
    }

    /** Starts a few seconds of viewfinder frames that are saved as a looping GIF; a second press ends it early. */
    fun toggleBurst(label: String, interval: Float, frames: Int) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (gifRunning) {
            engine.stopBurst()
            return
        }
        gifRunning = true
        burstLabel = label
        sound.play()
        // Frames are encoded as they arrive, on the saving thread, which alone touches these.
        val bytes = ByteArrayOutputStream()
        var writer: GifWriter? = null
        engine.startBurst(
            GIF_SIDE, interval, frames,
            onFrame = { width, height, rgba ->
                saver.execute {
                    runCatching {
                        val w = writer ?: GifWriter(bytes, width, height, (GIF_FRAME_SECONDS * 100).roundToInt()).also { writer = it }
                        w.addFrame(rgba)
                    }.onFailure { Log.e("Short", "GIF frame failed", it) }
                }
            },
            onEnd = {
                saver.execute {
                    val result = runCatching {
                        val w = checkNotNull(writer) { "no frames" }
                        w.finish()
                        Stills.saveToAlbum(activity, bytes.toByteArray(), Date(), extension = "gif", mime = "image/gif")
                    }
                    result.onSuccess {
                        Log.i("Short", "saved GIF of ${writer?.frames} frames, ${bytes.size()} bytes, to $it")
                        last.set(it)
                    }
                    result.onFailure { Log.e("Short", "GIF save failed", it) }
                    toast(if (result.isSuccess) "GIF saved to Short album" else "Could not save GIF")
                    activity.runOnUiThread {
                        gifRunning = false
                        if (result.isSuccess) shotsLeft = (shotsLeft - 1).coerceAtLeast(0)
                    }
                }
            },
        )
    }

    /** Saves a finished frame as a photo carrying the camera and patch, and reports it. */
    fun savePhoto(frame: StillFrame, what: String) {
        val meta = StillMeta(body.maker, body.model, board.toPatch().toString())
        val result = runCatching { Stills.saveToAlbum(activity, Stills.encode(frame, meta), Date()) }
        result.onSuccess {
            Log.i("Short", "saved $what ${frame.width}x${frame.height} to $it")
            last.set(it)
        }
        result.onFailure { Log.e("Short", "$what save failed", it) }
        toast(if (result.isSuccess) "Saved to Short album" else "Could not save photo")
        if (result.isSuccess) activity.runOnUiThread { shotsLeft = (shotsLeft - 1).coerceAtLeast(0) }
    }

    /**
     * Two presses, one photo: the first shot is held, and the second is exposed on top
     * of it, as when film is not wound on. Wires can be changed in between.
     */
    fun takeDouble() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        sound.play()
        engine.captureStill { frame, _ ->
            if (frame == null) return@captureStill
            val first = firstShot
            if (first == null || first.width != frame.width || first.height != frame.height) {
                activity.runOnUiThread { firstShot = frame }
                return@captureStill
            }
            activity.runOnUiThread { firstShot = null }
            saver.execute {
                // Light adds up on film; each shot is held back a little so the pair does not burn out.
                val a = first.rgba
                val b = frame.rgba
                for (i in 0 until frame.width * frame.height * 4) {
                    val sum = ((a.get(i).toInt() and 0xFF) + (b.get(i).toInt() and 0xFF)) * 5 / 8
                    b.put(i, minOf(sum, 255).toByte())
                }
                savePhoto(frame, "double exposure")
            }
        }
    }

    /**
     * Holds the shutter open for a couple of seconds: every viewfinder frame in that
     * time lands on the same photo, so lights leave trails and glitches pile up.
     */
    fun takeLong() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (gifRunning) return
        gifRunning = true
        burstLabel = "● LONG"
        sound.play()
        // Touched only on the saving thread.
        var sums: IntArray? = null
        var peak: ByteArray? = null
        var size = 0 to 0
        var count = 0
        engine.startBurst(
            LONG_SIDE, LONG_FRAME_SECONDS, LONG_FRAMES,
            onFrame = { width, height, rgba ->
                saver.execute {
                    val n = width * height * 4
                    val s = sums ?: IntArray(n).also { sums = it }
                    val p = peak ?: ByteArray(n).also { peak = it }
                    if (s.size != n) return@execute
                    size = width to height
                    for (i in 0 until n) {
                        val v = rgba.get(i).toInt() and 0xFF
                        s[i] += v
                        if (v > (p[i].toInt() and 0xFF)) p[i] = v.toByte()
                    }
                    count++
                }
            },
            onEnd = {
                saver.execute {
                    val s = sums
                    val p = peak
                    if (s == null || p == null || count == 0) {
                        toast("Could not save photo")
                    } else {
                        // Half the average, half the brightest each spot ever was: movement
                        // blurs, and anything bright leaves its trail.
                        val out = ByteBuffer.allocateDirect(s.size)
                        for (i in s.indices) out.put(i, ((s[i] / count + (p[i].toInt() and 0xFF)) / 2).toByte())
                        // Burst frames are already upright and have been through the live compressor.
                        savePhoto(
                            StillFrame(size.first, size.second, out, turn = 0, compressor = CompressorFault(0f, emptyList(), 0f, 0L)),
                            "long exposure of $count frames",
                        )
                    }
                    activity.runOnUiThread { gifRunning = false }
                }
            },
        )
    }

    fun pressShutter() = when (mode) {
        ShotMode.STILL -> takePhoto()
        ShotMode.GIF -> toggleBurst("● GIF", GIF_FRAME_SECONDS, GIF_FRAMES)
        ShotMode.LAPSE -> toggleBurst("● LAPSE", LAPSE_FRAME_SECONDS, LAPSE_FRAMES)
        ShotMode.DOUBLE -> takeDouble()
        ShotMode.LONG -> takeLong()
    }

    fun toggleRecording() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when {
            recorder != null -> stopRecording()
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startRecording(withAudio = true)
            else -> microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun openGallery() {
        if (!last.open()) toast(if (last.uri == null) "Nothing in the Short album yet" else "No gallery app could open it")
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .graphicsLayer {
                rotationY = turn.value
                cameraDistance = 14f * this.density
            }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val panelHeight = with(density) { 176.dp.toPx() }
        // The opened back keeps a strip of the camera's edge under the board, with the
        // shutter on it, so a finger can stay on a contact pad while another shoots.
        val stripHeight = with(density) { BACK_STRIP.toPx() }
        val landscape = width > height
        val live: Rect = when {
            showBack -> BoardGeometry(body, width, height - stripHeight).previewRect
            landscape -> Rect(0f, 0f, minOf(height * 4f / 3f, width - panelHeight * 1.4f), height)
            else -> Rect(0f, 0f, width, minOf(width * 4f / 3f, height - panelHeight))
        }

        if (showBack) {
            BoardView(board, Modifier.fillMaxSize().padding(bottom = BACK_STRIP))
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(BACK_STRIP)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2C2E32), Color(0xFF17181A))))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val label = TextStyle(color = Color(0xFFE8E8E0), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                BasicText("MODS", style = label, modifier = Modifier.clickable { showMods = true }.padding(8.dp))
                BasicText("NOTES", style = label, modifier = Modifier.clickable { showNotes = true }.padding(8.dp))
                RecordButton(40.dp, recorder != null, ::toggleRecording)
                ShutterButton(64.dp, ::pressShutter)
                GalleryThumb(44.dp, last, ::openGallery)
                BasicText("CLOSE", style = label, modifier = Modifier.clickable { flip() }.padding(8.dp))
            }
        } else {
            FrontFace(
                body = body,
                landscape = landscape,
                viewfinderExtent = with(density) { (if (landscape) live.width else live.height).toDp() },
                recording = recorder != null,
                frontLens = frontLens,
                modeLabel = mode.label,
                zoom = zoom,
                last = last,
                onZoom = {
                    zoom = it
                    feed.setZoom(it)
                },
                onSwitchMode = {
                    if (!gifRunning) {
                        mode = ShotMode.entries[(mode.ordinal + 1) % ShotMode.entries.size]
                        firstShot = null
                    }
                },
                onShutter = ::pressShutter,
                onRecord = ::toggleRecording,
                onGallery = ::openGallery,
                onSwitchLens = {
                    if (feed.useFrontLens(!frontLens)) frontLens = !frontLens
                    else toast("This device has no camera on that side")
                },
                onOpen = ::flip,
                onShelf = {
                    stopRecording()
                    onShelf()
                },
            )
        }

        // One live view for both faces: the viewfinder on the front, the small LCD on the back.
        val liveModifier = Modifier
            .offset { IntOffset(live.left.roundToInt(), live.top.roundToInt()) }
            .size(with(density) { live.width.toDp() }, with(density) { live.height.toDp() })
        AndroidView(
            modifier = liveModifier,
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st)
                            engine.setOutputSurface(surface, w, h)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            engine.setOutputSurface(surface, w, h)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            engine.setOutputSurface(null, 0, 0)
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )

        if (!showBack) {
            Osd(body.viewfinder, shotsLeft, recordingSeconds, liveModifier)
            if (zoom > 1.01f) ZoomScale(body.viewfinder, ln(zoom) / ln(body.zoom), liveModifier)
        }
        BootScreen(body.viewfinder, boots, liveModifier)

        if (recorder != null || gifRunning || firstShot != null) {
            BasicText(
                if (gifRunning) burstLabel else if (firstShot != null) "◫ 1 OF 2" else "● REC",
                style = TextStyle(color = Color(0xFFFF4034), fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier
                    .offset { IntOffset(live.left.roundToInt(), live.top.roundToInt()) }
                    .padding(start = 8.dp, top = if (showBack) 8.dp else 40.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (showBack) {
            // Tapping the little LCD also turns the camera back over.
            Box(liveModifier.clickable(onClick = ::flip))
            if (showNotes) NotebookPanel(notebook) { showNotes = false }
            if (showMods) {
                ModsPanel(
                    board = board,
                    store = mods,
                    onLoadFromPhoto = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onMessage = ::toast,
                    onClose = { showMods = false },
                )
            }
        }
    }
}

/** A GIF is at most this many frames, this far apart, and this many pixels on its long edge. */
private const val GIF_FRAMES = 30
private const val GIF_FRAME_SECONDS = 0.1f
private const val GIF_SIDE = 400

/** A time-lapse takes a frame a second, for up to five minutes, and plays back at ten a second. */
private const val LAPSE_FRAMES = 300
private const val LAPSE_FRAME_SECONDS = 1f

/** A long exposure gathers every viewfinder frame, at full viewfinder size, for about two seconds. */
private const val LONG_FRAMES = 50
private const val LONG_FRAME_SECONDS = 0.03f
private const val LONG_SIDE = 4096

/** What the shutter does. */
private enum class ShotMode(val label: String) {
    STILL("▢ STILL"),
    GIF("▣ GIF"),
    DOUBLE("◫ DOUBLE"),
    LONG("◔ LONG"),
    LAPSE("◷ LAPSE"),
}

/** Height of the camera edge shown under the board on the opened back. */
private val BACK_STRIP = 84.dp

