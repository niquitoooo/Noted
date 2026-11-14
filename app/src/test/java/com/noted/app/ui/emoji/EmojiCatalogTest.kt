package com.noted.app.ui.emoji

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    private val tsv = "0\t😀\tgrinning face\tcara sonriendo | sonrisa\n" +
        "0\t❤️\tred heart\tcorazón rojo | amor\n" +
        "5\t💡\tlight bulb\tbombilla | idea\n" +
        "7\t🇦🇷\tflag: Argentina\tbandera: Argentina\n"

    @Test fun `las categorías salen en orden y sin las vacías`() {
        val c = EmojiCatalog.parse(tsv)
        assertEquals(listOf(0, 5, 7), c.byCategory().map { it.first })
        assertEquals(listOf("😀", "❤️"), c.byCategory()[0].second.map { it.emoji })
    }

    @Test fun `busca en castellano sin tildes ni mayúsculas y en inglés`() {
        val c = EmojiCatalog.parse(tsv)
        assertEquals(listOf("❤️"), c.search("corazon").map { it.emoji })
        assertEquals(listOf("❤️"), c.search("CORAZÓN").map { it.emoji })
        assertEquals(listOf("❤️"), c.search("Heart").map { it.emoji })
        assertEquals(listOf("💡"), c.search("bombilla").map { it.emoji })
        assertTrue(c.search("zzzz").isEmpty())
        assertEquals(c.entries, c.search("  "))
    }

    @Test fun `saca lo que el aparato no puede dibujar`() {
        val c = EmojiCatalog.parse(tsv, canDraw = { it != "🇦🇷" })
        assertEquals(listOf(0, 5), c.byCategory().map { it.first })
    }

    @Test fun `el asset real tiene las 8 categorías y ningún tono de piel`() {
        val text = File("src/main/assets/emoji/emoji.tsv").readText()
        val c = EmojiCatalog.parse(text)
        assertEquals((0..7).toList(), c.byCategory().map { it.first })
        assertTrue(c.entries.none { e -> e.emoji.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF } })
        assertTrue(c.entries.size > 1500)
        assertNotNull(c.search("corazón").firstOrNull { it.emoji == "❤️" })
    }
}
