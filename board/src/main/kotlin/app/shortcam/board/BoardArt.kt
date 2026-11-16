package app.shortcam.board

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.shortcam.bodies.Body
import app.shortcam.bodies.Pad
import app.shortcam.bodies.Part
import kotlin.math.min
import kotlin.random.Random

/** Maps board units to pixels for a board fitted and centred in a given area. */
class BoardGeometry(val body: Body, width: Float, height: Float) {
    val scale = min(width / body.board.width, height / body.board.height)
    val left = (width - body.board.width * scale) / 2
    val top = (height - body.board.height * scale) / 2

    fun px(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
    fun px(pad: Pad) = px(pad.x, pad.y)
    fun boardX(px: Float) = (px - left) / scale
    fun boardY(px: Float) = (px - top) / scale

    /** Where the live preview goes, in pixels. */
    val previewRect: Rect
        get() = body.board.run {
            Rect(px(previewX, previewY), Size(previewWidth * scale, previewHeight * scale))
        }
}

internal const val PAD_RADIUS = 2.6f
internal const val CONTACT_RADIUS = 6f

private val Silk = Color(0xFFF2F2EA)
private val Tin = Color(0xFFC9CCD1)
private val Gold = Color(0xFFD8C27A)

/** Draws everything on the board that never changes: mask, traces, parts, pads, silkscreen. */
internal fun DrawScope.drawBoardArt(geo: BoardGeometry, text: TextMeasurer, art: List<ImageBitmap>) {
    val body = geo.body
    val board = body.board
    val s = geo.scale
    val mask = Color(board.color)
    val origin = geo.px(0f, 0f)
    val boardSize = Size(board.width * s, board.height * s)

    // Camera shell around the board.
    drawRect(Color(0xFF141416))

    // A body that ships drawn art gets exactly that: its layers, bottom first, stretched
    // over the board. Pads, parts and silkscreen are expected to be in the drawing.
    if (art.isNotEmpty()) {
        for (layer in art) {
            drawImage(
                layer,
                dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                dstSize = IntSize(boardSize.width.roundToInt(), boardSize.height.roundToInt()),
            )
        }
        return
    }

    // Solder mask, darker towards the bottom, with a lit edge.
    drawRoundRect(mask, origin, boardSize, CornerRadius(2.5f * s))
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.07f), Color.Transparent, Color.Black.copy(alpha = 0.22f)),
            startY = origin.y, endY = origin.y + boardSize.height,
        ),
        origin, boardSize, CornerRadius(2.5f * s),
    )
    drawRoundRect(
        lerp(mask, Color.White, 0.25f), origin, boardSize, CornerRadius(2.5f * s),
        style = Stroke(0.4f * s),
    )

    // Mounting holes.
    for ((hx, hy) in listOf(3.5f to 3.5f, board.width - 3.5f to 3.5f, 3.5f to board.height - 3.5f)) {
        drawCircle(Gold, 2.4f * s, geo.px(hx, hy))
        drawCircle(Color(0xFF0C0C0C), 1.4f * s, geo.px(hx, hy))
    }

    drawTraces(geo, lerp(mask, Color.White, 0.2f))

    // Flux residue around some joints.
    for (pad in body.pads) {
        val rnd = Random(pad.id.hashCode())
        if (rnd.nextFloat() < 0.45f) {
            val c = geo.px(pad.x + rnd.nextFloat() * 3 - 1.5f, pad.y + rnd.nextFloat() * 3 - 1.5f)
            drawCircle(Color(0xFFC9A04A).copy(alpha = 0.16f), (3.5f + rnd.nextFloat() * 2.5f) * s, c)
            drawCircle(Color(0xFF7A5A1E).copy(alpha = 0.10f), (2f + rnd.nextFloat() * 2f) * s, c + Offset(1.2f * s, 0.6f * s))
        }
    }

    // Back of the LCD module, which the live preview sits in.
    val lcd = geo.previewRect
    drawRoundRect(
        Color(0xFF2B2D31), lcd.topLeft - Offset(2f * s, 2f * s),
        Size(lcd.width + 4f * s, lcd.height + 4f * s), CornerRadius(1f * s),
    )
    drawRect(Color.Black, lcd.topLeft, lcd.size)
    label(text, "LCD1", geo.px(board.previewX + board.previewWidth / 2, board.previewY + board.previewHeight + 4.4f), 2.2f * s, Silk)

    for (part in board.parts) drawPart(geo, text, part)
    for (pad in body.pads) drawPad(geo, text, pad)
}

