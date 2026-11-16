package app.shortcam.bodies

import android.content.res.AssetManager
import app.shortcam.engine.EngineConfig
import app.shortcam.engine.Faults
import app.shortcam.engine.StageId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Reads and validates body packages from `assets/bodies`. */
object BodyLoader {
    private const val ROOT = "bodies"
    const val FORMAT = 1

    /** Ids of every body package shipped with the app. */
    fun list(assets: AssetManager): List<String> =
        assets.list(ROOT).orEmpty().filter { id -> assets.list("$ROOT/$id").orEmpty().contains("body.json") }.sorted()

    /** Loads one body. Throws [BodyFormatException] listing every problem found. */
    fun load(assets: AssetManager, id: String): Body {
        val text = assets.open("$ROOT/$id/body.json").bufferedReader().use { it.readText() }
        return parse(id, text)
    }

    fun parse(id: String, text: String): Body {
        val problems = ArrayList<String>()
        val body = try {
            build(id, JSONObject(text), problems)
        } catch (e: JSONException) {
            problems += "malformed JSON: ${e.message}"
            null
        }
        if (body == null || problems.isNotEmpty()) throw BodyFormatException(id, problems)
        return body
    }

    private fun build(id: String, json: JSONObject, problems: MutableList<String>): Body {
        if (json.optInt("format") != FORMAT) problems += "format must be $FORMAT"

        val stages = json.getJSONArray("stages").strings().mapNotNull { name ->
            stage(name).also { if (it == null) problems += "unknown stage '$name'" }
        }.toSet()
        if (stages.isEmpty()) problems += "a body needs at least one stage"

        val sensor = json.getJSONObject("sensor")
        val preview = json.getJSONObject("preview")
        val engine = EngineConfig(
            sensorWidth = sensor.getInt("width"),
            sensorHeight = sensor.getInt("height"),
            previewWidth = preview.getInt("width"),
            previewHeight = preview.getInt("height"),
            stages = stages,
            stability = json.optDouble("stability", 0.8).toFloat(),
            bitDepth = sensor.optInt("bits", 8),
            chromaSoftness = json.optDouble("chromaSoftness", 0.0).toFloat(),
        )
        if (engine.bitDepth !in 1..8) problems += "sensor bits must be between 1 and 8"
        if (engine.chromaSoftness !in 0f..1f) problems += "chromaSoftness must be between 0 and 1"
        if (engine.stability !in 0f..1f) problems += "stability must be between 0 and 1"
        if (engine.sensorWidth < engine.sensorHeight) problems += "sensor size must be given in landscape"
        if (engine.previewWidth > engine.sensorWidth) problems += "preview is larger than the sensor"

        val b = json.getJSONObject("board")
        val window = b.getJSONObject("preview")
        val board = BoardSpec(
            width = b.getDouble("width").toFloat(),
            height = b.getDouble("height").toFloat(),
            color = b.getString("color").removePrefix("#").toLong(16) or 0xFF000000,
            previewX = window.getDouble("x").toFloat(),
            previewY = window.getDouble("y").toFloat(),
            previewWidth = window.getDouble("w").toFloat(),
            previewHeight = window.getDouble("h").toFloat(),
            parts = b.getJSONArray("parts").objects().map { p ->
                val type = p.getString("type")
                if (type !in Part.TYPES) problems += "unknown part type '$type'"
                Part(
                    type = type,
                    label = p.optString("label"),
                    x = p.getDouble("x").toFloat(),
                    y = p.getDouble("y").toFloat(),
                    w = p.optDouble("w", 0.0).toFloat(),
                    h = p.optDouble("h", 0.0).toFloat(),
                    stage = p.optString("stage").takeIf { it.isNotEmpty() }?.let { name ->
                        stage(name).also { if (it == null) problems += "part has unknown stage '$name'" }
                    },
                )
            },
            art = b.optJSONArray("art")?.strings().orEmpty(),
        )

        val pads = json.getJSONArray("pads").objects().map { p ->
            val padId = p.getString("id")
            val stageName = p.optString("stage")
            val padStage = stageName.takeIf { it.isNotEmpty() }?.let { stage(it) }
            if (stageName.isNotEmpty() && padStage == null) problems += "pad $padId has unknown stage '$stageName'"
            if (padStage != null && padStage !in stages) problems += "pad $padId is on stage $padStage, which this body lacks"
            Pad(
                id = padId,
                label = p.optString("label", padId),
                x = p.getDouble("x").toFloat(),
                y = p.getDouble("y").toFloat(),
                stage = padStage,
                contact = p.optBoolean("contact"),
            ).also {
                if (it.x !in 0f..board.width || it.y !in 0f..board.height) problems += "pad $padId is off the board"
            }
        }
        pads.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { problems += "duplicate pad id $it" }
        val padsById = pads.associateBy { it.id }

        fun param(key: String, where: String) = Faults.find(key).also {
            if (it == null) problems += "$where: unknown fault parameter '$key'"
            else if (it.stage !in stages) problems += "$where: '$key' is on stage ${it.stage}, which this body lacks"
        }

        val seenPairs = HashSet<String>()
        val faults = json.getJSONArray("faults").objects().mapNotNull { f ->
            val pair = f.getJSONArray("pads").strings()
            val where = "fault ${pair.joinToString("-")}"
            if (pair.size != 2 || pair[0] == pair[1]) {
                problems += "$where: needs two different pads"
                return@mapNotNull null
            }
            for (padId in pair) {
                val pad = padsById[padId]
                if (pad == null) problems += "$where: no pad '$padId'"
                else if (pad.contact) problems += "$where: '$padId' is a contact pad and cannot take a wire"
            }
            if (!seenPairs.add(Body.pairKey(pair[0], pair[1]))) problems += "$where: pair listed twice"

            val crash = f.optBoolean("crash")
            val key = f.optString("param")
            if (crash == key.isNotEmpty()) problems += "$where: needs exactly one of 'param' or 'crash'"
            val min = f.optDouble("min", 0.0).toFloat()
            val max = f.optDouble("max", 1.0).toFloat()
            val value = f.optDouble("value", ((min + max) / 2).toDouble()).toFloat()
            if (!(0f <= min && min <= value && value <= max && max <= 1f)) {
                problems += "$where: needs 0 <= min <= value <= max <= 1"
            }
            FaultEntry(pair[0], pair[1], if (key.isNotEmpty()) param(key, where) else null, crash, min, max, value)
        }

        val contacts = json.optJSONArray("contacts")?.objects().orEmpty().mapNotNull { c ->
            val padId = c.getString("pad")
            val where = "contact $padId"
            val pad = padsById[padId]
            if (pad == null) problems += "$where: no such pad"
            else if (!pad.contact) problems += "$where: pad is not marked as a contact"
            val p = param(c.getString("param"), where) ?: return@mapNotNull null
            ContactEntry(padId, p, c.optDouble("max", 1.0).toFloat())
        }
        pads.filter { it.contact && contacts.none { c -> c.pad == it.id } }
            .forEach { problems += "contact pad ${it.id} has no entry in 'contacts'" }

        val vf = json.optJSONObject("viewfinder") ?: JSONObject()
        val osd = vf.optJSONObject("osd") ?: JSONObject()
        val sound = vf.optJSONObject("shutter") ?: JSONObject()
        val viewfinder = ViewfinderSpec(
            osdFont = vf.optString("font", "mono"),
            osdColor = vf.optString("color", "#FFFFFF").removePrefix("#").toLong(16) or 0xFF000000,
            osd = osd.keys().asSequence().associateWith { osd.getString(it) },
            shots = vf.optInt("shots", 99),
            shutter = ShutterSpec(
                length = sound.optDouble("length", 0.08).toFloat(),
                noise = sound.optDouble("noise", 0.6).toFloat(),
                beep = sound.optDouble("beep", 0.0).toFloat(),
            ),
            boot = (vf.optJSONObject("boot") ?: JSONObject()).let { boot ->
                BootSpec(
                    seconds = boot.optDouble("seconds", 0.0).toFloat(),
                    background = boot.optString("background", "#000000").removePrefix("#").toLong(16) or 0xFF000000,
                    lines = boot.optJSONArray("lines")?.strings().orEmpty(),
                    exit = boot.optString("exit", "fade"),
                )
            },
        )
        if (viewfinder.boot.seconds !in 0f..5f) problems += "boot seconds must be between 0 and 5"
        if (viewfinder.boot.exit !in BootSpec.EXITS) problems += "boot exit must be one of ${BootSpec.EXITS}"
        val zoom = json.optDouble("zoom", 1.0).toFloat()
        if (zoom !in 1f..20f) problems += "zoom must be between 1 and 20"
        val crosstalk = json.optDouble("crosstalk", 0.6).toFloat()
        if (crosstalk !in 0f..1f) problems += "crosstalk must be between 0 and 1"
        if (viewfinder.osdFont !in ViewfinderSpec.FONTS) problems += "viewfinder font must be one of ${ViewfinderSpec.FONTS}"
        (viewfinder.osd.keys - ViewfinderSpec.CORNERS).forEach { problems += "unknown viewfinder corner '$it'" }
        if (viewfinder.shutter.length !in 0.01f..1f) problems += "shutter length must be between 0.01 and 1 second"

        return Body(
            id = id,
            viewfinder = viewfinder,
            zoom = zoom,
            crosstalk = crosstalk,
            maker = json.getString("maker"),
            model = json.getString("model"),
            printed = json.optJSONArray("printed")?.strings().orEmpty(),
            serial = json.getString("serial"),
            madeIn = json.getString("madeIn"),
            shellColor = json.optString("shell", "#C4C6CA").removePrefix("#").toLong(16) or 0xFF000000,
            inkColor = json.optString("ink", "#26282C").removePrefix("#").toLong(16) or 0xFF000000,
            accentColor = json.optString("accent", json.optString("ink", "#26282C")).removePrefix("#").toLong(16) or 0xFF000000,
            engine = engine,
            board = board,
            pads = pads,
            faults = faults,
            contacts = contacts,
        )
    }

    private fun stage(name: String): StageId? = StageId.entries.firstOrNull { it.name == name }
    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
}
