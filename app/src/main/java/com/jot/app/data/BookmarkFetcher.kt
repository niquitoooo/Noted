package com.jot.app.data

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class BookmarkMetadata(
    val title: String,
    val description: String,
    val imageUrl: String?,
    val siteName: String?
)

/**
 * Trae título, descripción e imagen de un link usando Open Graph y, para
 * YouTube, la miniatura derivada del ID del video.
 */
object BookmarkFetcher {

    suspend fun fetch(rawUrl: String): BookmarkMetadata = withContext(Dispatchers.IO) {
        val requested = normalize(rawUrl)
        val download = runCatching { downloadText(requested) }.getOrNull()
        val html = download?.first
        // Después de las redirecciones: las imágenes relativas se resuelven desde ahí.
        val pageUrl = download?.second ?: requested

        val ogTitle = html?.let { metaContent(it, "og:title") }
        val ogDescription = html?.let { metaContent(it, "og:description") }
        val ogImage = html?.let { metaContent(it, "og:image") }
        val ogSite = html?.let { metaContent(it, "og:site_name") }
        val pageTitle = html?.let { titleTag(it) }
        val pageDescription = html?.let { metaContent(it, "description") }

        val youtubeId = youtubeId(pageUrl)
        val title = ogTitle?.takeIf { it.isNotBlank() }
            ?: pageTitle?.takeIf { it.isNotBlank() }
            ?: pageUrl
        val description = ogDescription?.takeIf { it.isNotBlank() }
            ?: pageDescription.orEmpty()
        val image = ogImage?.takeIf { it.isNotBlank() }?.let { absoluteUrl(pageUrl, it) }
            ?: youtubeId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }
        val siteName = ogSite?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(pageUrl).host }.getOrNull()

        BookmarkMetadata(
            title = title,
            description = description,
            imageUrl = image,
            siteName = siteName
        )
    }

    suspend fun downloadImage(url: String, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = connect(url)
                try {
                    if (connection.responseCode !in 200..299) return@withContext false
                    // Tope de 8 MB: se corta al pasarlo, sin bajar la imagen entera.
                    if (connection.contentLengthLong > MAX_IMAGE_BYTES) return@withContext false
                    val bytes = connection.inputStream.use { readAtMost(it, MAX_IMAGE_BYTES) }
                        ?: return@withContext false
                    if (bytes.isEmpty()) return@withContext false
                    destination.parentFile?.mkdirs()
                    destination.writeBytes(bytes)
                    true
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }

    /** Con "https://" adelante si el link no dice el protocolo ("www.google.com"). */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    private const val MAX_HTML_BYTES = 1_000_000L

    /**
     * Abre la conexión siguiendo hasta 5 redirecciones, también de http a https (la
     * redirección automática de Android no cambia de protocolo).
     */
    private fun connect(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val connection = openConnection(current)
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (code in 300..399 && location != null) {
                connection.disconnect()
                current = URL(URL(current), location).toString()
            } else {
                return connection
            }
        }
        return openConnection(current)
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        }

    private fun downloadText(url: String): Pair<String, String>? {
        val connection = connect(url)
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { readAtMost(it, MAX_HTML_BYTES, truncate = true) }
                ?: return null
            // La página dice su codificación en la cabecera o en <meta charset>; si no,
            // UTF-8. Así una página en ISO-8859-1 no muestra los acentos rotos.
            val charset = charsetOf(connection.contentType)
                ?: charsetOf(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))
                ?: Charsets.UTF_8
            String(bytes, charset) to connection.url.toString()
        } finally {
            connection.disconnect()
        }
    }

    private fun charsetOf(text: String?): java.nio.charset.Charset? {
        text ?: return null
        val name = Regex("charset\\s*=\\s*[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: return null
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
    }

    /** Lee hasta [limit] bytes; si hay más, null (o lo leído, con [truncate]). */
    private fun readAtMost(input: InputStream, limit: Long, truncate: Boolean = false): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (total + read > limit) {
                if (!truncate) return null
                out.write(buffer, 0, (limit - total).toInt())
                break
            }
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun metaContent(html: String, key: String): String? {
        val tagRegex = Regex("<meta\\b(?:\"[^\"]*\"|'[^']*'|[^>\"'])*>", RegexOption.IGNORE_CASE)
        val contentRegex = Regex(
            "content\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
            RegexOption.IGNORE_CASE
        )
        val keyRegex = Regex(
            "(?:property|name)\\s*=\\s*[\"']" + Regex.escape(key) + "[\"']",
            RegexOption.IGNORE_CASE
        )
        tagRegex.findAll(html).forEach { match ->
            val tag = match.value
            if (keyRegex.containsMatchIn(tag)) {
                val found = contentRegex.find(tag)
                if (found != null) {
                    return decode(found.groupValues[1].ifEmpty { found.groupValues[2] })
                }
            }
        }
        return null
    }

    private fun titleTag(html: String): String? {
        val match = Regex(
            "<title\\b[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: return null
        return decode(match.groupValues[1])
    }

    private fun decode(value: String): String =
        runCatching {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        }.getOrDefault(value).trim()

    private fun absoluteUrl(base: String, value: String): String {
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value
        }
        return runCatching { URL(URL(base), value).toString() }.getOrDefault(value)
    }

    private fun youtubeId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            return uri.path?.trim('/')?.takeIf { it.isNotBlank() }
        }
        if (host.contains("youtube.com")) {
            val query = uri.rawQuery ?: return null
            return query.split('&')
                .firstOrNull { it.startsWith("v=") }
                ?.substringAfter("v=")
                ?.takeIf { it.isNotBlank() }
        }
        return null
    }
}