private fun DrawScope.drawTraces(geo: BoardGeometry, color: Color) {
    val body = geo.body
    val s = geo.scale
    val stroke = Stroke(0.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val chips = body.board.parts.filter { it.type == "ic" && it.stage != null }.sortedBy { it.stage }

    // Buses between consecutive chips in signal order.
    for ((from, to) in chips.zipWithNext()) {
        for (k in -2..2) {
            val o = k * 1.3f
            val ax = from.x + from.w / 2 + o
            val ay = from.y + from.h / 2
            val bx = to.x + to.w / 2 + o
            val by = to.y + to.h / 2
            val midY = (ay + by) / 2 + o
            val path = Path().apply {
                moveTo(geo.px(ax, ay).x, geo.px(ax, ay).y)
                lineTo(geo.px(ax, midY).x, geo.px(ax, midY).y)
                lineTo(geo.px(bx, midY).x, geo.px(bx, midY).y)
                lineTo(geo.px(bx, by).x, geo.px(bx, by).y)
            }
            drawPath(path, color, style = stroke)
        }
    }

    // Each pad runs to a pin on the chip of its stage, with a jog and a via.
    for (pad in body.pads) {
        val chip = chips.firstOrNull { it.stage == pad.stage } ?: continue
        val pinX = pad.x.coerceIn(chip.x + 2f, chip.x + chip.w - 2f)
        val chipY = if (pad.y > chip.y) chip.y + chip.h else chip.y
        val jogY = (pad.y + chipY) / 2
        val path = Path().apply {
            moveTo(geo.px(pad).x, geo.px(pad).y)
            lineTo(geo.px(pad.x, jogY + 1f).x, geo.px(pad.x, jogY + 1f).y)
            lineTo(geo.px(pinX, jogY - 1f).x, geo.px(pinX, jogY - 1f).y)
            lineTo(geo.px(pinX, chipY).x, geo.px(pinX, chipY).y)
        }
        drawPath(path, color, style = stroke)
    }

    // Stray vias.
    val rnd = Random(body.id.hashCode())
    repeat(26) {
        val c = geo.px(4f + rnd.nextFloat() * (body.board.width - 8f), 60f + rnd.nextFloat() * (body.board.height - 80f))
        drawCircle(color, 0.9f * s, c)
        drawCircle(Color(0xFF0E0E0E), 0.4f * s, c)
    }
}

private fun DrawScope.drawPart(geo: BoardGeometry, text: TextMeasurer, part: Part) {
    val s = geo.scale
    val topLeft = geo.px(part.x, part.y)
    val size = Size(part.w * s, part.h * s)
    val centre = topLeft + Offset(size.width / 2, size.height / 2)

    when (part.type) {
        "ic" -> {
            val pin = Size(1f * s, 1.5f * s)
            val quad = part.w >= 20f && part.h >= 20f
            if (part.w >= part.h || quad) {
                var x = part.x + 1.5f
                while (x < part.x + part.w - 1.5f) {
                    drawRect(Tin, geo.px(x, part.y - 1.3f), pin)
                    drawRect(Tin, geo.px(x, part.y + part.h - 0.2f), pin)
                    x += 2f
                }
            }
            if (part.h > part.w || quad) {
                var y = part.y + 1.5f
                while (y < part.y + part.h - 1.5f) {
                    drawRect(Tin, geo.px(part.x - 1.3f, y), Size(pin.height, pin.width))
                    drawRect(Tin, geo.px(part.x + part.w - 0.2f, y), Size(pin.height, pin.width))
                    y += 2f
                }
            }
            drawRoundRect(Color(0xFF161617), topLeft, size, CornerRadius(0.6f * s))
            drawRoundRect(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    start = topLeft, end = topLeft + Offset(size.width, size.height),
                ),
                topLeft, size, CornerRadius(0.6f * s),
            )
            drawCircle(Color(0xFF3A3A3C), 0.9f * s, topLeft + Offset(2.2f * s, size.height - 2.2f * s))
            label(text, part.label, centre, 2.5f * s, Color(0xFFAEB0B3))
        }
        "cap" -> {
            val r = size.width / 2
            drawCircle(Color(0xFF8C9096), r * 1.08f, centre)
            drawCircle(Tin, r, centre)
            drawArc(
                Color(0xFF26272D), 205f, 130f, true,
                centre - Offset(r, r), Size(r * 2, r * 2),
            )
            drawLine(Color(0xFF8C9096), centre - Offset(r * 0.5f, 0f), centre + Offset(r * 0.5f, 0f), 0.25f * s)
            label(text, part.label, centre + Offset(0f, r + 2f * s), 1.9f * s, Silk)
        }
        "xtal" -> {
            drawRoundRect(Color(0xFF8C9096), topLeft - Offset(0.3f * s, 0.3f * s), Size(size.width + 0.6f * s, size.height + 0.6f * s), CornerRadius(size.height / 2))
            drawRoundRect(Color(0xFFD7DADF), topLeft, size, CornerRadius(size.height / 2))
            label(text, part.label, centre, 2f * s, Color(0xFF55585E))
        }
        "r", "c" -> {
            val bodyColor = if (part.type == "r") Color(0xFF1B1B1C) else Color(0xFFB08D57)
            drawRect(Tin, topLeft, size)
            val inset = if (part.w >= part.h) Offset(size.width * 0.22f, 0f) else Offset(0f, size.height * 0.22f)
            drawRect(bodyColor, topLeft + inset, Size(size.width - inset.x * 2, size.height - inset.y * 2))
        }
        "conn" -> {
            drawRect(Color(0xFFE6E0CE), topLeft, size)
            drawRect(Color(0xFF3A342C), topLeft + Offset(1f * s, size.height * 0.35f), Size(size.width - 2f * s, size.height * 0.3f))
            var x = part.x + 1.5f
            while (x < part.x + part.w - 1f) {
                drawRect(Gold, geo.px(x, part.y + part.h), Size(0.8f * s, 1.6f * s))
                x += 1.6f
            }
            label(text, part.label, centre + Offset(0f, size.height / 2 + 3.6f * s), 2f * s, Silk)
        }
        "led" -> {
            drawRect(Tin, topLeft - Offset(0.8f * s, 0f), Size(size.width + 1.6f * s, size.height))
            drawRoundRect(Color(0xFFE9E6DA), topLeft, size, CornerRadius(0.5f * s))
            label(text, part.label, centre + Offset(0f, size.height / 2 + 1.8f * s), 1.8f * s, Silk)
        }
        "sticker" -> {
            drawRoundRect(Color.Black.copy(alpha = 0.3f), topLeft + Offset(0.3f * s, 0.4f * s), size, CornerRadius(0.5f * s))
            drawRoundRect(Color(0xFFE4E2D8), topLeft, size, CornerRadius(0.5f * s))
            drawRoundRect(Color(0xFF8A8778), topLeft + Offset(0.7f * s, 0.7f * s), Size(size.width - 1.4f * s, size.height - 1.4f * s), CornerRadius(0.3f * s), style = Stroke(0.2f * s))
            label(text, part.label, centre, 2.2f * s, Color(0xFF1C1C1C), bold = true)
        }
        "silk" -> label(text, part.label, topLeft, part.h * s, Silk.copy(alpha = 0.9f), centred = false)
        "logo" -> {
            drawRoundRect(Silk, topLeft, size, CornerRadius(size.height / 2), style = Stroke(0.5f * s))
            label(text, part.label, centre, size.height * 0.6f, Silk, bold = true)
        }
    }
}

