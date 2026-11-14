package com.noted.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * El formato de notes.json de hoy. No lleva los campos derivados ni
 * `revision`/`legacyMarkdown`: es el formato de un archivo viejo que solo se lee.
 */
object NotesJson {
    /** Las notas que se pudieron leer; `damaged` si alguna (o el archivo) estaba mal. */
    data class Parsed(val notes: List<Note>, val damaged: Boolean)

    fun parse(text: String): Parsed {
        val notes = ArrayList<Note>()
        var damaged = false
        try {
            val array = JSONObject(text).optJSONArray("notes") ?: JSONArray()
            for (i in 0 until array.length()) {
                // Una nota mal formada no tiene que llevarse puestas a las demás.
                runCatching { notes.add(noteFromJson(array.getJSONObject(i))) }
                    .onFailure { damaged = true }
            }
        } catch (e: Exception) {
            damaged = true
        }
        return Parsed(notes, damaged)
    }

    /** Un archivo de antes de `sortOrder` tiene alguna nota sin él. */
    fun needsSortOrder(notes: List<Note>): Boolean = notes.any { it.sortOrder == Long.MIN_VALUE }

    /** Si hace falta, numera las notas en el orden en que se veían antes de `sortOrder`. */
    fun withSortOrder(notes: List<Note>): List<Note> =
        if (needsSortOrder(notes)) {
            notes.sortedWith(LEGACY_ORDER).mapIndexed { index, note -> note.copy(sortOrder = index.toLong()) }
        } else {
            notes
        }

    /**
     * Copia aparte un archivo que no se pudo leer entero, antes de que algo lo reemplace.
     * Devuelve el nombre de la copia.
     */
    fun backupDamaged(source: File, dir: File): String {
        val backup = "notes-danado-${System.currentTimeMillis()}.json"
        runCatching { source.copyTo(File(dir, backup)) }
        return backup
    }

    private val LEGACY_ORDER: Comparator<Note> =
        compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }

    fun toJson(note: Note): JSONObject = JSONObject().apply {
        put("id", note.id)
        put("title", note.title)
        put("body", note.body)
        put("notebook", note.notebook ?: JSONObject.NULL)
        put("pinned", note.pinned)
        put("trashed", note.trashed)
        put("createdAt", note.createdAt)
        put("updatedAt", note.updatedAt)
        put("sortOrder", note.sortOrder)
        put("tags", JSONArray(note.tags))
        put("icon", note.icon ?: JSONObject.NULL)
        put("trashedWith", note.trashedWith ?: JSONObject.NULL)
        put("attachments", attachmentsToJson(note.attachments))
        put("bookmarks", bookmarksToJson(note.bookmarks))
    }

    fun attachmentsToJson(attachments: List<Attachment>): JSONArray = JSONArray().apply {
        attachments.forEach { attachment ->
            put(JSONObject().apply {
                put("id", attachment.id)
                put("noteId", attachment.noteId)
                put("name", attachment.name)
                put("mime", attachment.mime)
                put("size", attachment.size)
                put("storedName", attachment.storedName)
                put("createdAt", attachment.createdAt)
            })
        }
    }

    fun bookmarksToJson(bookmarks: List<Bookmark>): JSONArray = JSONArray().apply {
        bookmarks.forEach { bookmark ->
            put(JSONObject().apply {
                put("id", bookmark.id)
                put("url", bookmark.url)
                put("title", bookmark.title)
                put("description", bookmark.description)
                put("imageUrl", bookmark.imageUrl ?: JSONObject.NULL)
                put("imageFile", bookmark.imageFile ?: JSONObject.NULL)
                put("faviconFile", bookmark.faviconFile ?: JSONObject.NULL)
                put("siteName", bookmark.siteName ?: JSONObject.NULL)
                put("createdAt", bookmark.createdAt)
            })
        }
    }

    fun attachmentsFromJson(array: JSONArray, noteId: String): List<Attachment> {
        val attachments = ArrayList<Attachment>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            attachments.add(
                Attachment(
                    id = item.optString("id"),
                    noteId = item.optString("noteId", noteId),
                    name = item.optString("name", "archivo"),
                    mime = item.optString("mime", "application/octet-stream"),
                    size = item.optLong("size", 0L),
                    storedName = item.optString("storedName"),
                    createdAt = item.optLong("createdAt", 0L)
                )
            )
        }
        return attachments
    }

    fun bookmarksFromJson(array: JSONArray): List<Bookmark> {
        val bookmarks = ArrayList<Bookmark>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            bookmarks.add(
                Bookmark(
                    id = item.optString("id"),
                    url = item.optString("url"),
                    title = item.optString("title", item.optString("url")),
                    description = item.optString("description"),
                    imageUrl = item.optString("imageUrl")
                        .takeIf { it.isNotBlank() && it != "null" },
                    imageFile = item.optString("imageFile")
                        .takeIf { it.isNotBlank() && it != "null" },
                    faviconFile = item.optString("faviconFile")
                        .takeIf { it.isNotBlank() && it != "null" },
                    siteName = item.optString("siteName")
                        .takeIf { it.isNotBlank() && it != "null" },
                    createdAt = item.optLong("createdAt", 0L)
                )
            )
        }
        return bookmarks
    }

    fun noteFromJson(json: JSONObject): Note {
        val id = json.optString("id")
        return Note(
            id = id,
            title = json.optString("title"),
            body = json.optString("body"),
            notebook = if (json.isNull("notebook")) null
                else json.optString("notebook").takeIf { it.isNotBlank() },
            tags = NotesRepository.parseTags(json.optString("body")),
            pinned = json.optBoolean("pinned"),
            trashed = json.optBoolean("trashed"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            sortOrder = json.optLong("sortOrder", Long.MIN_VALUE),
            attachments = attachmentsFromJson(json.optJSONArray("attachments") ?: JSONArray(), id),
            bookmarks = bookmarksFromJson(json.optJSONArray("bookmarks") ?: JSONArray()),
            icon = json.optString("icon").takeIf { it.isNotBlank() && it != "null" },
            trashedWith = json.optString("trashedWith").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
