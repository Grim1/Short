package app.shortcam.board

import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import app.shortcam.bodies.Pad
import app.shortcam.engine.FaultParam
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WireColors = listOf(
    Color(0xFFD23B2E), Color(0xFFE8C330), Color(0xFF2D6FD2), Color(0xFFEDEDE6),
    Color(0xFF2FA35A), Color(0xFFE2792B), Color(0xFF8A4FC2), Color(0xFF2B2B2E),
)

private const val PAD_HIT = 6f
private const val CONTROL_HIT = 5.5f
private const val WIRE_HIT = 4f
private const val SLOP = 2.5f
private const val LONG_PRESS_MS = 450L

/** How far a finger travels, in board units, to turn a pot from one end stop to the other. */
private const val POT_TRAVEL = 20f

/** What one finger is doing on the board. */
private sealed interface Role
private class Dragging(val from: Pad) : Role
private class Touching(val pad: Pad) : Role
private class Holding(val wire: Wire, val onControl: Boolean, val start: Offset) : Role {
    var moved = false
    var fired = false
    var timer: Job? = null
}

/**
 * The opened back of the camera. Drag between pads to bridge them, tap a wire to pull
 * it off, hold a wire to fit a pot and then a switch, hold a bare contact to bend with
 * a finger. The result is published through [BoardState.faultValues].
 */
@Composable
fun BoardView(state: BoardState, modifier: Modifier = Modifier) {
    val text = rememberTextMeasurer()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val drags = remember { mutableStateMapOf<PointerId, Pair<Pad, Offset>>() }

    // Drawn art layers from the body's folder, if it has any. A layer that will not
    // load is skipped; with none left the board falls back to being drawn in code.
    val assets = LocalContext.current.assets
    val art = remember(state.body) {
        state.body.board.art.mapNotNull { file ->
            runCatching {
                assets.open("bodies/${state.body.id}/$file").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) state.step((now - last) / 1e9f)
                last = now
                frame = now
            }
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            drawBoardArt(BoardGeometry(state.body, size.width, size.height), text, art)
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .pointerInput(state) {
                    val roles = HashMap<PointerId, Role>()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val geo = BoardGeometry(state.body, size.width.toFloat(), size.height.toFloat())
                            for (c in event.changes) {
                                val at = Offset(geo.boardX(c.position.x), geo.boardY(c.position.y))
                                when {
                                    c.changedToDown() -> {
                                        val role = begin(state, at)
                                        if (role != null) roles[c.id] = role
                                        when (role) {
                                            is Dragging -> drags[c.id] = role.from to at
                                            is Touching -> {
                                                state.touches[role.pad.id] = contactStrength(role.pad, at, c.pressure)
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                            is Holding -> role.timer = scope.launch {
                                                delay(LONG_PRESS_MS)
                                                if (!role.moved) {
                                                    role.fired = true
                                                    state.cycleControl(role.wire)
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                }
                                            }
                                            null -> state.nudge(at.x, at.y, 14f)
                                        }
                                    }

                                    c.changedToUp() -> when (val role = roles.remove(c.id)) {
                                        is Dragging -> {
                                            drags.remove(c.id)
                                            val target = padAt(state, at, wiresOnly = true)
                                            if (target != null && state.addWire(role.from, target) != null) {
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                        }
                                        is Touching -> state.touches.remove(role.pad.id)
                                        is Holding -> {
                                            role.timer?.cancel()
                                            if (!role.fired && !role.moved) {
                                                if (role.onControl && role.wire.control == WireControl.SWITCH) {
                                                    role.wire.on = !role.wire.on
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                } else if (!role.onControl) {
                                                    state.removeWire(role.wire)
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                }
                                            }
                                        }
                                        null -> Unit
                                    }

                                    c.pressed -> when (val role = roles[c.id]) {
                                        is Dragging -> {
                                            drags[c.id] = role.from to at
                                            state.nudge(at.x, at.y, 3f)
                                        }
                                        is Touching -> {
                                            state.touches[role.pad.id] = contactStrength(role.pad, at, c.pressure)
                                        }
                                        is Holding -> {
                                            if (hypot(at.x - role.start.x, at.y - role.start.y) > SLOP) role.moved = true
                                            if (role.moved && role.onControl && role.wire.control == WireControl.POT) {
                                                val d = (c.position.x - c.previousPosition.x) - (c.position.y - c.previousPosition.y)
                                                val before = role.wire.pot
                                                role.wire.pot = (before + d / geo.scale / POT_TRAVEL).coerceIn(0f, 1f)
                                                // A tick for every tenth of a turn, and at each end stop.
                                                if ((before * 10).toInt() != (role.wire.pot * 10).toInt()) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                }
                                            }
                                        }
                                        null -> state.nudge(at.x, at.y, 3f)
                                    }
                                }
                                c.consume()
                            }
                            // A cancelled gesture can end without up events; drop anything left over.
                            if (event.changes.none { it.pressed }) {
                                roles.values.forEach { if (it is Holding) it.timer?.cancel() }
                                roles.clear()
                                drags.clear()
                                state.touches.clear()
                            }
                        }
                    }
                }
        ) {
            val geo = BoardGeometry(state.body, size.width, size.height)
            val time = frame / 1e9f
            if (state.powered) drawSignalLeds(geo, state.faultValues, time)
            drawContactGlow(geo, state)
            for (wire in state.wires) drawWire(geo, wire)
            for ((from, to) in drags.values) drawLooseWire(geo, from, to, WireColors[state.wires.size % WireColors.size])
        }
    }
}

