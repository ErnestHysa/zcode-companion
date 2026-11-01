package com.zcode.android.features.conversation.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zcode.android.features.conversation.R
import java.io.ByteArrayOutputStream

/**
 * G31 (vc51 Phase 4C): the mobile-shaped minimum of the desktop's WhiteboardPane — a
 * sketch → send entry in the composer's add-menu. Draw → Done renders the strokes to a
 * PNG (white background, brand-primary ink) and hands the BYTES to the composer's
 * existing attachment-upload transaction, so the sketch lands in the conversation as an
 * image attachment (inbound sketch images already render).
 */
data class SketchStroke(val points: MutableList<androidx.compose.ui.geometry.Offset> = mutableListOf())

@Composable
fun SketchSheet(
    onDismiss: () -> Unit,
    onDone: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strokes = remember { mutableStateListOf<SketchStroke>() }
    val current = remember { mutableStateOf<SketchStroke?>(null) }
    val ink = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier
                .fillMaxWidth()
                .testTag("sketch_sheet"),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.sketch_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("sketch_title"),
                )
                Spacer(Modifier.height(8.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.45f)
                        .background(Color.White, MaterialTheme.shapes.medium)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start -> current.value = SketchStroke(mutableStateListOf(start)) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current.value?.points?.add(change.position)
                                },
                                onDragEnd = {
                                    current.value?.let { if (it.points.size > 1) strokes.add(it) }
                                    current.value = null
                                },
                            )
                        }
                        .testTag("sketch_canvas"),
                ) {
                    (strokes.toList() + listOfNotNull(current.value)).forEach { stroke ->
                        if (stroke.points.size > 1) {
                            val path = ComposePath().apply {
                                moveTo(stroke.points.first().x, stroke.points.first().y)
                                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, color = ink, style = Stroke(width = 5f))
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { strokes.clear(); current.value = null },
                        enabled = strokes.isNotEmpty() || current.value != null,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("sketch_clear"),
                    ) { Text(stringResource(R.string.sketch_clear)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onDone(renderSketchPng(strokes.toList(), ink)) },
                        enabled = strokes.isNotEmpty() || current.value != null,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("sketch_done"),
                    ) { Text(stringResource(R.string.sketch_done)) }
                }
            }
        }
    }
}

/** 1024-wide white-canvas PNG of the strokes (ink = the brand primary). */
internal fun renderSketchPng(strokes: List<SketchStroke>, ink: Color): ByteArray {
    val width = 1024
    val height = 706 // the canvas's aspect ratio (1.45f) in pixels
    val scale = width.toFloat() / 1000f // pointer coords are canvas-px; render at a fixed 1000-wide design space
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(
            (ink.alpha * 255).toInt(),
            (ink.red * 255).toInt(),
            (ink.green * 255).toInt(),
            (ink.blue * 255).toInt(),
        )
        style = Paint.Style.STROKE
        strokeWidth = 6f * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.points.size > 1) {
            val path = Path()
            path.moveTo(stroke.points.first().x * scale, stroke.points.first().y * scale)
            stroke.points.drop(1).forEach { path.lineTo(it.x * scale, it.y * scale) }
            canvas.drawPath(path, paint)
        }
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}
