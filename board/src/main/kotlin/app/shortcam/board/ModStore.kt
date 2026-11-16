package app.shortcam.board

import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * Saved mods: named patches kept as JSON files in app storage, one folder per body.
 * A mod belongs to the body it was made on and is only ever listed for that body.
 */
class ModStore(filesDir: File) {
    private val root = File(filesDir, "mods")

    /** Names of the mods saved for a body, in alphabetical order. */
    fun list(bodyId: String): List<String> =
        folder(bodyId).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { read(it)?.optString("name")?.takeIf { name -> name.isNotEmpty() } }
            .sortedBy { it.lowercase() }

    /** Saves a patch under a name, replacing any mod of the same name. */
    fun save(bodyId: String, name: String, patch: JSONObject) {
        val folder = folder(bodyId).apply { mkdirs() }
        File(folder, fileName(name)).writeText(JSONObject(patch.toString()).put("name", name).toString(2))
    }

    fun load(bodyId: String, name: String): JSONObject? = read(File(folder(bodyId), fileName(name)))

    fun delete(bodyId: String, name: String) {
        File(folder(bodyId), fileName(name)).delete()
    }

    private fun folder(bodyId: String) = File(root, bodyId.replace(Regex("[^A-Za-z0-9_-]"), "_"))
    private fun fileName(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".json"

    private fun read(file: File): JSONObject? = try {
        if (file.isFile) JSONObject(file.readText()) else null
    } catch (e: JSONException) {
        null
    }
}
