package com.noted.app.ui.emoji

import android.content.Context
import android.graphics.Paint
import java.text.Normalizer
import kotlin.random.Random

data class EmojiEntry(val emoji: String, val category: Int, val nameEn: String, val keywordsEs: String)

val EMOJI_CATEGORIES: List<String> = listOf(
    "Caritas y personas", "Animales y naturaleza", "Comida y bebida", "Actividades y deportes",
    "Viajes y lugares", "Objetos", "Símbolos", "Banderas",
)

private val MARCAS = Regex("\\p{Mn}+")

private fun normaliza(s: String): String =
    MARCAS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

class EmojiCatalog(val entries: List<EmojiEntry>) {
    private val normalizados: List<String> =
        entries.map { normaliza(it.nameEn + "\u0000" + it.keywordsEs) }

    fun byCategory(): List<Pair<Int, List<EmojiEntry>>> =
        entries.groupBy { it.category }.toSortedMap().map { it.key to it.value }

    fun search(query: String): List<EmojiEntry> {
        val q = normaliza(query.trim())
        if (q.isEmpty()) return entries
        return entries.filterIndexed { i, _ -> normalizados[i].contains(q) }
    }

    fun random(rnd: Random = Random.Default): EmojiEntry? =
        if (entries.isEmpty()) null else entries[rnd.nextInt(entries.size)]

    companion object {
        @Volatile private var cache: EmojiCatalog? = null

        fun parse(tsv: String, canDraw: (String) -> Boolean = { true }): EmojiCatalog {
            val lista = tsv.lineSequence().filter { it.isNotBlank() }.mapNotNull { linea ->
                val p = linea.split('\t')
                val cat = p.getOrNull(0)?.toIntOrNull()
                if (p.size < 3 || cat == null) null
                else EmojiEntry(p[1], cat, p[2], p.getOrElse(3) { "" })
            }.filter { canDraw(it.emoji) }.toList()
            return EmojiCatalog(lista)
        }

        fun load(context: Context): EmojiCatalog = cache ?: synchronized(this) {
            cache ?: run {
                val texto = context.applicationContext.assets.open("emoji/emoji.tsv")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val paint = Paint()
                parse(texto) { paint.hasGlyph(it) }.also { cache = it }
            }
        }
    }
}
