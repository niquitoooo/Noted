package com.noted.app.util

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

fun formatDate(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(then.time)
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    if (sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Hoy $time"
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameYear && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Ayer $time"
    val pattern = if (sameYear) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(then.time)
}

fun sanitizeFileName(name: String): String {
    val clean = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
    return clean.take(90).ifBlank { "archivo" }
}

fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

fun mimeIcon(mime: String): ImageVector = when {
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.Audiotrack
    mime.startsWith("image/") -> Icons.Filled.Image
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    mime.startsWith("text/") || mime.contains("word") || mime.contains("document") -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

fun typeLabel(mime: String): String = when {
    mime.startsWith("video/") -> "Video"
    mime.startsWith("audio/") -> "Audio"
    mime.startsWith("image/") -> "Imagen"
    mime == "application/pdf" -> "PDF"
    mime.startsWith("text/") -> "Texto"
    mime.contains("word") -> "Documento"
    mime.contains("sheet") -> "Planilla"
    mime.contains("zip") || mime.contains("compressed") -> "Comprimido"
    else -> mime.substringAfterLast('/').uppercase(Locale.getDefault()).ifBlank { "Archivo" }
}

fun stripMarkdown(markdown: String): String = markdown
    .replace(Regex("<!--\\s*noted:bookmark\\s+\\S+\\s*-->"), " ")
    .replace(Regex("</?(?:details|summary)[^>]*>"), " ")
    .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
    .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("[#>*_~`]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

fun excerpt(markdown: String, max: Int = 180): String {
    val text = stripMarkdown(markdown)
    return if (text.length <= max) text else text.take(max).trimEnd() + "…"
}

fun attachmentUri(context: Context, file: File): android.net.Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

fun openExternally(context: Context, file: File, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(attachmentUri(context, file), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Abrir con"))
    } catch (e: Exception) {
        Toast.makeText(context, "No hay ninguna app para abrir este archivo", Toast.LENGTH_SHORT).show()
    }
}

fun shareFile(context: Context, file: File, mime: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, attachmentUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Compartir archivo"))
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir el archivo", Toast.LENGTH_SHORT).show()
    }
}

/** Copia el adjunto al documento que el usuario eligió en «Guardar como». */
fun copyFileTo(context: Context, file: File, target: android.net.Uri): Boolean = runCatching {
    context.contentResolver.openOutputStream(target, "w")!!.use { out ->
        file.inputStream().use { it.copyTo(out) }
    }
}.isSuccess

fun shareText(context: Context, text: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, "Compartir nota"))
}
