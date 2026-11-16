package app.shortcam

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The shutter release: a metal button. Used on both faces of the camera. */
@Composable
fun ShutterButton(size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFF4F5F6), Color(0xFF8E9197))))
            .padding(size * 0.09f)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF9B9EA3), Color(0xFFE3E4E6))))
            .border(1.dp, Color(0x55000000), CircleShape)
            .clickable(onClick = onClick)
    )
}

/** The record button: a red dot that becomes a square while recording. */
@Composable
fun RecordButton(size: Dp, recording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1E1F22))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (recording) size * 0.35f else size * 0.48f)
                .clip(if (recording) RoundedCornerShape(3.dp) else CircleShape)
                .background(Color(0xFFE0362B))
        )
    }
}

/**
 * The zoom rocker: hold T to zoom in, W to zoom out. The lens moves at motor speed for
 * as long as a side is held, taking [SWEEP_SECONDS] to cross its whole range.
 */
@Composable
fun ZoomRocker(zoom: Float, max: Float, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    var direction by remember { mutableStateOf(0) }
    val current by rememberUpdatedState(zoom)
    val report by rememberUpdatedState(onZoom)
    LaunchedEffect(direction) {
        if (direction == 0) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val step = direction * (now - last) / 1e9f * ln(max) / SWEEP_SECONDS
            last = now
            report((current * exp(step)).coerceIn(1f, max))
        }
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3C40), Color(0xFF1E1F22))))
    ) {
        for ((label, side) in listOf("W" to -1, "T" to 1)) {
            Box(
                Modifier
                    .size(46.dp, 30.dp)
                    .background(if (direction == side) Color(0x33FFFFFF) else Color.Transparent)
                    .pointerInput(side) {
                        awaitEachGesture {
                            awaitFirstDown()
                            direction = side
                            waitForUpOrCancellation()
                            direction = 0
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label, style = TextStyle(color = Color(0xFFE8E8E0), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

private const val SWEEP_SECONDS = 1.8f

/** The last photo or clip, as a small print. Tapping it opens the phone's gallery on that item. */
@Composable
fun GalleryThumb(size: Dp, last: LastCapture, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF1E1F22))
            .border(2.dp, Color(0xFFE9E6DA), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = last.thumbnail
        if (thumb != null) {
            Image(thumb, contentDescription = "Open the last capture in the gallery", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (last.isVideo) BasicText("▶", style = TextStyle(color = Color.White, fontSize = 16.sp))
        } else {
            BasicText("▦", style = TextStyle(color = Color(0xFF8E8E86), fontSize = 18.sp))
        }
    }
}

/**
 * The most recent thing the app saved to the Short album, for the gallery shortcut.
 * There is no gallery in the app; this only points at the phone's own.
 */
class LastCapture(private val context: Context) {
    var uri by mutableStateOf<Uri?>(null)
        private set
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
        private set
    var isVideo by mutableStateOf(false)
        private set

    /** Records a capture that was just saved. Safe to call from any thread. */
    fun set(saved: Uri) {
        val type = context.contentResolver.getType(saved).orEmpty()
        val thumb = runCatching {
            context.contentResolver.loadThumbnail(saved, Size(256, 256), null).asImageBitmap()
        }.getOrNull()
        uri = saved
        isVideo = type.startsWith("video")
        thumbnail = thumb
    }

    /** Finds the newest item this app has in the Short album, for when the app has just started. */
    fun findLatest() {
        val newest = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/Short/%",
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/Short/%",
        ).mapNotNull { (collection, path) ->
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(path),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) to c.getLong(1) else null
                }
            }.getOrNull()
        }.maxByOrNull { it.second }
        if (newest != null) set(newest.first)
    }

    /** Opens the last capture in the phone's gallery. Returns false if there is nothing to open or no app to open it. */
    fun open(): Boolean {
        val target = uri ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(target, context.contentResolver.getType(target) ?: "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
