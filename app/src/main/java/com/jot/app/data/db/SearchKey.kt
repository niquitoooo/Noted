package com.jot.app.data.db

/**
 * La columna `searchKey` de `note`: título, texto y enlaces llevados a una forma sin mayúsculas,
 * separados por [SEPARATOR]. La búsqueda es `instr(searchKey, fold(consulta))` y da lo mismo que
 * `Note.matchesSearch` (`contains(…, ignoreCase = true)` en cada campo).
 */
object SearchKey {
    /** Separa los campos: así el final del título y el principio del texto no forman una palabra. */
    const val SEPARATOR = '\u001F'

    /**
     * Letra por letra, como compara `Char.equals(…, ignoreCase = true)`: dos letras son iguales sin
     * importar mayúsculas si y solo si quedan iguales acá. No cambia el largo del texto.
     */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(c.uppercaseChar().lowercaseChar())
        return out.toString()
    }

    fun of(title: String, text: String, links: String): String =
        fold(title) + SEPARATOR + fold(text) + SEPARATOR + fold(links)

    /** Una consulta vacía o con el separador no se puede buscar en la columna. */
    fun canSearch(query: String): Boolean = query.isNotEmpty() && SEPARATOR !in query
}
