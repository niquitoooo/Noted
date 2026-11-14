package com.jot.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Las tarjetas de enlace en el export (renglón `bookmarks:` del encabezado) y al importar. */
@RunWith(RobolectricTestRunner::class)
class NoteIOBookmarksTest {

    private val full = Bookmark(
        id = "b1", url = "https://example.com/a?x=1", title = "Un \"título\"", description = "Línea\ndos",
        imageFile = "b1.jpg", siteName = "Example", faviconFile = "b1_icon.png"
    )
    private val bare = Bookmark(id = "b2", url = "https://example.org", title = "Solo")

    private val paths = mapOf("b1" to NoteIO.BookmarkFilePaths("bookmarks/b1.jpg", "bookmarks/b1_icon.png"))

    @Test
    fun exportWritesOneLineWithBothBookmarks() {
        val value = NoteIO.bookmarksToFrontMatter(listOf(full, bare), paths)!!
        assertFalse(value.contains('\n'))
        assertTrue(value.contains("\"image\":\"bookmarks/b1.jpg\""))
        assertTrue(value.contains("\"favicon\":\"bookmarks/b1_icon.png\""))
        assertTrue(value.contains("\"id\":\"b2\""))
    }

    @Test
    fun noBookmarksNoLine() {
        assertEquals(null, NoteIO.bookmarksToFrontMatter(emptyList(), emptyMap()))
    }

    @Test
    fun roundTripKeepsTheData() {
        val value = NoteIO.bookmarksToFrontMatter(listOf(full, bare), paths)!!
        val back = NoteIO.bookmarksFromFrontMatter(value)
        assertEquals(2, back.size)
        assertEquals(
            NoteIO.ImportedBookmark("b1", "https://example.com/a?x=1", "Un \"título\"", "Línea\ndos", "Example",
                "bookmarks/b1.jpg", "bookmarks/b1_icon.png"),
            back[0]
        )
        assertEquals(NoteIO.ImportedBookmark("b2", "https://example.org", "Solo", "", null, null, null), back[1])
    }

    @Test
    fun noteFileHasTheLineInTheHeader() {
        val note = Note(id = "n1", title = "T", body = "<p>x</p>", bookmarks = listOf(bare))
        val text = NoteIO.noteToMarkdownFile(note, emptyMap(), emptyMap(), null)
        val (meta, _) = NoteIO.splitFrontMatter(text)
        assertEquals(listOf("b2"), NoteIO.bookmarksFromFrontMatter(meta.getValue("bookmarks")).map { it.id })
    }

    @Test
    fun malformedHeaderGivesNoBookmarksAndNoCrash() {
        listOf("", "no es json", "[{\"id\":", "{\"a\":1}", "[1,2]", "[{\"id\":\"x\"}]").forEach {
            assertEquals(it, emptyList<NoteIO.ImportedBookmark>(), NoteIO.bookmarksFromFrontMatter(it))
        }
    }

    @Test
    fun emptyTitleStaysEmpty() {
        val value = """[{"id":"a","url":"https://1.com","title":""},{"id":"b","url":"https://2.com"}]"""
        assertEquals(listOf("", ""), NoteIO.bookmarksFromFrontMatter(value).map { it.title })
    }

    @Test
    fun repeatedIdKeepsTheFirst() {
        val value = """[{"id":"a","url":"https://1.com","title":"uno"},{"id":"a","url":"https://2.com","title":"dos"}]"""
        assertEquals(listOf("uno"), NoteIO.bookmarksFromFrontMatter(value).map { it.title })
    }

    @Test
    fun bodyWithMarkAndHeaderBecomesACard() {
        val md = "<!-- jot:bookmark b2 -->\n[Solo](https://example.org)\n"
        val html = runBlocking { NoteIO.markdownFileToBody(md, listOf(bare)) { _, _ -> null } }
        assertTrue(html, html.contains("data-jot-bookmark=\"b2\""))
    }

    @Test
    fun bodyWithMarkButNoCardStaysATextLink() {
        val md = "<!-- jot:bookmark b2 -->\n[Solo](https://example.org)\n"
        val html = runBlocking { NoteIO.markdownFileToBody(md, emptyList()) { _, _ -> null } }
        assertFalse(html, html.contains("data-jot-bookmark"))
        assertTrue(html, html.contains("https://example.org"))
    }

