package app.shortcam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shortcam.board.BoardState
import app.shortcam.board.ModStore

private val Mono = TextStyle(color = Color(0xFFE8E8E0), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
private val Dim = Mono.copy(color = Color(0xFF8E8E86), fontSize = 12.sp)
private val Go = Mono.copy(color = Color(0xFFE8A13A))

/**
 * Saved mods for the body in hand: name and save the current patch, load or delete a
 * saved one, load the patch stored in a photo, or pull every wire.
 */
@Composable
fun ModsPanel(
    board: BoardState,
    store: ModStore,
    onLoadFromPhoto: () -> Unit,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val bodyId = board.body.id
    var mods by remember { mutableStateOf(store.list(bodyId)) }
    var name by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0101012))
            .clickable(enabled = false) {}
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("MODS  ·  ${board.body.model}", style = Mono, modifier = Modifier.weight(1f))
            BasicText("[ back ]", style = Go, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }

        BasicText("save this patch as", style = Dim, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                singleLine = true,
                textStyle = Mono,
                cursorBrush = SolidColor(Color(0xFFE8A13A)),
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF26262A))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
            BasicText(
                "[ save ]",
                style = Go,
                modifier = Modifier
                    .clickable {
                        val trimmed = name.trim()
                        if (trimmed.isEmpty()) {
                            onMessage("Give the mod a name first")
                        } else {
                            store.save(bodyId, trimmed, board.toPatch())
                            mods = store.list(bodyId)
                            name = ""
                            onMessage("Saved \"$trimmed\"")
                        }
                    }
                    .padding(10.dp),
            )
        }

        BasicText("saved for this camera", style = Dim, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
        if (mods.isEmpty()) BasicText("none yet", style = Dim, modifier = Modifier.padding(vertical = 8.dp))
        for (mod in mods) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    mod,
                    style = Mono,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            val patch = store.load(bodyId, mod)
                            if (patch != null && board.applyPatch(patch)) {
                                onMessage("Loaded \"$mod\"")
                                onClose()
                            } else {
                                onMessage("Could not load \"$mod\"")
                            }
                        }
                        .padding(vertical = 12.dp),
                )
                BasicText(
                    "[ delete ]",
                    style = Dim,
                    modifier = Modifier
                        .clickable {
                            store.delete(bodyId, mod)
                            mods = store.list(bodyId)
                        }
                        .padding(10.dp),
                )
            }
        }

        BasicText(
            "[ load patch from a photo ]",
            style = Go,
            modifier = Modifier.padding(top = 22.dp).clickable(onClick = onLoadFromPhoto).padding(vertical = 10.dp),
        )
        BasicText(
            "[ pull all wires ]",
            style = Go,
            modifier = Modifier
                .clickable {
                    board.clear()
                    onClose()
                }
                .padding(vertical = 10.dp),
        )
    }
}
