package com.novainc.agt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Hand drawn line icons, drawn with Canvas strokes in the AGT style.
 * No emoji, no stock glyph fonts.
 */
enum class IconKind {
    Bolt, Cloud, Database, Shield, Pulse, Battery, Storage, Memory, Clock, Console,
    Bell, Flame, Home, Layers, Squares, Lock, Refresh, Grid, Plus, Download,
    Cpu, Thermometer, Signal
}

@Composable
fun LineIcon(kind: IconKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.07f)
        when (kind) {
            IconKind.Bolt -> {
                val p = Path().apply {
                    moveTo(w * 0.55f, h * 0.05f)
                    lineTo(w * 0.25f, h * 0.55f)
                    lineTo(w * 0.47f, h * 0.55f)
                    lineTo(w * 0.42f, h * 0.95f)
                    lineTo(w * 0.78f, h * 0.42f)
                    lineTo(w * 0.55f, h * 0.42f)
                    close()
                }
                drawPath(p, color, style = stroke)
            }
            IconKind.Cloud -> {
                val p = Path().apply {
                    moveTo(w * 0.22f, h * 0.78f)
                    lineTo(w * 0.78f, h * 0.78f)
                    cubicTo(w * 0.95f, h * 0.78f, w * 0.95f, h * 0.5f, w * 0.78f, h * 0.48f)
                    cubicTo(w * 0.72f, h * 0.28f, w * 0.42f, h * 0.22f, w * 0.34f, h * 0.44f)
                    cubicTo(w * 0.16f, h * 0.46f, w * 0.08f, h * 0.78f, w * 0.22f, h * 0.78f)
                    close()
                }
                drawPath(p, color, style = stroke)
            }
            IconKind.Database -> {
                drawOval(
                    color = color,
                    topLeft = Offset(w * 0.15f, h * 0.1f),
                    size = Size(w * 0.7f, h * 0.22f),
                    style = stroke
                )
                drawLine(color, Offset(w * 0.15f, h * 0.21f), Offset(w * 0.15f, h * 0.79f), strokeWidth = w * 0.07f)
                drawLine(color, Offset(w * 0.85f, h * 0.21f), Offset(w * 0.85f, h * 0.79f), strokeWidth = w * 0.07f)
                val p = Path().apply {
                    moveTo(w * 0.15f, h * 0.79f)
                    cubicTo(w * 0.3f, h * 0.95f, w * 0.7f, h * 0.95f, w * 0.85f, h * 0.79f)
                }
                drawPath(p, color, style = stroke)
                val mid = Path().apply {
                    moveTo(w * 0.15f, h * 0.5f)
                    cubicTo(w * 0.3f, h * 0.66f, w * 0.7f, h * 0.66f, w * 0.85f, h * 0.5f)
                }
                drawPath(mid, color, style = stroke)
            }
            IconKind.Shield -> {
                val p = Path().apply {
                    moveTo(w * 0.5f, h * 0.06f)
                    lineTo(w * 0.85f, h * 0.18f)
                    lineTo(w * 0.85f, h * 0.48f)
                    cubicTo(w * 0.85f, h * 0.75f, w * 0.68f, h * 0.88f, w * 0.5f, h * 0.95f)
                    cubicTo(w * 0.32f, h * 0.88f, w * 0.15f, h * 0.75f, w * 0.15f, h * 0.48f)
                    lineTo(w * 0.15f, h * 0.18f)
                    close()
                }
                drawPath(p, color, style = stroke)
                val check = Path().apply {
                    moveTo(w * 0.34f, h * 0.5f)
                    lineTo(w * 0.46f, h * 0.62f)
                    lineTo(w * 0.67f, h * 0.38f)
                }
                drawPath(check, color, style = stroke)
            }
            IconKind.Pulse -> {
                val p = Path().apply {
                    moveTo(w * 0.05f, h * 0.55f)
                    lineTo(w * 0.32f, h * 0.55f)
                    lineTo(w * 0.44f, h * 0.28f)
                    lineTo(w * 0.58f, h * 0.78f)
                    lineTo(w * 0.68f, h * 0.55f)
                    lineTo(w * 0.95f, h * 0.55f)
                }
                drawPath(p, color, style = stroke)
            }
            IconKind.Battery -> {
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.08f, h * 0.28f),
                    size = Size(w * 0.74f, h * 0.44f),
                    style = stroke
                )
                drawLine(color, Offset(w * 0.88f, h * 0.4f), Offset(w * 0.88f, h * 0.6f), strokeWidth = w * 0.09f)
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.15f, h * 0.35f),
                    size = Size(w * 0.4f, h * 0.3f)
                )
            }
            IconKind.Storage -> {
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.15f),
                    size = Size(w * 0.76f, h * 0.3f),
                    style = stroke
                )
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.55f),
                    size = Size(w * 0.76f, h * 0.3f),
                    style = stroke
                )
                drawCircle(color = color, center = Offset(w * 0.26f, h * 0.3f), radius = w * 0.045f)
                drawCircle(color = color, center = Offset(w * 0.26f, h * 0.7f), radius = w * 0.045f)
            }
            IconKind.Memory -> {
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.18f, h * 0.22f),
                    size = Size(w * 0.64f, h * 0.56f),
                    style = stroke
                )
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.36f, h * 0.4f),
                    size = Size(w * 0.28f, h * 0.2f),
                    style = stroke
                )
                for (i in 0..2) {
                    val x = w * (0.3f + i * 0.2f)
                    drawLine(color, Offset(x, h * 0.05f), Offset(x, h * 0.22f), strokeWidth = w * 0.06f)
                    drawLine(color, Offset(x, h * 0.78f), Offset(x, h * 0.95f), strokeWidth = w * 0.06f)
                }
            }
            IconKind.Clock -> {
                drawCircle(color = color, center = Offset(w / 2, h / 2), radius = w * 0.42f, style = stroke)
                drawLine(color, Offset(w / 2, h / 2), Offset(w / 2, h * 0.24f), strokeWidth = w * 0.07f)
                drawLine(color, Offset(w / 2, h / 2), Offset(w * 0.68f, h * 0.58f), strokeWidth = w * 0.07f)
            }
            IconKind.Console -> {
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.08f, h * 0.14f),
                    size = Size(w * 0.84f, h * 0.72f),
                    style = stroke
                )
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.36f)
                    lineTo(w * 0.42f, h * 0.5f)
                    lineTo(w * 0.24f, h * 0.64f)
                }
                drawPath(p, color, style = stroke)
                drawLine(color, Offset(w * 0.52f, h * 0.66f), Offset(w * 0.76f, h * 0.66f), strokeWidth = w * 0.07f)
            }
            IconKind.Bell -> {
                val p = Path().apply {
                    moveTo(w * 0.5f, h * 0.1f)
                    cubicTo(w * 0.68f, h * 0.1f, w * 0.76f, h * 0.26f, w * 0.76f, h * 0.44f)
                    lineTo(w * 0.76f, h * 0.62f)
                    lineTo(w * 0.84f, h * 0.76f)
                    lineTo(w * 0.16f, h * 0.76f)
                    lineTo(w * 0.24f, h * 0.62f)
                    lineTo(w * 0.24f, h * 0.44f)
                    cubicTo(w * 0.24f, h * 0.26f, w * 0.32f, h * 0.1f, w * 0.5f, h * 0.1f)
                    close()
                }
                drawPath(p, color, style = stroke)
                drawCircle(color = color, center = Offset(w * 0.5f, h * 0.86f), radius = w * 0.05f)
            }
            IconKind.Flame -> {
                val p = Path().apply {
                    moveTo(w * 0.5f, h * 0.05f)
                    cubicTo(w * 0.62f, h * 0.24f, w * 0.8f, h * 0.38f, w * 0.8f, h * 0.6f)
                    cubicTo(w * 0.8f, h * 0.8f, w * 0.66f, h * 0.94f, w * 0.5f, h * 0.94f)
                    cubicTo(w * 0.34f, h * 0.94f, w * 0.2f, h * 0.8f, w * 0.2f, h * 0.6f)
                    cubicTo(w * 0.2f, h * 0.46f, w * 0.3f, h * 0.36f, w * 0.38f, h * 0.28f)
                    cubicTo(w * 0.4f, h * 0.42f, w * 0.44f, h * 0.5f, w * 0.5f, h * 0.54f)
                    cubicTo(w * 0.52f, h * 0.4f, w * 0.5f, h * 0.2f, w * 0.5f, h * 0.05f)
                    close()
                }
                drawPath(p, color, style = stroke)
            }
            IconKind.Home -> {
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.48f)
                    lineTo(w * 0.5f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.48f)
                }
                drawPath(p, color, style = stroke)
                val body = Path().apply {
                    moveTo(w * 0.24f, h * 0.4f)
                    lineTo(w * 0.24f, h * 0.88f)
                    lineTo(w * 0.76f, h * 0.88f)
                    lineTo(w * 0.76f, h * 0.4f)
                }
                drawPath(body, color, style = stroke)
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.42f, h * 0.62f),
                    size = Size(w * 0.16f, h * 0.26f),
                    style = stroke
                )
            }
            IconKind.Layers -> {
                fun diamond(cy: Float) {
                    val p = Path().apply {
                        moveTo(w * 0.5f, cy - h * 0.13f)
                        lineTo(w * 0.88f, cy)
                        lineTo(w * 0.5f, cy + h * 0.13f)
                        lineTo(w * 0.12f, cy)
                        close()
                    }
                    drawPath(p, color, style = stroke)
                }
                diamond(h * 0.28f)
                diamond(h * 0.52f)
                diamond(h * 0.76f)
            }
            IconKind.Squares -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.34f, h * 0.1f),
                    size = Size(w * 0.56f, h * 0.56f),
                    cornerRadius = CornerRadius(w * 0.1f),
                    style = stroke
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.1f, h * 0.34f),
                    size = Size(w * 0.56f, h * 0.56f),
                    cornerRadius = CornerRadius(w * 0.1f),
                    style = stroke
                )
            }
            IconKind.Lock -> {
                val shackle = Path().apply {
                    moveTo(w * 0.3f, h * 0.44f)
                    lineTo(w * 0.3f, h * 0.32f)
                    cubicTo(w * 0.3f, h * 0.14f, w * 0.7f, h * 0.14f, w * 0.7f, h * 0.32f)
                    lineTo(w * 0.7f, h * 0.44f)
                }
                drawPath(shackle, color, style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.18f, h * 0.44f),
                    size = Size(w * 0.64f, h * 0.48f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = stroke
                )
                drawCircle(color = color, center = Offset(w * 0.5f, h * 0.66f), radius = w * 0.05f)
            }
            IconKind.Refresh -> {
                val arc = Path().apply {
                    moveTo(w * 0.82f, h * 0.3f)
                    cubicTo(w * 0.72f, h * 0.14f, w * 0.4f, h * 0.08f, w * 0.24f, h * 0.26f)
                    cubicTo(w * 0.06f, h * 0.46f, w * 0.12f, h * 0.76f, w * 0.34f, h * 0.88f)
                    cubicTo(w * 0.56f, h * 1.0f, w * 0.86f, h * 0.9f, w * 0.93f, h * 0.66f)
                }
                drawPath(arc, color, style = stroke)
                val head = Path().apply {
                    moveTo(w * 0.6f, h * 0.3f)
                    lineTo(w * 0.84f, h * 0.3f)
                    lineTo(w * 0.82f, h * 0.54f)
                }
                drawPath(head, color, style = stroke)
            }
            IconKind.Grid -> {
                for (r in 0..1) {
                    for (c in 0..1) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(w * (0.1f + c * 0.44f), h * (0.1f + r * 0.44f)),
                            size = Size(w * 0.36f, h * 0.36f),
                            cornerRadius = CornerRadius(w * 0.09f),
                            style = stroke
                        )
                    }
                }
            }
            IconKind.Plus -> {
                drawLine(color, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), strokeWidth = w * 0.08f)
                drawLine(color, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.84f, h * 0.5f), strokeWidth = w * 0.08f)
            }
            IconKind.Download -> {
                drawLine(color, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.5f, h * 0.62f), strokeWidth = w * 0.08f)
                val head = Path().apply {
                    moveTo(w * 0.28f, h * 0.42f)
                    lineTo(w * 0.5f, h * 0.66f)
                    lineTo(w * 0.72f, h * 0.42f)
                }
                drawPath(head, color, style = stroke)
                drawLine(color, Offset(w * 0.14f, h * 0.86f), Offset(w * 0.86f, h * 0.86f), strokeWidth = w * 0.08f)
            }
            IconKind.Cpu -> {
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.24f, h * 0.24f),
                    size = Size(w * 0.52f, h * 0.52f),
                    style = stroke
                )
                drawRect(
                    color = color,
                    topLeft = Offset(w * 0.4f, h * 0.4f),
                    size = Size(w * 0.2f, h * 0.2f),
                    style = stroke
                )
                for (i in 0..2) {
                    val t = w * (0.34f + i * 0.16f)
                    drawLine(color, Offset(t, h * 0.06f), Offset(t, h * 0.24f), strokeWidth = w * 0.06f)
                    drawLine(color, Offset(t, h * 0.76f), Offset(t, h * 0.94f), strokeWidth = w * 0.06f)
                    drawLine(color, Offset(w * 0.06f, t), Offset(w * 0.24f, t), strokeWidth = w * 0.06f)
                    drawLine(color, Offset(w * 0.76f, t), Offset(w * 0.94f, t), strokeWidth = w * 0.06f)
                }
            }
            IconKind.Thermometer -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.4f, h * 0.06f),
                    size = Size(w * 0.2f, h * 0.62f),
                    cornerRadius = CornerRadius(w * 0.1f),
                    style = stroke
                )
                drawCircle(color = color, center = Offset(w * 0.5f, h * 0.8f), radius = w * 0.17f, style = stroke)
                drawCircle(color = color, center = Offset(w * 0.5f, h * 0.8f), radius = w * 0.06f)
                drawLine(color, Offset(w * 0.66f, h * 0.2f), Offset(w * 0.78f, h * 0.2f), strokeWidth = w * 0.06f)
                drawLine(color, Offset(w * 0.66f, h * 0.36f), Offset(w * 0.78f, h * 0.36f), strokeWidth = w * 0.06f)
            }
            IconKind.Signal -> {
                val heights = listOf(0.22f, 0.42f, 0.62f, 0.84f)
                for (i in 0..3) {
                    val x = w * (0.1f + i * 0.22f)
                    drawLine(
                        color,
                        Offset(x, h * 0.9f),
                        Offset(x, h * (0.9f - heights[i])),
                        strokeWidth = w * 0.1f
                    )
                }
            }
        }
    }
}
