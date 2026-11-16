package app.shortcam.engine

/** The six stages of the simulated camera, in signal order. */
enum class StageId(val label: String) {
    SENSOR("Sensor readout"),
    ADC("A/D converter"),
    MEMORY("Frame memory"),
    ENCODER("Colour encoder"),
    SYNC("Clock and sync"),
    COMPRESSOR("Compressor"),
}

/** One numeric fault parameter on one stage. 0 is healthy, 1 is the hardest fault. */
class FaultParam internal constructor(
    val stage: StageId,
    /** Stable identifier, used by body fault tables and saved patches. Also the shader uniform. */
    val key: String,
    val label: String,
)

internal class StageDef(
    val id: StageId,
    val fragment: String,
    /** True if the stage reads its own previous output (uPrev). */
    val usesHistory: Boolean,
    labels: List<Pair<String, String>>,
) {
    val params: List<FaultParam> = labels.map { (uniform, label) -> FaultParam(id, uniform, label) }
}

object Faults {
    internal val STAGES: List<StageDef> = listOf(
        StageDef(
            StageId.SENSOR, Shaders.SENSOR, false,
            listOf("uLineSkip" to "line skip", "uColumnShift" to "column shift", "uSmear" to "smear", "uStuck" to "stuck pixels", "uWobble" to "wobble"),
        ),
        StageDef(
            StageId.ADC, Shaders.ADC, false,
            listOf("uBitCrush" to "bit crush", "uBitSwap" to "bit swap", "uFold" to "fold/invert", "uChannelDrop" to "channel drop"),
        ),
        StageDef(
            StageId.MEMORY, Shaders.MEMORY, true,
            listOf("uAddrShort" to "address short", "uStale" to "stale blocks", "uTrails" to "trails", "uFeedback" to "feedback", "uMirror" to "mirror"),
        ),
        StageDef(
            StageId.ENCODER, Shaders.ENCODER, false,
            listOf("uChromaSwap" to "chroma swap", "uChromaDelay" to "chroma delay", "uChromaDrift" to "chroma drift", "uHueSpin" to "hue spin", "uColorKill" to "colour kill"),
        ),
        StageDef(
            StageId.SYNC, Shaders.SYNC, true,
            listOf("uTear" to "tear", "uRoll" to "roll", "uStarve" to "starve", "uTracking" to "tracking", "uComb" to "field slip", "uDropout" to "dropouts"),
        ),
        StageDef(
            StageId.COMPRESSOR, Shaders.COMPRESSOR, false,
            listOf("uBlockCorrupt" to "block corrupt", "uQuant" to "quantise"),
        ),
    )

    /** Every fault parameter, in stage order. */
    val ALL: List<FaultParam> = STAGES.flatMap { it.params }

    fun find(key: String): FaultParam? = ALL.firstOrNull { it.key == key }
}