    @Test
    fun unsafeIdGetsANewOneAndTheMarkFollows() {
        val md = "<!-- jot:bookmark ../x -->\n[Solo](https://example.org)\n"
        val (bookmarks, body) = NoteIO.safeBookmarks(
            listOf(NoteIO.ImportedBookmark("../x", "https://example.org", "Solo", "", null, null, null)), md
        )
        val newId = bookmarks.single().id
        assertTrue(com.jot.app.data.NotesRepository.isSafeId(newId))
        assertNotEquals("../x", newId)
        assertTrue(body.contains("<!-- jot:bookmark $newId -->"))
        assertFalse(body.contains("../x"))
    }

    @Test
    fun cardInHeaderWithoutMarkGoesAtTheEnd() {
        val a = NoteIO.ImportedBookmark("a1", "https://a.com", "A", "", null, null, null)
        val b = NoteIO.ImportedBookmark("b1", "https://b.com", "B", "", null, null, null)
        val md = "Hola\n\n<!-- jot:bookmark a1 -->\n[A](https://a.com)\n"
        val body = NoteIO.withMissingMarks(listOf(a, b), md)
        val cards = listOf(
            Bookmark(id = "a1", url = "https://a.com", title = "A"),
            Bookmark(id = "b1", url = "https://b.com", title = "B")
        )
        val html = runBlocking { NoteIO.markdownFileToBody(body, cards) { _, _ -> null } }
        val ids = Regex("data-jot-bookmark=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(html, listOf("a1", "b1"), ids)
    }

    @Test
    fun backslashSlashInATitleSurvives() {
        val tricky = Bookmark(id = "t1", url = "https://x.com/a", title = "a\\/b \\\\/ c/d")
        val value = NoteIO.bookmarksToFrontMatter(listOf(tricky), emptyMap())!!
        assertEquals("a\\/b \\\\/ c/d",NoteIO.bookmarksFromFrontMatter(value).single().title)
    }

    @Test
    fun emptyOrMissingIdsEachGetANewOne() {
        val value = """[{"url":"https://1.com","title":"uno"},{"id":"","url":"https://2.com","title":"dos"}]"""
        val cards = NoteIO.bookmarksFromFrontMatter(value)
        assertEquals(2, cards.size)
        val (fixed, body) = NoteIO.safeBookmarks(cards, "texto")
        assertTrue(fixed.all { NotesRepository.isSafeId(it.id) })
        assertNotEquals(fixed[0].id, fixed[1].id)
        val withMarks = NoteIO.withMissingMarks(fixed, body)
        assertTrue(fixed.all { withMarks.contains("<!-- jot:bookmark ${it.id} -->") })
    }

    @Test
    fun lineSeparatorsInTheCardSurviveTheHeader() {
        val odd = Bookmark(id = "s1", url = "https://x.com", title = "a b\u0085c d", description = "uno dos\u0085tres cuatro")
        val note = Note(id = "n1", title = "Mi nota", notebook = "Cuaderno", body = "<p>x</p>", bookmarks = listOf(odd))
        val text = NoteIO.noteToMarkdownFile(note, emptyMap(), emptyMap(), null)
        val (meta, _) = NoteIO.splitFrontMatter(text)
        assertEquals("n1", meta["id"])
        assertEquals("Mi nota", meta["title"])
        assertEquals("Cuaderno", meta["notebook"])
        val line = meta.getValue("bookmarks")
        assertFalse(line.contains(' ') || line.contains('\u0085') || line.contains(' '))
        val back = NoteIO.bookmarksFromFrontMatter(line).single()
        assertEquals(odd.title, back.title)
        assertEquals(odd.description, back.description)
    }

    @Test
    fun rawLineSeparatorInTheHeaderKeepsTheOtherFields() {
        val raw = "---\nid: n7\ntitle: Con raro\nbookmarks: [{\"url\":\"https://x.com\",\"description\":\"a b\"}]\n---\ncuerpo"
        val (meta, body) = NoteIO.splitFrontMatter(raw)
        assertEquals("n7", meta["id"])
        assertEquals("Con raro", meta["title"])
        assertEquals("cuerpo", body)
        assertEquals("a b", NoteIO.bookmarksFromFrontMatter(meta.getValue("bookmarks")).single().description)
    }

    @Test
    fun jsonNullsAreNotTheTextNull() {
        val value = """[{"id":null,"url":"https://1.com","title":null},{"id":"k2","url":null,"title":"sin url"},{"id":"k3","url":"","title":"vacía"}]"""
        val cards = NoteIO.bookmarksFromFrontMatter(value)
        assertEquals(1, cards.size)
        assertEquals("", cards[0].id)
        assertEquals("", cards[0].title)
        val (fixed, _) = NoteIO.safeBookmarks(cards, "texto")
        assertNotEquals("null", fixed[0].id)
        assertTrue(NotesRepository.isSafeId(fixed[0].id))
    }
}
