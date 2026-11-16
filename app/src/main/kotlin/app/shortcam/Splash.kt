package app.shortcam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * What plays when the app starts, on a screen that switches on like an old tube: a
 * lens is drawn, a wire is soldered across it, and the moment it lands the picture
 * shorts out, tearing and splitting into colours, before settling into the name.
 * A tap skips it. [onDone] runs when it has finished.
 */
@Composable
fun MakerSplash(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(3200, easing = LinearEasing))
        onDone()
    }
    val measurer = rememberTextMeasurer()
    val title = remember {
        measurer.measure(
            "SHORT",
            TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = 12.sp),
        )
    }
    val sub = remember {
        measurer.measure("CIRCUIT-BENT CAMERA", TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp))
    }
    val maker = remember {
        measurer.measure("CSE  ·  COLD SOLDER ELECTRIC CO.", TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp))
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone)
    ) {
        val p = progress.value
        val d = density

        // The tube opens from a bright line and, at the end, collapses back to one.
        val opening = (p / T_OPEN).coerceIn(0f, 1f)
        val closing = ((p - T_CLOSE) / (1f - T_CLOSE)).coerceIn(0f, 1f)
        val aperture = (opening * opening) * (1f - closing * closing)
        val half = maxOf(size.height / 2 * aperture, 1.5f * d)
        if (opening < 1f || closing > 0f) {
            drawRect(Color.White.copy(alpha = 0.9f * (1f - aperture)), Offset(0f, center.y - 1.5f * d), Size(size.width, 3f * d))
        }

        clipRect(top = center.y - half, bottom = center.y + half) {
            drawRect(Color(0xFF0C1114))

            // The short: strongest the instant the wire lands, dying away after.
            val shorted = if (p >= T_SHORT) (1f - (p - T_SHORT) / SHORT_LENGTH).coerceIn(0f, 1f) else 0f
            val tick = (p * 45).toInt()
            if (shorted > 0f) {
                // Torn into bands, each thrown sideways, with the colours pulled apart.
                val bands = 11
                val bandHeight = size.height / bands
                for (i in 0 until bands) {
                    val shove = (noise(i, tick) - 0.5f) * 2f * shorted * shorted * 90f * d
                    val split = shorted * 7f * d
                    clipRect(top = i * bandHeight, bottom = (i + 1) * bandHeight) {
                        translate(left = shove - split) { scene(p, title, sub, maker, Color(0xFFFF2A3C)) }
                        translate(left = shove + split) { scene(p, title, sub, maker, Color(0xFF22E0FF)) }
                        translate(left = shove) { scene(p, title, sub, maker, null) }
                    }
                }
                if (shorted > 0.86f) drawRect(Color.White.copy(alpha = (shorted - 0.86f) * 6f))
            } else {
                scene(p, title, sub, maker, null)
            }

            // Scan lines, and a soft bar of brightness rolling up the tube.
            var y = 0f
            while (y < size.height) {
                drawRect(Color(0x30000000), Offset(0f, y), Size(size.width, 1.2f * d))
                y += 3.2f * d
            }
            val bar = size.height * (1f - (p * 2.3f) % 1f)
            drawRect(Color(0x0CFFFFFF), Offset(0f, bar), Size(size.width, 60f * d))
        }
    }
}

/**
 * The lens, the wire and the lettering as they stand at time [p]. With a [tint] the
 * whole scene is drawn in that one colour, for the split-colour ghosts.
 */
private fun DrawScope.scene(p: Float, title: TextLayoutResult, sub: TextLayoutResult, maker: TextLayoutResult, tint: Color?) {
    val d = density
    val ghost = tint?.copy(alpha = 0.55f)
    fun c(color: Color) = ghost ?: color
    val s = 2.6f * d                               // one icon unit, of the 108 the launcher icon is drawn in
    val lens = Offset(center.x, center.y - 70f * d)

    // The lens barrel is drawn round in one sweep, then the glass fills in.
    val sweep = ((p - T_LENS) / LENS_LENGTH).coerceIn(0f, 1f) * 360f
    if (sweep > 0f) {
        fun ring(radius: Float, width: Float, color: Color) = drawArc(
            c(color), -90f, sweep, false,
            Offset(lens.x - radius * s, lens.y - radius * s), Size(radius * 2 * s, radius * 2 * s),
            style = Stroke(width * s, cap = StrokeCap.Round),
        )
        ring(22f, 4f, Color(0xFFDADCE0))
        ring(17f, 3f, Color(0xFF6E7177))
        val glass = ((p - T_LENS - LENS_LENGTH * 0.7f) / (LENS_LENGTH * 0.5f)).coerceIn(0f, 1f)
        if (glass > 0f) {
            drawCircle(c(Color(0xFF31407A)).copy(alpha = glass * (ghost?.alpha ?: 1f)), 9f * s, lens)
            drawCircle(c(Color(0xFFB8C4F0)).copy(alpha = glass * 0.8f * (ghost?.alpha ?: 1f)), 3f * s, lens + Offset(-5f * s, -6f * s))
        }
    }

    // Two solder pads, then the wire run from one to the other.
    val from = lens + Offset(-24f * s, 22f * s)
    val to = lens + Offset(24f * s, -22f * s)
    val bend = lens + Offset(8f * s, 20f * s)
    if (p > T_WIRE - 0.05f) {
        drawCircle(c(Color(0xFFD8B24A)), 5f * s, from)
        drawCircle(c(Color(0xFFD8B24A)), 5f * s, to)
    }
    val run = ((p - T_WIRE) / (T_SHORT - T_WIRE)).coerceIn(0f, 1f)
    if (run > 0f) {
        var last = from
        val steps = 28
        for (i in 1..(steps * run).toInt()) {
            val t = i / steps.toFloat()
            val at = from * ((1 - t) * (1 - t)) + bend * (2 * t * (1 - t)) + to * (t * t)
            drawLine(c(Color(0xFFE0362B)), last, at, 4.5f * s, StrokeCap.Round)
            last = at
        }
        // The iron's tip glows at the end of the wire until the joint is made.
        if (run < 1f) drawCircle(c(Color(0xFFFFE9A8)), (3f + 2f * sin(p * 240f)) * s, last)
        else {
            drawCircle(c(Color(0xFFE9E9EC)), 2.6f * s, from)
            drawCircle(c(Color(0xFFE9E9EC)), 2.6f * s, to)
        }
    }

    // The name arrives with the short; the small print follows.
    if (p >= T_SHORT) {
        drawText(title, c(Color(0xFFF2F0E6)), Offset(center.x - title.size.width / 2f, center.y + 22f * d))
    }
    if (p >= T_SHORT + 0.10f) {
        drawText(sub, c(Color(0xFFE8A13A)), Offset(center.x - sub.size.width / 2f, center.y + 30f * d + title.size.height))
    }
    if (p >= T_SHORT + 0.16f) {
        drawText(maker, c(Color(0xFF8E8E86)), Offset(center.x - maker.size.width / 2f, size.height - 80f * d))
    }
}

/** A repeatable random number in 0..1 for band [i] at moment [tick]. */
private fun noise(i: Int, tick: Int): Float {
    var h = i * 374761393 + tick * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
}

private const val T_OPEN = 0.08f
private const val T_LENS = 0.10f
private const val LENS_LENGTH = 0.20f
private const val T_WIRE = 0.34f
private const val T_SHORT = 0.52f
private const val SHORT_LENGTH = 0.26f
private const val T_CLOSE = 0.95f
