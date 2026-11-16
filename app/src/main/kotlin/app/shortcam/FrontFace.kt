package app.shortcam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shortcam.bodies.Body
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The outside of the camera: space for the viewfinder (drawn by the live view above
 * this), then the body with its printed markings, the shutter, the record button, and
 * the catch that opens the back.
 */
@Composable
fun FrontFace(
    body: Body,
    /** In landscape the viewfinder is on the left and the body beside it; in portrait it is on top. */
    landscape: Boolean,
    /** Space to leave for the viewfinder: its width in landscape, its height in portrait. */
    viewfinderExtent: Dp,
    recording: Boolean,
    frontLens: Boolean,
    modeLabel: String,
    zoom: Float,
    last: LastCapture,
    onZoom: (Float) -> Unit,
    onSwitchMode: () -> Unit,
    onShutter: () -> Unit,
    onRecord: () -> Unit,
    onGallery: () -> Unit,
    onSwitchLens: () -> Unit,
    onOpen: () -> Unit,
    onShelf: () -> Unit,
) {
    val shell = Color(body.shellColor)
    val ink = Color(body.inkColor)
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(
                    start = if (landscape) viewfinderExtent else 0.dp,
                    top = if (landscape) 0.dp else viewfinderExtent,
                )
                .background(
                    Brush.verticalGradient(
                        listOf(lerp(shell, Color.White, 0.18f), shell, lerp(shell, Color.Black, 0.32f))
                    )
                )
        ) {
            // With room to spare the controls grow and the shell gets its hardware:
            // a dial round the shutter, a speaker grille, screws. A cramped shell stays plain.
            val roomy = maxHeight > 270.dp && maxWidth > 300.dp
            val shutter = if (roomy) (minOf(maxWidth, maxHeight) * 0.27f).coerceIn(84.dp, 128.dp) else 78.dp
            val dial = shutter * 1.5f
            val record = if (roomy) shutter * 0.56f else 46.dp
            val recordOffset = -((if (roomy) dial else shutter) / 2 + record / 2 + 16.dp)

            Box(Modifier.fillMaxSize().drawBehind { shellHardware(shell, roomy, 78.dp.toPx()) })

            Column(Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 18.dp)) {
                BasicText(
                    body.maker,
                    style = TextStyle(
                        color = ink, fontSize = 22.sp, fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic, letterSpacing = 2.sp,
                    ),
                )
                BasicText(
                    body.model.removePrefix(body.maker).trim(),
                    style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                for (line in body.printed) {
                    BasicText(
                        line,
                        style = TextStyle(
                            color = Color(body.accentColor), fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        ),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }

            if (roomy) Canvas(Modifier.align(Alignment.Center).size(dial)) { dialRing() }
            ShutterButton(shutter, onShutter, Modifier.align(Alignment.Center))
            RecordButton(record, recording, onRecord, Modifier.align(Alignment.Center).offset(x = recordOffset))
            GalleryThumb(if (roomy) 64.dp else 52.dp, last, onGallery, Modifier.align(Alignment.BottomEnd).padding(18.dp))

            if (body.zoom > 1f) {
                ZoomRocker(zoom, body.zoom, onZoom, Modifier.align(Alignment.BottomCenter).padding(bottom = if (roomy) 30.dp else 12.dp))
            }
            if (roomy) {
                // Stamped into the shell, as on the base of a real one.
                BasicText(
                    "${body.serial}   ${body.madeIn}",
                    style = TextStyle(
                        color = ink.copy(alpha = 0.45f), fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp, letterSpacing = 1.sp,
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp),
                )
            }

            Column(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp), horizontalAlignment = Alignment.End) {
                val small = TextStyle(color = ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                // Lens selector: the camera facing away, or the one facing you.
                BasicText(
                    if (frontLens) "⟲ SELFIE" else "⟲ REAR",
                    style = small,
                    modifier = Modifier.clickable(onClick = onSwitchLens).padding(horizontal = 10.dp, vertical = 5.dp),
                )
                // What the shutter takes. Each tap moves to the next mode.
                BasicText(
                    modeLabel,
                    style = small,
                    modifier = Modifier.clickable(onClick = onSwitchMode).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            // Catch for the back cover.
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2A2C30))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    "OPEN",
                    style = TextStyle(color = Color(0xFFE8E8E0), fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
                BasicText("▸▸", style = TextStyle(color = Color(0xFFE8A13A), fontSize = 12.sp))
            }

            BasicText(
                "◂ SHELF",
                style = TextStyle(color = ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 12.dp)
                    .clickable(onClick = onShelf)
                    .padding(10.dp),
            )
        }
    }
}

/** The moulded edge of the shell and, where there is room, its screws and speaker grille. */
private fun DrawScope.shellHardware(shell: Color, roomy: Boolean, grilleTop: Float) {
    val light = lerp(shell, Color.White, 0.45f)
    val dark = lerp(shell, Color.Black, 0.55f)
    val d = density
    // A bevel round the edge: lit from above.
    drawLine(light, Offset(0f, 1.5f * d), Offset(size.width, 1.5f * d), 3f * d)
    drawLine(dark, Offset(0f, size.height - 1.5f * d), Offset(size.width, size.height - 1.5f * d), 3f * d)
    if (!roomy) return

    // A screw in each corner, slots at odd angles as the factory left them.
    val inset = 11f * d
    val corners = listOf(
        Offset(inset, inset) to 0.5f,
        Offset(size.width - inset, inset) to 2.1f,
        Offset(inset, size.height - inset) to 1.2f,
        Offset(size.width - inset, size.height - inset) to 2.7f,
    )
    for ((at, angle) in corners) {
        drawCircle(dark, 4.6f * d, at)
        drawCircle(lerp(shell, Color.White, 0.25f), 3.6f * d, at)
        val arm = Offset(cos(angle), sin(angle)) * (3.2f * d)
        drawLine(dark, at - arm, at + arm, 1.2f * d)
    }

    // Speaker grille down the left side, between the printing and the bottom edge.
    val pitch = 9f * d
    val left = 30f * d
    var y = size.height / 2 - pitch * 1.5f
    if (y < grilleTop + 40f * d) y = grilleTop + 40f * d
    repeat(4) { row ->
        repeat(5) { col ->
            val at = Offset(left + col * pitch + if (row % 2 == 1) pitch / 2 else 0f, y + row * pitch)
            if (at.y < size.height - 70f * d) {
                drawCircle(dark, 2.3f * d, at)
                drawCircle(light.copy(alpha = 0.5f), 2.3f * d, at + Offset(0f, 0.8f * d), style = androidx.compose.ui.graphics.drawscope.Stroke(0.6f * d))
            }
        }
    }
}

/** The knurled dial the shutter sits in. */
private fun DrawScope.dialRing() {
    val r = size.minDimension / 2
    drawCircle(Color(0x55000000), r, center + Offset(0f, r * 0.05f))
    drawCircle(Brush.verticalGradient(listOf(Color(0xFF4A4C51), Color(0xFF1B1C1F))), r * 0.98f)
    // Knurling round the rim.
    val ticks = 72
    for (i in 0 until ticks) {
        val a = (i * 2 * PI / ticks).toFloat()
        val dir = Offset(cos(a), sin(a))
        drawLine(Color(0x66FFFFFF), center + dir * (r * 0.84f), center + dir * (r * 0.96f), r * 0.018f)
    }
    drawCircle(Brush.verticalGradient(listOf(Color(0xFF2A2C30), Color(0xFF3E4045))), r * 0.80f)
}
