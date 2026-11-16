package app.shortcam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shortcam.bodies.ViewfinderSpec

/**
 * The camera starting up, laid over the viewfinder: a moment of black, the body's
 * start-up screen with its lines arriving one by one, then the picture. It plays when
 * a body is picked up and again each time [run] changes, which is after every crash.
 */
@Composable
fun BootScreen(spec: ViewfinderSpec, run: Int, modifier: Modifier = Modifier) {
    val boot = spec.boot
    if (boot.seconds <= 0f) return
    val progress = remember { Animatable(0f) }
    LaunchedEffect(run) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween((boot.seconds * 1000).toInt(), easing = LinearEasing))
    }
    val p = progress.value
    if (p >= 1f) return

    // How far the start-up screen has given way to the picture, 0..1.
    val leaving = ((p - EXIT_AT) / (1f - EXIT_AT)).coerceIn(0f, 1f)
    val shaped = when (boot.exit) {
        "wipe" -> Modifier.drawWithContent { clipRect(top = size.height * leaving) { this@drawWithContent.drawContent() } }
        "blink" -> Modifier.graphicsLayer { alpha = if ((leaving * 6).toInt() % 2 == 1) 0f else 1f }
        else -> Modifier.graphicsLayer { alpha = 1f - leaving }
    }

    BoxWithConstraints(modifier.then(shaped).background(Color.Black)) {
        if (p < DARK_UNTIL) return@BoxWithConstraints
        val family = when (spec.osdFont) {
            "sans" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            else -> FontFamily.Monospace
        }
        // Text is sized to the screen it is on, so the small LCD on the back reads the same.
        val unit = with(LocalDensity.current) { (maxWidth / 12).toSp() }
        val shownLines = ((p - DARK_UNTIL) / (EXIT_AT - DARK_UNTIL) * (boot.lines.size + 0.5f)).toInt() + 1
        Column(
            Modifier.fillMaxSize().background(Color(boot.background)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            boot.lines.forEachIndexed { i, line ->
                BasicText(
                    line,
                    style = TextStyle(
                        color = if (i < shownLines) Color(spec.osdColor) else Color.Transparent,
                        fontFamily = family,
                        fontSize = if (i == 0) unit * 2.2f else unit * 0.62f,
                        fontWeight = if (i == 0) FontWeight.Black else FontWeight.Bold,
                        fontStyle = if (i == 0) FontStyle.Italic else FontStyle.Normal,
                        letterSpacing = unit * 0.12f,
                    ),
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 6.dp),
                )
            }
        }
    }
}

private const val DARK_UNTIL = 0.12f
private const val EXIT_AT = 0.78f