private fun begin(state: BoardState, at: Offset): Role? {
    // Controls sit on top of everything, then pads, then the wires themselves.
    for (wire in state.wires.asReversed()) {
        if (wire.control != WireControl.NONE && dist(at, hangPoint(wire)) < CONTROL_HIT) {
            return Holding(wire, onControl = true, start = at)
        }
    }
    padAt(state, at, wiresOnly = false)?.let { return if (it.contact) Touching(it) else Dragging(it) }
    for (wire in state.wires.asReversed()) {
        if (distToWire(wire, at) < WIRE_HIT) return Holding(wire, onControl = false, start = at)
    }
    return null
}

private fun padAt(state: BoardState, at: Offset, wiresOnly: Boolean): Pad? =
    state.body.pads
        .filter { !(wiresOnly && it.contact) }
        .map { it to hypot(it.x - at.x, it.y - at.y) }
        .filter { (pad, d) -> d < if (pad.contact) CONTACT_RADIUS + 1.5f else PAD_HIT }
        .minByOrNull { it.second }
        ?.first

/** A finger near the middle of a contact, pressed flat, bends hardest. */
private fun contactStrength(pad: Pad, at: Offset, pressure: Float): Float {
    val centred = (1f - hypot(at.x - pad.x, at.y - pad.y) / (CONTACT_RADIUS + 1.5f)).coerceIn(0f, 1f)
    return ((0.3f + 0.7f * centred) * (0.5f + 0.5f * pressure.coerceIn(0f, 1f))).coerceIn(0f, 1f)
}

private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)

/** Where the middle of the wire hangs, in board units. */
private fun hangPoint(wire: Wire) =
    Offset((wire.a.x + wire.b.x) / 2 + wire.sagX, (wire.a.y + wire.b.y) / 2 + wire.sagY)

/** Point on the wire's curve at [t], in board units. The curve passes through the hang point. */
private fun wirePoint(wire: Wire, t: Float): Offset {
    val hang = hangPoint(wire)
    val cx = 2 * hang.x - (wire.a.x + wire.b.x) / 2
    val cy = 2 * hang.y - (wire.a.y + wire.b.y) / 2
    val u = 1 - t
    return Offset(
        u * u * wire.a.x + 2 * u * t * cx + t * t * wire.b.x,
        u * u * wire.a.y + 2 * u * t * cy + t * t * wire.b.y,
    )
}

private fun distToWire(wire: Wire, at: Offset): Float {
    var best = Float.MAX_VALUE
    for (i in 0..16) best = minOf(best, dist(at, wirePoint(wire, i / 16f)))
    return best
}

