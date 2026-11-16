package app.shortcam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shortcam.bodies.ViewfinderSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The body's on-screen display, laid over the viewfinder: whatever text its package
 * puts in each corner. It is part of the viewfinder only and is not saved into photos.
 */
@Composable
fun Osd(spec: ViewfinderSpec, shotsLeft: Int, recordingSeconds: Int, modifier: Modifier = Modifier) {
    val style = TextStyle(
        color = Color(spec.osdColor),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = when (spec.osdFont) {
            "sans" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            else -> FontFamily.Monospace
        },
        shadow = Shadow(Color(0xCC000000), Offset(1.5f, 1.5f), 2f),
    )
    val timer = "%d:%02d:%02d".format(recordingSeconds / 3600, recordingSeconds / 60 % 60, recordingSeconds % 60)
    val date = SimpleDateFormat("''yy MM dd", Locale.US).format(Date())

    Box(modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        for ((corner, alignment) in CORNERS) {
            val text = spec.osd[corner] ?: continue
            BasicText(
                text.replace("{shots}", shotsLeft.toString()).replace("{timer}", timer).replace("{date}", date),
                style = style,
                modifier = Modifier.align(alignment),
            )
        }
    }
}

/** The zoom scale in the viewfinder: wide at the left, tele at the right. [fraction] is 0..1 along it. */
@Composable
fun ZoomScale(spec: ViewfinderSpec, fraction: Float, modifier: Modifier = Modifier) {
    val color = Color(spec.osdColor)
    Box(modifier.padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.size(120.dp, 12.dp)) {
            val mid = size.height / 2
            drawLine(Color(0xAA000000), Offset(0f, mid + 1.5f), Offset(size.width, mid + 1.5f), 5f)
            drawLine(color, Offset(0f, mid), Offset(size.width, mid), 3f)
            drawRect(
                color,
                Offset(fraction.coerceIn(0f, 1f) * (size.width - 6f), 0f),
                androidx.compose.ui.geometry.Size(6f, size.height),
            )
        }
    }
}

private val CORNERS = listOf(
    "topLeft" to Alignment.TopStart,
    "topRight" to Alignment.TopEnd,
    "bottomLeft" to Alignment.BottomStart,
    "bottomRight" to Alignment.BottomEnd,
)