private fun DrawScope.drawPad(geo: BoardGeometry, text: TextMeasurer, pad: Pad) {
    val s = geo.scale
    val c = geo.px(pad)
    if (pad.contact) {
        val r = CONTACT_RADIUS * s
        val topLeft = c - Offset(r, r)
        drawRoundRect(Color(0xFF8A5A2B), topLeft - Offset(0.3f * s, 0.3f * s), Size(r * 2 + 0.6f * s, r * 2 + 0.6f * s), CornerRadius(1.6f * s))
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFFE0A566), Color(0xFFB9783C)), topLeft, topLeft + Offset(r * 2, r * 2)),
            topLeft, Size(r * 2, r * 2), CornerRadius(1.4f * s),
        )
        // Interleaved fingers, like a bare touch contact.
        val gap = Color(geo.body.board.color)
        for (i in 0 until 5) {
            val y = c.y - r + (i + 1) * (r * 2 / 6)
            val fromLeft = i % 2 == 0
            drawLine(
                gap,
                Offset(if (fromLeft) c.x - r else c.x - r * 0.55f, y),
                Offset(if (fromLeft) c.x + r * 0.55f else c.x + r, y),
                0.6f * s,
            )
        }
        label(text, pad.label, c + Offset(0f, r + 2.4f * s), 2.3f * s, Silk)
    } else {
        drawCircle(Gold, PAD_RADIUS * s, c)
        drawCircle(Color(0xFFEDE8D6), PAD_RADIUS * 0.72f * s, c)
        drawCircle(Color(0xFF0E0E0E), PAD_RADIUS * 0.34f * s, c)
        label(text, pad.label, c + Offset(0f, 5f * s), 2.3f * s, Silk)
    }
}

/** Draws monospace text of a given pixel height, centred on [at] or with [at] as its top-left. */
internal fun DrawScope.label(
    text: TextMeasurer,
    string: String,
    at: Offset,
    sizePx: Float,
    color: Color,
    bold: Boolean = false,
    centred: Boolean = true,
) {
    if (string.isEmpty()) return
    val layout = text.measure(
        AnnotatedString(string),
        TextStyle(
            color = color,
            fontSize = sizePx.toSp(),
            lineHeight = (sizePx * 1.25f).toSp(),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    val topLeft = if (centred) at - Offset(layout.size.width / 2f, layout.size.height / 2f) else at
    drawText(layout, topLeft = topLeft)
}
