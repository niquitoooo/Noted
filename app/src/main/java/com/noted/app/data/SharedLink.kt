package com.noted.app.data

import java.net.URL

/**
 * Lo que llega al compartir texto con Noted (`ACTION_SEND text/plain`): el asunto y el texto.
 * El título de la nota es el asunto y el cuerpo es el texto, tal cual.
 */
data class SharedLink(val subject: String, val text: String) {

    /**
     * El sitio del link si **todo** el texto es una dirección; si no, `null`: "Mira esto https://…" no es un link.
     */
    val host: String? get() = hostOf(text)

    /** La dirección, si el texto entero es un link. */
    val url: String? get() = text.trim().takeIf { host != null }

    companion object {
        fun hostOf(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            return runCatching { URL(trimmed).host }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}
