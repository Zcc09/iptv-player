package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.zcc09.iptvplayer.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logoCache = object : LruCache<String, Bitmap>(120) {}

/** Channel logo with a small in-memory cache (no image library needed). */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    var bitmap by remember(url) { mutableStateOf(logoCache.get(url)) }

    LaunchedEffect(url) {
        if (bitmap == null && url.isNotBlank()) {
            val decoded = withContext(Dispatchers.IO) {
                try {
                    val conn = Http.open(url, timeoutMs = 8000)
                    val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    conn.disconnect()
                    bmp
                } catch (t: Throwable) {
                    null
                }
            }
            if (decoded != null) {
                logoCache.put(url, decoded)
                bitmap = decoded
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )
        } else {
            Text(text = "📺", fontSize = (size.value * 0.5f).sp)
        }
    }
}

/** Cast glyph drawn directly so the app needs no icon dependency. */
@Composable
fun CastGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
        // screen
        drawRect(
            color = color,
            topLeft = Offset(w * 0.42f, h * 0.06f),
            size = Size(w * 0.52f, h * 0.42f),
            style = stroke
        )
        // bottom-left corner device
        drawRect(
            color = color,
            topLeft = Offset(w * 0.04f, h * 0.82f),
            size = Size(w * 0.14f, h * 0.14f)
        )
        // waves
        drawArc(
            color = color,
            startAngle = 270f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.04f, h * 0.56f),
            size = Size(h * 0.4f, h * 0.4f),
            style = stroke
        )
        drawArc(
            color = color,
            startAngle = 270f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.04f, h * 0.30f),
            size = Size(h * 0.66f, h * 0.66f),
            style = stroke
        )
    }
}
