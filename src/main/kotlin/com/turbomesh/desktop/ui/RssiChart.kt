package com.turbomesh.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun RssiChart(
    readings: List<Int>,  // RSSI values, e.g. [-70, -65, -80, ...]
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF4CAF50),
) {
    if (readings.isEmpty()) {
        Box(modifier.height(80.dp), contentAlignment = Alignment.Center) {
            Text("No RSSI data yet", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val minRssi = -100
    val maxRssi = -30

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${maxRssi} dBm", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("RSSI (last ${readings.size})", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${minRssi} dBm", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
            val w = size.width
            val h = size.height
            val range = (maxRssi - minRssi).toFloat()

            fun rssiToY(rssi: Int): Float =
                h - ((rssi.coerceIn(minRssi, maxRssi) - minRssi) / range * h)

            // Grid lines at -50, -70, -90
            listOf(-50, -70, -90).forEach { level ->
                val y = rssiToY(level)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.2f),
                    start = Offset(0f, y), end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            // Fill path
            if (readings.size >= 2) {
                val step = w / (readings.size - 1).coerceAtLeast(1)
                val fillPath = Path().apply {
                    moveTo(0f, h)
                    readings.forEachIndexed { i, rssi ->
                        lineTo(i * step, rssiToY(rssi))
                    }
                    lineTo((readings.size - 1) * step, h)
                    close()
                }
                drawPath(fillPath, color = lineColor.copy(alpha = 0.15f))

                // Line
                val linePath = Path().apply {
                    readings.forEachIndexed { i, rssi ->
                        val x = i * step
                        val y = rssiToY(rssi)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(linePath, color = lineColor, style = Stroke(width = 2f, cap = StrokeCap.Round))

                // Last point dot
                val lastX = (readings.size - 1) * step
                val lastY = rssiToY(readings.last())
                drawCircle(color = lineColor, radius = 4f, center = Offset(lastX, lastY))
            }
        }
        Text(
            "Current: ${readings.lastOrNull() ?: "—"} dBm",
            style = MaterialTheme.typography.labelSmall,
            color = lineColor,
            modifier = Modifier.align(Alignment.End)
        )
    }
}
