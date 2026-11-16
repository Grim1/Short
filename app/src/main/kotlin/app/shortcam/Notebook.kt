package app.shortcam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shortcam.bodies.Body
import app.shortcam.bodies.Pad
import java.io.File
import org.json.JSONArray

/**
 * What has been found out about one body's board: every pad pair that turned out to do
 * something, and every contact pad that was touched, in the order they were found.
 * It fills in by itself and is kept between runs of the app.
 */
class Notebook(dir: File, val body: Body) {
    private val file = File(File(dir, "notes"), "${body.id}.json")

    /** "A|B" for a pad pair, or a contact pad's id. */
    val found = mutableStateListOf<String>()

    init {
        runCatching {
            val list = JSONArray(file.readText())
            for (i in 0 until list.length()) found += list.getString(i)
        }
    }

    /** Notes a bridged pair, if bridging it does anything. */
    fun bridged(a: Pad, b: Pad) {
        if (body.faultFor(a, b) != null) add(if (a.id < b.id) "${a.id}|${b.id}" else "${b.id}|${a.id}")
    }

    /** Notes a contact pad that was touched. */
    fun touched(padId: String) {
        if (body.pad(padId)?.let { body.contactFor(it) } != null) add(padId)
    }

    private fun add(key: String) {
        if (key in found) return
        found += key
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONArray(found.toList()).toString())
        }
    }

    /** The pads of an entry, as written on the board, and what was seen. Null if the body no longer has them. */
    fun read(key: String): Pair<String, String>? {
        val ids = key.split('|')
        val pads = ids.map { body.pad(it) ?: return null }
        if (pads.size == 1) {
            val contact = body.contactFor(pads[0]) ?: return null
            return pads[0].label to "finger on it: " + (SEEN[contact.param.key] ?: contact.param.label)
        }
        val entry = body.faultFor(pads[0], pads[1]) ?: return null
        val seen = when {
            entry.crash -> "KILLS IT. Reboots after."
            else -> (SEEN[entry.param?.key] ?: entry.param?.label.orEmpty()) + if (entry.max < 0.45f) " (faint)" else ""
        }
        return "${pads[0].label} ~ ${pads[1].label}" to seen
    }

    private companion object {
        /** What each fault looks like, as someone at the bench would jot it down. */
        val SEEN = mapOf(
            "uLineSkip" to "lines repeat",
            "uColumnShift" to "columns slide sideways",
            "uSmear" to "bright bits streak",
            "uBitCrush" to "colours go in steps",
            "uBitSwap" to "colours scramble",
            "uFold" to "brights fold over, go negative",
            "uAddrShort" to "picture repeats in tiles",
            "uStale" to "old blocks stick",
            "uTrails" to "ghost trails",
            "uChromaSwap" to "colours swap places",
            "uChromaDelay" to "colour slides off the picture",
            "uChromaDrift" to "colour crawls, rainbows",
            "uTear" to "picture tears sideways",
            "uRoll" to "picture rolls",
            "uStarve" to "picture hangs in fits, lines drop out",
            "uTracking" to "tracking bars",
            "uWobble" to "picture sways like jelly",
            "uChannelDrop" to "one colour drops out in bands",
            "uMirror" to "picture folds onto itself",
            "uColorKill" to "colour cuts out, goes grey",
            "uStuck" to "pixels and columns stuck on",
            "uFeedback" to "picture tunnels into itself",
            "uHueSpin" to "all the colours spin round",
            "uComb" to "every other line lags, edges comb",
            "uDropout" to "white streaks, torn bottom edge",
            "uBlockCorrupt" to "blocks break up",
            "uQuant" to "goes chunky",
        )
    }
}

private val Ink = Color(0xFF2B3A6B)
private val Rule = Color(0x554F7FB8)

/** The body's notebook as a ruled page of pencilled notes. */
@Composable
fun NotebookPanel(notebook: Notebook, onClose: () -> Unit) {
    val hand = TextStyle(color = Ink, fontFamily = FontFamily.Cursive, fontSize = 21.sp)
    val pads = TextStyle(color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3EED9))
            .clickable(enabled = false) {}
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                notebook.body.model.removePrefix(notebook.body.maker).trim() + " notes",
                style = hand.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            BasicText("[ back ]", style = pads.copy(color = Color(0xFF8C1D18)), modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        val lines = notebook.found.mapNotNull { notebook.read(it) }
        BasicText(
            if (lines.isEmpty()) "Nothing yet. Join two pads with a wire and watch the picture."
            else "${lines.size} found so far",
            style = hand.copy(fontSize = 17.sp, color = Color(0xFF6B6B60)),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        for ((where, seen) in lines) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(Rule, Offset(0f, size.height), Offset(size.width, size.height), 1.5f) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(where, style = pads, modifier = Modifier.width(118.dp))
                BasicText(seen, style = hand, modifier = Modifier.weight(1f))
            }
        }
    }
}
