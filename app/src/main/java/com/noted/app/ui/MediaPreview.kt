package com.noted.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class VideoPreview(
    val frame: Bitmap?,
    val width: Int,
    val height: Int
)

/**
 * Extrae un frame representativo del video para usarlo como miniatura.
 * Se toma cerca del primer segundo (o de la primera cuarta parte si es corto)
 * y se limita el ancho para no guardar un bitmap gigante en memoria.
 */
@Composable
fun rememberVideoPreview(file: File): VideoPreview? {
    val key = PreviewCache.key(file, "video")
    // Si ya está en memoria se usa en el primer cuadro: sin espera ni salto de alto.
    val preview by produceState<VideoPreview?>(initialValue = PreviewCache.videos.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            loadVideoPreview(file)?.also { PreviewCache.videos.put(key, it) }
        }
    }
    return preview
}

/**
 * Renderiza la primera página del PDF como bitmap para la miniatura.
 */
@Composable
fun rememberPdfPreview(file: File, targetWidth: Int = 360): Bitmap? {
    val key = PreviewCache.key(file, "pdf$targetWidth")
    val preview by produceState<Bitmap?>(initialValue = PreviewCache.pdfs.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            loadPdfFirstPage(file, targetWidth)?.also { PreviewCache.pdfs.put(key, it) }
        }
    }
    return preview
}

/**
 * Miniaturas ya calculadas, para no volver a decodificar el video ni dibujar el
 * PDF cada vez que el bloque aparece (abrir la nota, arrastrar, volver atrás).
 * La clave incluye la fecha de modificación por si el archivo cambia.
 */
private object PreviewCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8).toInt()

    val videos = object : LruCache<String, VideoPreview>(maxBytes / 2) {
        override fun sizeOf(key: String, value: VideoPreview) =
            value.frame?.allocationByteCount ?: 1
    }

    val pdfs = object : LruCache<String, Bitmap>(maxBytes / 2) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun key(file: File, kind: String) = "$kind|${file.absolutePath}|${file.lastModified()}"
}

fun loadVideoPreview(file: File): VideoPreview? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
        val timeUs = if (durationMs > 0) {
            minOf(1_000_000L, durationMs * 1000L / 4)
        } else {
            0L
        }
        val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        val width = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?: raw?.width
            ?: 0
        val height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?: raw?.height
            ?: 0
        VideoPreview(raw?.let { scaleDown(it, 1280) }, width, height)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

fun loadPdfFirstPage(file: File, targetWidth: Int): Bitmap? {
    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    return try {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)
        if (renderer.pageCount == 0) {
            null
        } else {
            renderer.openPage(0).use { page ->
                val scale = pdfRenderScale(page.width, page.height, targetWidth)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
    }
}

// Tope de una página de PDF dibujada: unos 12 millones de píxeles (48 MB) y 8192 de
// lado. Un plano enorme o "una página web en una hoja" se achica en lugar de cerrar
// la app por falta de memoria.
private const val PDF_MAX_PIXELS = 12_000_000f
private const val PDF_MAX_SIDE = 8192f

internal fun pdfRenderScale(pageWidth: Int, pageHeight: Int, targetWidth: Int): Float {
    val w = pageWidth.coerceAtLeast(1).toFloat()
    val h = pageHeight.coerceAtLeast(1).toFloat()
    val wanted = (targetWidth / w).coerceAtMost(2.5f)
    val byPixels = kotlin.math.sqrt(PDF_MAX_PIXELS / (w * h))
    return minOf(wanted, byPixels, PDF_MAX_SIDE / w, PDF_MAX_SIDE / h)
}

private fun scaleDown(bitmap: Bitmap, maxWidth: Int): Bitmap {
    if (bitmap.width <= maxWidth) return bitmap
    val height = (bitmap.height * (maxWidth.toFloat() / bitmap.width))
        .toInt()
        .coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
    if (scaled != bitmap) bitmap.recycle()
    return scaled
}
