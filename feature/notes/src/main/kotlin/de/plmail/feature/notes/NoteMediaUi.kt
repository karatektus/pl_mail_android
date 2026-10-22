package de.plmail.feature.notes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import de.plmail.jmap.methods.NoteMedia
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A note's pictures (and drawings) as a row of thumbnails, and its voice notes as play buttons.
 * Each file is fetched once into the cache by [load]; a picture opens full size in the phone's
 * viewer, through the FileProvider.
 */
@Composable
internal fun NoteMediaStrip(
    media: List<NoteMedia>,
    editable: Boolean,
    load: suspend (NoteMedia) -> File?,
    onRemove: (NoteMedia) -> Unit,
) {
    if (media.isEmpty()) return
    val context = LocalContext.current
    val pictures = media.filterNot { it.isAudio }
    val voices = media.filter { it.isAudio }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (pictures.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pictures.forEach { item ->
                    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
                    var file by remember(item.id) { mutableStateOf<File?>(null) }
                    LaunchedEffect(item.id) {
                        file = load(item)
                        bitmap = file?.let { f ->
                            withContext(Dispatchers.IO) { decodeSampled(f, 480) }
                        }
                    }
                    Box(
                        Modifier.height(140.dp)
                            .aspectRatio(
                                bitmap
                                    ?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
                                    ?.coerceIn(0.5f, 2f) ?: 1f
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .clickable(enabled = file != null) {
                                file?.let { context.openMedia(it, item.type) }
                            }
                    ) {
                        val b = bitmap
                        if (b != null) {
                            Image(
                                b.asImageBitmap(),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
                        }
                        if (editable) {
                            RemoveBadge(Modifier.align(Alignment.TopEnd)) { onRemove(item) }
                        }
                    }
                }
            }
        }
        voices.forEach { item -> VoiceRow(item, editable, load, onRemove) }
    }
}

@Composable
private fun RemoveBadge(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(4.dp)
            .size(28.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Close,
            stringResource(R.string.notes_media_remove),
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun VoiceRow(
    item: NoteMedia,
    editable: Boolean,
    load: suspend (NoteMedia) -> File?,
    onRemove: (NoteMedia) -> Unit,
) {
    var file by remember(item.id) { mutableStateOf<File?>(null) }
    var playing by remember(item.id) { mutableStateOf(false) }
    var player by remember(item.id) { mutableStateOf<MediaPlayer?>(null) }
    LaunchedEffect(item.id) { file = load(item) }
    DisposableEffect(item.id) { onDispose { player?.release() } }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
        IconButton(
            enabled = file != null,
            onClick = {
                val p = player
                if (playing && p != null) {
                    p.pause()
                    playing = false
                } else {
                    val f = file ?: return@IconButton
                    val next =
                        p
                            ?: MediaPlayer().apply {
                                setDataSource(f.absolutePath)
                                setOnCompletionListener { playing = false }
                                prepare()
                            }
                    player = next
                    next.start()
                    playing = true
                }
            },
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(
                    if (playing) R.string.notes_media_pause else R.string.notes_media_play
                ),
            )
        }
        Text(
            stringResource(R.string.notes_media_voice_note),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (editable) {
            IconButton(onClick = { onRemove(item) }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.notes_media_remove))
            }
        }
    }
}

/** A drawing pad: pen colours (white rubs out), width, undo, clear. Saves a PNG. */
@Composable
internal fun DrawingDialog(onSave: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    class Line(val color: Color, val width: Float, val points: MutableList<Offset>)

    val strokes = remember { mutableStateListOf<Line>() }
    var color by remember { mutableStateOf(Color(0xFF202124)) }
    var width by remember { mutableFloatStateOf(8f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                            0xFF202124,
                            0xFFD93025,
                            0xFF1A73E8,
                            0xFF188038,
                            0xFFF9AB00,
                            0xFF9334E6,
                            0xFFFFFFFF,
                        )
                        .forEach { argb ->
                            val c = Color(argb)
                            Box(
                                Modifier.size(28.dp)
                                    .background(c, CircleShape)
                                    .border(
                                        if (c == color) 3.dp else 1.dp,
                                        if (c == color) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.2f),
                                        CircleShape,
                                    )
                                    .clickable { color = c }
                            )
                        }
                }
                Slider(
                    value = width,
                    onValueChange = { width = it },
                    valueRange = 2f..48f,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Canvas(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .background(Color.White)
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    strokes.add(Line(color, width, mutableListOf(start)))
                                    tick++
                                },
                                onDrag = { change, _ ->
                                    strokes.lastOrNull()?.points?.add(change.position)
                                    tick++
                                },
                            )
                        }
                ) {
                    // Read so the pad redraws as a line grows (its points are a plain list).
                    if (tick < 0) return@Canvas
                    strokes.forEach { s ->
                        val path = Path()
                        s.points.forEachIndexed { i, p ->
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        drawPath(
                            path,
                            s.color,
                            style = Stroke(s.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            strokes.removeLastOrNull()
                            tick++
                        }
                    ) {
                        Text(stringResource(R.string.notes_undo))
                    }
                    TextButton(
                        onClick = {
                            strokes.clear()
                            tick++
                        }
                    ) {
                        Text(stringResource(R.string.notes_media_clear))
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.notes_reminder_cancel))
                    }
                    TextButton(
                        enabled = strokes.isNotEmpty() && size.width > 0,
                        onClick = {
                            val bitmap = createBitmap(size.width, size.height)
                            bitmap.applyCanvas {
                                drawColor(android.graphics.Color.WHITE)
                                strokes.forEach { s ->
                                    val paint =
                                        android.graphics
                                            .Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                            .apply {
                                                this.color = s.color.toArgb()
                                                strokeWidth = s.width
                                                style = android.graphics.Paint.Style.STROKE
                                                strokeCap = android.graphics.Paint.Cap.ROUND
                                                strokeJoin = android.graphics.Paint.Join.ROUND
                                            }
                                    val path = android.graphics.Path()
                                    s.points.forEachIndexed { i, p ->
                                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                                    }
                                    if (s.points.size == 1)
                                        path.lineTo(s.points[0].x + 0.1f, s.points[0].y)
                                    drawPath(path, paint)
                                }
                            }
                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            onSave(out.toByteArray())
                        },
                    ) {
                        Text(stringResource(R.string.notes_media_save_drawing))
                    }
                }
            }
        }
    }
}

/** Records a voice note into the cache, as AAC in MPEG-4 (.m4a). */
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start() {
        val target =
            File(
                File(context.cacheDir, "notes").apply { mkdirs() },
                "voice-${System.currentTimeMillis()}.m4a",
            )
        val r = MediaRecorder(context)
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setMaxDuration(5 * 60 * 1000)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = target
    }

    /** Stops and answers the recording, or null when nothing was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return file?.takeIf { ok && it.length() > 0 }
    }

    fun cancel() {
        stop()?.delete()
    }
}

private fun decodeSampled(file: File, max: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (
        bounds.outWidth / (sample * 2) >= max && bounds.outHeight / (sample * 2) >= max
    ) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun Context.openMedia(file: File, type: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.blobs", file)
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
