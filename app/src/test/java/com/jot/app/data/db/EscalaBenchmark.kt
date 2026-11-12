package com.jot.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * Cuánto aguanta la lista y la búsqueda cuando hay muchas notas.
 *
 * **No corre con la suite**: el nombre no termina en `Test`, así que Gradle no lo
 * descubre. Se corre a mano:
 *
 *     ./gradlew :app:testDebugUnitTest --tests "*EscalaBenchmark*"
 *
 * Toma el texto de las notas reales del Pixel (si está `app/src/test/datos-reales/`)
 * o uno de relleno, y arma bases de 2.000 y 20.000 notas. Mide, por tamaño:
 *
 * - `all()`: todas las notas con su HTML, como hace la lista hoy. Es lo que C2 sacaría.
 * - una proyección sin HTML: lo que devolvería una lista liviana.
 * - `searchIds`: la búsqueda de C1, con una palabra común, una rara y una de 2 letras.
 *
 * También informa cuánto texto queda retenido, que es lo que ocupa memoria.
 */
@RunWith(RobolectricTestRunner::class)
class EscalaBenchmark {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class Seed(val title: String, val text: String, val links: String, val html: String)

    private fun seeds(): List<Seed> {
        val real = File("src/test/datos-reales/pixel/databases")
        if (File(real, NotesDatabase.NAME).exists()) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                File(real, NotesDatabase.NAME).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val out = db.rawQuery("SELECT title, text, links, html FROM note", null).use { c ->
                buildList {
                    while (c.moveToNext()) add(Seed(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
                }
            }
            db.close()
            if (out.isNotEmpty()) return out
        }
        val body = (1..40).joinToString(" ") { "palabra$it de relleno para medir la escala de la lista" }
        return listOf(Seed("Nota de relleno", body, "https://ejemplo.com/relleno", "<p>$body</p>"))
    }

    @Test
    fun medir() {
        val seed = seeds()
        println()
        println("Semillas: ${seed.size} notas reales, ${seed.sumOf { it.text.length } / 1024} KB de texto")
        for (n in listOf(2_000, 20_000)) {
            run(n, seed)
        }
    }

    private fun run(n: Int, seeds: List<Seed>) {
        val name = "bench-$n.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        listOf("", "-wal", "-shm").forEach { File(path.path + it).delete() }

        val room = Room.databaseBuilder(context, NotesDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val raw = room.openHelper.writableDatabase
            insert(raw, n, seeds)

            println()
            println("== $n notas ==")
            println(String.format(Locale.ROOT, "  %-42s %8s", "texto en la base", "${sizeMb(raw)} MB"))

            val dao = room.dao()

            measure("all()  (todas con HTML, como hoy)") {
                runBlocking { dao.all() }
            }
            val full = runBlocking { dao.all() }
            println(String.format(Locale.ROOT, "  %-42s %8.1f MB", "  retenido por all()", heldMb(full)))

            measure("proyeccion sin HTML (lista liviana)") {
                raw.query(
                    "SELECT id, title, summary, hasTodo, notebook, tags, pinned, trashed, " +
                        "createdAt, updatedAt, sortOrder, icon, cover, coverX, coverY, coverScale, " +
                        "trashedWith, noteLinkIds, revision, searchKey FROM note"
                ).use { c -> while (c.moveToNext()) { c.getString(0) } }
            }

            for (q in listOf("de", "nota", "cabe", "zz", "palabra37")) {
                val key = SearchKey.fold(q)
                val ids = measure("searchIds(\"$q\")") { runBlocking { dao.searchIds(key) } }
                val found = runBlocking { dao.searchIds(key) }.size
                println(String.format(Locale.ROOT, "  %-42s %8d notas", "  encontradas por \"$q\"", found))
                require(ids >= 0)
            }
        } finally {
            room.close()
            listOf("", "-wal", "-shm").forEach { File(path.path + it).delete() }
        }
    }

    /** Inserta en una transacción y con un statement por fila; no se mide el alta. */
    private fun insert(db: SupportSQLiteDatabase, n: Int, seeds: List<Seed>) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT INTO note (id, title, html, text, links, summary, hasTodo, notebook, tags, " +
                    "pinned, trashed, createdAt, updatedAt, sortOrder, attachments, bookmarks, icon, " +
                    "trashedWith, noteLinkIds, revision, legacyMarkdown, cover, coverX, coverY, " +
                    "coverScale, searchKey) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            for (i in 0 until n) {
                val s = seeds[i % seeds.size]
                val title = "${s.title} ${i + 1}"
                val text = "${s.text} nota numero ${i + 1}"
                val links = s.links
                st.clearBindings()
                st.bindString(1, "id-$i")
                st.bindString(2, title)
                st.bindString(3, s.html)
                st.bindString(4, text)
                st.bindString(5, links)
                st.bindString(6, text.take(120))
                st.bindLong(7, if (text.contains("☐")) 1 else 0)
                st.bindString(8, "Cuaderno ${i % 12}")
                st.bindString(9, "[]")
                st.bindLong(10, if (i % 20 == 0) 1 else 0)
                st.bindLong(11, if (i % 30 == 0) 1 else 0)
                st.bindLong(12, now - i * 1000L)
                st.bindLong(13, now - i * 1000L)
                st.bindLong(14, i.toLong())
                st.bindString(15, "[]")
                st.bindString(16, "[]")
                st.bindNull(17)
                st.bindNull(18)
                st.bindString(19, "[]")
                st.bindLong(20, 1)
                st.bindNull(21)
                st.bindNull(22)
                st.bindDouble(23, 0.5)
                st.bindDouble(24, 0.5)
                st.bindDouble(25, 1.0)
                st.bindString(26, SearchKey.of(title, text, links))
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Ejecuta [block] una vez de calentamiento y tres medidas; informa el promedio. */
    private fun measure(label: String, block: () -> Unit): Double {
        block()
        val times = (1..3).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000.0
        }
        val avg = times.average()
        println(String.format(Locale.ROOT, "  %-42s %8.1f ms", label, avg))
        return avg
    }

    private fun sizeMb(db: SupportSQLiteDatabase): String {
        val pageSize = db.query("PRAGMA page_size").use { c -> c.moveToFirst(); c.getLong(0) }
        val pages = db.query("PRAGMA page_count").use { c -> c.moveToFirst(); c.getLong(0) }
        return String.format(Locale.ROOT, "%.1f", pageSize * pages / 1024.0 / 1024.0)
    }

    /** Lo que ocupa en memoria lo que devuelve `all()`, en UTF-16 (2 bytes por letra). */
    private fun heldMb(notes: List<NoteEntity>): Double {
        val chars = notes.sumOf {
            it.id.length + it.title.length + it.html.length + it.text.length + it.links.length +
                it.summary.length + it.tags.length + it.attachments.length + it.bookmarks.length +
                it.noteLinkIds.length + (it.notebook?.length ?: 0) + (it.cover?.length ?: 0) +
                (it.icon?.length ?: 0) + (it.legacyMarkdown?.length ?: 0)
        }
        return chars * 2.0 / 1024.0 / 1024.0
    }
}