private fun DrawScope.drawCurve(from: Offset, hang: Offset, to: Offset, color: Color, s: Float) {
    val mid = (from + to) / 2f
    val control = hang * 2f - mid
    val path = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    val shadow = Path().apply { addPath(path, Offset(0.6f * s, 1.1f * s)) }
    drawPath(shadow, Color.Black.copy(alpha = 0.28f), style = Stroke(1.8f * s, cap = StrokeCap.Round))
    drawPath(path, Color.Black.copy(alpha = 0.55f), style = Stroke(1.9f * s, cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(1.4f * s, cap = StrokeCap.Round))
    drawPath(path, Color.White.copy(alpha = 0.28f), style = Stroke(0.35f * s, cap = StrokeCap.Round))
}

private fun DrawScope.drawSolder(at: Offset, s: Float) {
    drawCircle(Color(0xFF8E9197), 2.3f * s, at)
    drawCircle(
        Brush.radialGradient(listOf(Color.White, Color(0xFFB9BCC2)), at - Offset(0.6f * s, 0.6f * s), 2f * s),
        2f * s, at,
    )
}

private fun DrawScope.drawWire(geo: BoardGeometry, wire: Wire) {
    val s = geo.scale
    val a = geo.px(wire.a)
    val b = geo.px(wire.b)
    val hang = hangPoint(wire).let { geo.px(it.x, it.y) }
    drawCurve(a, hang, b, WireColors[wire.colorIndex % WireColors.size], s)
    drawSolder(a, s)
    drawSolder(b, s)

    if (wire.control == WireControl.NONE) return

    // Hot glue holding the control to the board.
    drawCircle(Color(0xFFEDE6C8).copy(alpha = 0.55f), 5.2f * s, hang + Offset(0.8f * s, 1f * s))
    drawCircle(Color(0xFFF7F2DC).copy(alpha = 0.6f), 4.3f * s, hang - Offset(1f * s, 0.6f * s))
    drawCircle(Color.White.copy(alpha = 0.35f), 1.1f * s, hang - Offset(2.6f * s, 2.4f * s))

    if (wire.control == WireControl.POT) {
        val half = 3.3f * s
        drawRoundRect(Color(0xFF173B78), hang - Offset(half, half) + Offset(0.3f * s, 0.5f * s), Size(half * 2, half * 2), CornerRadius(0.7f * s))
        drawRoundRect(Color(0xFF2A62B8), hang - Offset(half, half), Size(half * 2, half * 2), CornerRadius(0.7f * s))
        drawCircle(Color(0xFF9A8748), 2.5f * s, hang)
        drawCircle(Color(0xFFE2CC83), 2.2f * s, hang)
        val angle = Math.toRadians((-225.0 + 270.0 * wire.pot))
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(Color(0xFF3B3320), hang - dir * (1.8f * s), hang + dir * (1.8f * s), 0.7f * s, StrokeCap.Round)
        drawCircle(Color(0xFFD23B2E), 0.5f * s, hang + dir * (1.5f * s))
    } else {
        val w = 2.6f * s
        val h = 3.6f * s
        drawRoundRect(Color(0xFF6E7278), hang - Offset(w, h) + Offset(0.3f * s, 0.5f * s), Size(w * 2, h * 2), CornerRadius(0.6f * s))
        drawRoundRect(Color(0xFFC4C7CC), hang - Offset(w, h), Size(w * 2, h * 2), CornerRadius(0.6f * s))
        drawCircle(Color(0xFF55585E), 1.5f * s, hang)
        val tip = hang + Offset(0f, if (wire.on) -3.4f * s else 3.4f * s)
        drawLine(Color(0xFF8E9197), hang, tip, 1.5f * s, StrokeCap.Round)
        drawLine(Color(0xFFF1F2F4), hang, tip, 0.9f * s, StrokeCap.Round)
        drawCircle(Color(0xFFF1F2F4), 1.1f * s, tip)
    }
}

/** A wire still in the hand: soldered at one end, following the finger at the other. */
private fun DrawScope.drawLooseWire(geo: BoardGeometry, from: Pad, to: Offset, color: Color) {
    val s = geo.scale
    val a = geo.px(from)
    val b = geo.px(to.x, to.y)
    val sag = (4f + 0.12f * hypot(to.x - from.x, to.y - from.y)) * s
    drawCurve(a, (a + b) / 2f + Offset(0f, sag), b, color, s)
    drawSolder(a, s)
    drawCircle(Color(0xFFC98A4B), 0.9f * s, b)
}

/** LEDs tied to a stage flicker harder the more that stage is being bent. */
private fun DrawScope.drawSignalLeds(geo: BoardGeometry, values: Map<FaultParam, Float>, time: Float) {
    val s = geo.scale
    geo.body.board.parts.forEachIndexed { index, part ->
        if (part.type != "led") return@forEachIndexed
        val centre = geo.px(part.x + part.w / 2, part.y + part.h / 2)
        val stage = part.stage
        val (color, level) = if (stage == null) {
            Color(0xFF45E06A) to 0.85f
        } else {
            val activity = values.entries.filter { it.key.stage == stage }.maxOfOrNull { it.value } ?: 0f
            val slot = (time * 22f).toInt()
            val noise = ((((slot * 7919 + index * 104729) * 2654435761L) ushr 8) and 0xFFFFL) / 65535f
            Color(0xFFFF4A32) to if (activity > 0f && noise < 0.2f + 0.8f * activity) 0.5f + 0.5f * noise else 0f
        }
        if (level <= 0f) return@forEachIndexed
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.55f * level), Color.Transparent), centre, 6f * s),
            6f * s, centre,
        )
        drawRoundRect(
            color.copy(alpha = level), centre - Offset(part.w * s / 2, part.h * s / 2),
            Size(part.w * s, part.h * s), CornerRadius(0.5f * s),
        )
        drawCircle(Color.White.copy(alpha = 0.7f * level), 0.6f * s, centre)
    }
}

private fun DrawScope.drawContactGlow(geo: BoardGeometry, state: BoardState) {
    val s = geo.scale
    for ((padId, strength) in state.touches) {
        val pad = state.body.pad(padId) ?: continue
        val c = geo.px(pad)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFE2A8).copy(alpha = 0.25f + 0.45f * strength), Color.Transparent), c, 11f * s),
            11f * s, c,
        )
    }
}
