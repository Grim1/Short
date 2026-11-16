package app.shortcam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/** App settings, kept as a JSON file in app storage. */
class Settings(filesDir: File) {
    private val file = File(filesDir, "settings.json")
    private val stored: JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (e: JSONException) {
        JSONObject()
    }

    /** Save the unbent frame beside each bent photo. Off unless turned on. */
    var cleanCopy by mutableStateOf(stored.optBoolean("cleanCopy", false))
        private set

    fun toggleCleanCopy() {
        cleanCopy = !cleanCopy
        file.writeText(stored.put("cleanCopy", cleanCopy).toString(2))
    }
}
