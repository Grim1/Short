package app.shortcam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shortcam.bodies.Body

/** Body selection: the cameras sit on shelves, and tapping one picks it up. */
@Composable
fun Shelf(bodies: List<Body>, settings: Settings, onPick: (Body) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A342E), Color(0xFF1F1B18))))
            .systemBarsPadding()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (body in bodies) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CameraOnShelf(body, Modifier.clickable { onPick(body) })
                // The plank, with a lit top edge and a shadow under it.
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFB98A56)))
                Box(
                    Modifier.fillMaxWidth().height(14.dp)
                        .background(Brush.verticalGradient(listOf(Color(0xFF8B6238), Color(0xFF5E4024))))
                )
                Box(
                    Modifier.fillMaxWidth().height(18.dp)
                        .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                )
                // Price-tag style label.
                BasicText(
                    body.model,
                    style = TextStyle(color = Color(0xFF2A2622), fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier
                        .offset(y = (-26).dp)
                        .background(Color(0xFFE9E2CF), RoundedCornerShape(2.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // The one setting so far, as a note pinned under the shelves.
        BasicText(
            "save a clean copy beside each photo:  " + if (settings.cleanCopy) "[ ON ]" else "[ OFF ]",
            style = TextStyle(color = Color(0xFFCFC6B4), fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { settings.toggleCleanCopy() }
                .padding(12.dp),
        )
    }
}

@Composable
private fun CameraOnShelf(body: Body, modifier: Modifier = Modifier) {
    val shell = Color(body.shellColor)
    val ink = Color(body.inkColor)
    // Proportions follow the sensor, so each body sits differently on the shelf.
    val aspect = body.engine.sensorWidth.toFloat() / body.engine.sensorHeight
    val height = (60 + body.pads.size * 2).coerceAtMost(120).dp
    Box(
        modifier
            .width(height * aspect * 1.15f)
            .height(height)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(Brush.verticalGradient(listOf(lerp(shell, Color.White, 0.25f), shell, lerp(shell, Color.Black, 0.35f))))
            .border(1.dp, Color(0x66000000), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
    ) {
        // Lens.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(height * 0.62f)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFDADCDF), Color(0xFF6C6F74))))
                .padding(height * 0.07f)
                .clip(CircleShape)
                .background(Color(0xFF111214))
                .padding(height * 0.1f)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF3B4A7A), Color(0xFF0B0D14))))
        )
        // Viewfinder window and flash.
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(height * 0.2f, height * 0.13f)
                .background(Color(0xFF15171A), RoundedCornerShape(2.dp))
        )
        Box(
            Modifier.align(Alignment.TopStart).padding(8.dp).size(height * 0.26f, height * 0.11f)
                .background(Color(0xFFF2F0E6), RoundedCornerShape(2.dp))
        )
        BasicText(
            body.maker,
            style = TextStyle(
                color = ink, fontSize = 11.sp, fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic, letterSpacing = 1.sp,
            ),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 5.dp),
        )
    }
}
