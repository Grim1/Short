package app.shortcam.board

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.abs
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shortcam.bodies.Body
import app.shortcam.bodies.Pad
import app.shortcam.engine.FaultParam
import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject

enum class WireControl { NONE, POT, SWITCH }

/** A patch wire bridging two pads, with an optional soldered-on control. */
class Wire internal constructor(val a: Pad, val b: Pad, val colorIndex: Int) {
    var control by mutableStateOf(WireControl.NONE)

    /** Pot position, 0..1 across the fault's range. */
    var pot by mutableFloatStateOf(0.5f)

    /** Toggle switch position. */
    var on by mutableStateOf(true)

    // Where the wire's middle hangs relative to the straight line between its pads,
    // in board units. A damped spring pulls it to its resting sag.
    internal var sagX = 0f
    internal var sagY = 0f
    internal var velX = 0f
    internal var velY = 0f

    internal val restSag: Float get() = 5f + 0.16f * hypot(b.x - a.x, b.y - a.y)

    fun connects(p: Pad, q: Pad) = (a == p && b == q) || (a == q && b == p)
}

/**
 * Everything the user has done to one body's board: wires, their controls, and fingers
 * on contact pads. [faultValues] is the result, ready to hand to the engine.
 */
class BoardState(val body: Body) {
    val wires = mutableStateListOf<Wire>()

    /** Contact pad id to how hard it is being touched, 0..1. */
    val touches = mutableStateMapOf<String, Float>()

    /** False while the camera is crashed and rebooting. The board goes dark; wires stay. */
    var powered by mutableStateOf(true)

    /**
     * Live wires across a pad pair the body marks as a crash. A wire joining this set
     * should crash the camera once. It then sits there doing nothing until it is
     * removed or switched off and on again.
     */
    val crashWires: Set<Wire> by derivedStateOf {
        wires.filter { wire ->
            body.faultFor(wire.a, wire.b)?.crash == true &&
                !(wire.control == WireControl.SWITCH && !wire.on)
        }.toSet()
    }

    private var colorCounter = 0

    /** Adds a wire, or returns null if the pads are the same or already bridged. */
    fun addWire(a: Pad, b: Pad): Wire? {
        if (a == b || a.contact || b.contact || wires.any { it.connects(a, b) }) return null
        return Wire(a, b, colorCounter++).also {
            // Starts taut and drops, so a new wire swings into place.
            it.velY = 30f
            wires += it
            stirred++
        }
    }

    fun removeWire(wire: Wire) {
        wires -= wire
    }

    fun clear() {
        wires.clear()
    }

    /** The current patch: every wire, its control, and the control's setting. */
    fun toPatch(): JSONObject = JSONObject()
        .put("short", PATCH_FORMAT)
        .put("body", body.id)
        .put("wires", JSONArray().also { list ->
            for (w in wires) {
                list.put(
                    JSONObject()
                        .put("a", w.a.id)
                        .put("b", w.b.id)
                        .put("control", w.control.name)
                        .put("pot", w.pot.toDouble())
                        .put("on", w.on)
                )
            }
        })

    /**
     * Replaces the board's wires with a saved patch. A patch only fits the body it was
     * made on; returns false, changing nothing, if it is for another body or unreadable.
     */
    fun applyPatch(patch: JSONObject): Boolean {
        if (patch.optString("body") != body.id) return false
        val list = patch.optJSONArray("wires") ?: return false
        clear()
        for (i in 0 until list.length()) {
            val w = list.optJSONObject(i) ?: continue
            val a = body.pad(w.optString("a")) ?: continue
            val b = body.pad(w.optString("b")) ?: continue
            val wire = addWire(a, b) ?: continue
            wire.control = WireControl.entries.firstOrNull { it.name == w.optString("control") } ?: WireControl.NONE
            wire.pot = w.optDouble("pot", 0.5).toFloat().coerceIn(0f, 1f)
            wire.on = w.optBoolean("on", true)
        }
        return true
    }

    /** Plain wire, then pot, then switch, then plain again. */
    fun cycleControl(wire: Wire) {
        when (wire.control) {
            WireControl.NONE -> {
                // A new pot starts where the bare wire was, so fitting it changes nothing.
                val entry = body.faultFor(wire.a, wire.b)
                wire.pot = if (entry != null && entry.max > entry.min) {
                    (entry.value - entry.min) / (entry.max - entry.min)
                } else 0.5f
                wire.control = WireControl.POT
            }
            WireControl.POT -> {
                wire.on = true
                wire.control = WireControl.SWITCH
            }
            WireControl.SWITCH -> wire.control = WireControl.NONE
        }
    }

    /** Intensity each fault parameter is driven at. Parameters not listed are healthy. */
    val faultValues: Map<FaultParam, Float> by derivedStateOf {
        val out = HashMap<FaultParam, Float>()
        for (wire in wires) {
            val entry = body.faultFor(wire.a, wire.b) ?: continue
            val param = entry.param ?: continue
            val value = when (wire.control) {
                WireControl.NONE -> entry.value
                WireControl.POT -> entry.min + (entry.max - entry.min) * wire.pot
                WireControl.SWITCH -> if (wire.on) entry.value else 0f
            }
            out[param] = maxOf(out[param] ?: 0f, value)
        }
        for ((padId, pressure) in touches) {
            val entry = body.pad(padId)?.let { body.contactFor(it) } ?: continue
            out[entry.param] = maxOf(out[entry.param] ?: 0f, entry.max * pressure)
        }
        out
    }

    /**
     * Counts every knock that can set a wire swinging. The view watches it to know
     * when to start animating again after everything has come to rest.
     */
    internal var stirred by mutableIntStateOf(0)
        private set

    /** Advances the wire physics by [dt] seconds. Returns false once every wire hangs still. */
    internal fun step(dt: Float): Boolean {
        val step = dt.coerceAtMost(0.033f)
        var moving = false
        for (w in wires) {
            if (abs(w.velX) + abs(w.velY) > 0.05f || abs(w.sagX) + abs(w.sagY - w.restSag) > 0.02f) moving = true
            val ax = -STIFFNESS * w.sagX - DAMPING * w.velX
            val ay = -STIFFNESS * (w.sagY - w.restSag) - DAMPING * w.velY
            w.velX += ax * step
            w.velY += ay * step
            w.sagX += w.velX * step
            w.sagY += w.velY * step
        }
        return moving
    }

    /** Knocks wires near a board position, so they sway when a finger brushes past. */
    internal fun nudge(x: Float, y: Float, strength: Float) {
        if (wires.isNotEmpty()) stirred++
        for (w in wires) {
            val mx = (w.a.x + w.b.x) / 2 + w.sagX
            val my = (w.a.y + w.b.y) / 2 + w.sagY
            val d = hypot(mx - x, my - y)
            if (d < 14f) {
                w.velX += (mx - x) / (d + 1f) * strength
                w.velY += (my - y) / (d + 1f) * strength
            }
        }
    }

    private companion object {
        const val PATCH_FORMAT = 1
        const val STIFFNESS = 90f
        const val DAMPING = 5.5f
    }
}
