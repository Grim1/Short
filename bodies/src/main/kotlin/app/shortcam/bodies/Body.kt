package app.shortcam.bodies

import app.shortcam.engine.EngineConfig
import app.shortcam.engine.FaultParam
import app.shortcam.engine.Faults
import app.shortcam.engine.StageId

/**
 * A camera body, loaded from `assets/bodies/<id>/body.json`. Everything that makes one
 * camera different from another lives here; the engine and the board view are shared.
 * Positions and sizes are in board units, with the origin at the board's top-left.
 */
data class Body(
    val id: String,
    val maker: String,
    val model: String,
    /** Slogans printed on the camera body. */
    val printed: List<String>,
    val serial: String,
    val madeIn: String,
    /** Colour of the camera's outer shell and of the markings printed on it, ARGB. */
    val shellColor: Long,
    val inkColor: Long,
    /** Colour of the slogans printed on the shell. */
    val accentColor: Long,
    val viewfinder: ViewfinderSpec,
    /** How far the lens zooms in, as a multiple of its widest view. 1 is a fixed lens. */
    val zoom: Float,
    /**
     * How strongly two pads with no fault table entry of their own still couple, 0..1.
     * 0 means only the listed pairs do anything.
     */
    val crosstalk: Float,
    val engine: EngineConfig,
    val board: BoardSpec,
    val pads: List<Pad>,
    val faults: List<FaultEntry>,
    val contacts: List<ContactEntry>,
) {
    private val padsById = pads.associateBy { it.id }
    private val faultsByPair = faults.associateBy { pairKey(it.padA, it.padB) }
    private val contactsByPad = contacts.associateBy { it.pad }

    fun pad(id: String): Pad? = padsById[id]

    /**
     * What bridging two pads does, in either order: the fault table's entry for the
     * pair, or failing that a weaker stray coupling. Null means nothing happens.
     */
    fun faultFor(a: Pad, b: Pad): FaultEntry? = faultsByPair[pairKey(a.id, b.id)] ?: stray(a, b)

    /**
     * Pads that were never meant to meet still disturb the stages they sit on. Which
     * fault a pair wakes, and how hard, is fixed by the pair, so it is the same every
     * time and can be learnt. Two pads that belong to no stage do nothing.
     */
    private fun stray(a: Pad, b: Pad): FaultEntry? {
        if (crosstalk <= 0f || a.contact || b.contact) return null
        val stages = setOfNotNull(a.stage, b.stage)
        val candidates = Faults.ALL.filter { it.stage in stages }
        if (candidates.isEmpty()) return null
        var h = pairKey(a.id, b.id).hashCode()
        h = (h xor (h ushr 16)) * -0x7a143595
        h = h xor (h ushr 13)
        val param = candidates[(h and 0x7FFFFFFF) % candidates.size]
        val max = crosstalk * (0.45f + 0.55f * (h ushr 8 and 0xFF) / 255f)
        val value = max * 0.7f
        return FaultEntry(a.id, b.id, param, crash = false, min = minOf(0.05f, value), max = max, value = value, stray = true)
    }

    fun contactFor(pad: Pad): ContactEntry? = contactsByPad[pad.id]

    internal companion object {
        fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"
    }
}

/** What the viewfinder overlays on the picture, and the noise the shutter makes. */
data class ViewfinderSpec(
    /** "mono", "sans", or "serif". */
    val osdFont: String,
    val osdColor: Long,
    /**
     * Text for each corner, keyed "topLeft", "topRight", "bottomLeft", "bottomRight".
     * May contain {shots} (frames left), {timer} (running time), and {date}.
     */
    val osd: Map<String, String>,
    /** How many frames the counter starts at. */
    val shots: Int,
    val shutter: ShutterSpec,
    val boot: BootSpec,
) {
    companion object {
        val CORNERS = setOf("topLeft", "topRight", "bottomLeft", "bottomRight")
        val FONTS = setOf("mono", "sans", "serif")
    }
}

/** What the viewfinder shows while the camera starts up, and again after a crash. */
data class BootSpec(
    /** How long start-up takes. 0 for a camera that is simply on. */
    val seconds: Float,
    val background: Long,
    /** Text shown one line after another; the first is the largest. */
    val lines: List<String>,
    /** How the start-up screen gives way to the picture: "fade", "wipe", or "blink". */
    val exit: String,
) {
    companion object {
        val EXITS = setOf("fade", "wipe", "blink")
    }
}

/** The shutter sound, synthesised: a burst of mechanical noise and an optional electronic beep. */
data class ShutterSpec(
    /** Length of the click in seconds. */
    val length: Float,
    /** Loudness of the mechanical noise, 0..1. */
    val noise: Float,
    /** Pitch of the beep in hertz, or 0 for none. */
    val beep: Float,
)

data class BoardSpec(
    val width: Float,
    val height: Float,
    /** Solder mask colour, ARGB. */
    val color: Long,
    /** Where the small live preview sits on the opened back. */
    val previewX: Float,
    val previewY: Float,
    val previewWidth: Float,
    val previewHeight: Float,
    val parts: List<Part>,
    /** Optional drawn art layers (image files in the body folder), bottom first. */
    val art: List<String>,
)

/** A component or marking on the board. [type] picks how it is drawn. */
data class Part(
    val type: String,
    val label: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** The stage this part implements, for traces and signal LEDs. */
    val stage: StageId?,
) {
    companion object {
        val TYPES = setOf("ic", "cap", "xtal", "r", "c", "conn", "led", "sticker", "silk", "logo")
    }
}

data class Pad(
    val id: String,
    /** Silkscreen text. Never an effect name. */
    val label: String,
    val x: Float,
    val y: Float,
    val stage: StageId?,
    /** True for a bare metal contact that responds to a finger rather than a wire. */
    val contact: Boolean,
)

/** What bridging two pads does. */
data class FaultEntry(
    val padA: String,
    val padB: String,
    /** The fault parameter driven, or null for a pair that crashes the camera. */
    val param: FaultParam?,
    val crash: Boolean,
    /** Range a pot sweeps. */
    val min: Float,
    val max: Float,
    /** Intensity of a bare wire with no pot. */
    val value: Float,
    /** True for a coupling the body's table does not list, worked out from the pads. */
    val stray: Boolean = false,
)

/** What holding a finger on a contact pad does. */
data class ContactEntry(
    val pad: String,
    val param: FaultParam,
    val max: Float,
)

class BodyFormatException(val bodyId: String, val problems: List<String>) :
    Exception("Body '$bodyId' is invalid:\n" + problems.joinToString("\n") { "  - $it" })
