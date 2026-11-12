package com.jot.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jot.app.data.html.NoteMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Importa una exportación en Markdown (con su carpeta de adjuntos) y exporta
 * nuestras notas a Markdown estándar, con los adjuntos en una carpeta aparte.
 */
object NoteIO {

    /** [inTrashedNotebook]: notas que no se importaron porque su cuaderno está en la papelera. */
    data class ImportResult(val notes: Int, val attachments: Int, val skipped: Int, val inTrashedNotebook: Int = 0)

    /** El cuaderno de una nota importada: el del encabezado o, si no tiene, la carpeta. */
    internal fun importNotebook(meta: Map<String, String>, folder: String?): String? =
        (meta["notebook"] ?: folder)?.trim()?.takeIf { it.isNotEmpty() }

    /** Si la nota iría a un cuaderno de [trashed] (sin importar mayúsculas): esa no se importa. */
    internal fun goesToTrashedNotebook(meta: Map<String, String>, folder: String?, trashed: Set<String>): Boolean {
        val name = importNotebook(meta, folder) ?: return false
        return trashed.any { it.equals(name, ignoreCase = true) }
    }

    private data class Entry(val path: String, val doc: DocumentFile)

    private val MARKDOWN_LINK = Regex("!?\\[([^\\]]*)]\\(([^)\\s]+)\\)")

    // --------------------------------------------------------------- importar

    suspend fun importFolder(
        context: Context,
        repo: NotesRepository,
        treeUri: Uri
    ): ImportResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ImportResult(0, 0, 0)

        val entries = ArrayList<Entry>()
        collect(root, "", entries, 0)

        val byPath = entries.associateBy { it.path.lowercase(Locale.ROOT) }
        val byName = entries.groupBy { it.doc.name?.lowercase(Locale.ROOT) ?: "" }

        var noteCount = 0
        var attachmentCount = 0
        var skipped = 0
        var inTrashedNotebook = 0
        val trashedNotebooks = repo.notebooksTrashed.value

        // Primera pasada: leer cada .md y decidir el id de su nota (sin crearla
        // todavía). Así, en la segunda, un enlace a otro .md del mismo export se puede
        // convertir en una nota de adentro, y si se corta a la mitad no quedan notas
        // vacías. Las carpetas attachments/, icons/ y bookmarks/ de nuestro export no son notas.
        val candidates = entries.asSequence()
            .filter { !it.doc.isDirectory }
            .filter { isText(it.doc.name) }
            .filterNot { entry ->
                val lower = entry.path.lowercase(Locale.ROOT)
                lower.startsWith("attachments/") || lower.startsWith("icons/") || lower.startsWith("covers/") ||
                    lower.startsWith("bookmarks/")
            }
            .toList()
        val raws = HashMap<Entry, String?>()
        fun raw(entry: Entry): String? =
            raws.getOrPut(entry) { readText(context, entry.doc)?.removePrefix("﻿") }
        // Un .txt que enlaza un .md es un adjunto de esa nota (los de files/ en un export).
        // No es una nota aparte.
        val linkedText = linkedTextFiles(
            candidates.filter { isMarkdown(it.doc.name) }.mapNotNull { entry -> raw(entry)?.let { entry.path to it } },
            byPath, byName
        )
        val textEntries = candidates.filterNot { it in linkedText }.take(2000)

        data class Planned(
            val entry: Entry,
            val meta: Map<String, String>,
            val content: String,
            val id: String,
            val folder: String?
        )
        val planned = ArrayList<Planned>()
        val noteIdByPath = HashMap<String, String>()
        // Ids ya usados en esta misma importación (dos copias del mismo export).
        val plannedIds = HashSet<String>()
        textEntries.forEach { entry ->
            val raw = raw(entry)
            if (raw.isNullOrBlank()) {
                skipped++
                return@forEach
            }
            val (meta, content) = splitFrontMatter(raw)
            // Un id que no sirve como nombre de carpeta (".", "..") no se usa.
            val originalId = meta["id"]?.trim()?.takeIf { NotesRepository.isSafeId(it) }
            // Ya importada antes (mismo id): no se duplica, pero los enlaces a ella
            // de las otras notas siguen apuntando a la que ya está.
            if (originalId != null && (repo.findNote(originalId) != null || originalId in plannedIds)) {
                noteIdByPath[entry.path.lowercase(Locale.ROOT)] = originalId
                skipped++
                return@forEach
            }
            val id = originalId ?: UUID.randomUUID().toString()
            val folder = entry.path.substringBeforeLast('/', "")
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            // A un cuaderno de la papelera no se importa: hay que restaurarlo primero.
            if (goesToTrashedNotebook(meta, folder, trashedNotebooks)) {
                inTrashedNotebook++
                return@forEach
            }
            planned.add(Planned(entry, meta, content, id, folder))
            plannedIds.add(id)
            noteIdByPath[entry.path.lowercase(Locale.ROOT)] = id
        }

        planned.forEach { (entry, meta, content, id, folder) ->
            val note = repo.createNote(folder, id)
            // Si el id ya estaba en uso, createNote dio uno nuevo: los enlaces de las
            // otras notas tienen que apuntar a ese.
            if (note.id != id) noteIdByPath[entry.path.lowercase(Locale.ROOT)] = note.id
            var body = content
            var title = meta["title"]
                ?: entry.doc.name?.substringBeforeLast('.')?.trim().orEmpty()
            val heading = Regex("^#\\s+(.+)\\n*").find(body)
            when {
                // Export viejo de esta app: una nota sin título salía como
                // "title: Sin título" con "# Sin título" arriba del texto.
                heading != null && meta["title"] == UNTITLED &&
                    heading.groupValues[1].trim() == UNTITLED -> {
                    title = ""
                    body = body.substring(heading.range.last + 1)
                }
                // Archivo sin título propio: el primer "# …" lo da (y queda en el texto).
                meta["title"] == null && title.isBlank() && heading != null ->
                    title = heading.groupValues[1].trim()
            }

            // Las tarjetas se crean antes de convertir el cuerpo: así la marca las encuentra.
            val (importedCards, cardBody) = safeBookmarks(bookmarksFromFrontMatter(meta["bookmarks"].orEmpty()), body)
            body = withMissingMarks(importedCards, cardBody)
            // La nota queda ocupada mientras se crean sus tarjetas y adjuntos: releaseUnusedContents
            // no las aparta a mitad de la importación.
            val icon = meta["icon"]
            repo.whileBusy(note.id) {
            val cards = importedCards.map { card ->
                fun uriOf(path: String?) =
                    path?.let { resolveTarget(it, entry.path, byPath, byName) }?.takeIf { f -> !f.doc.isDirectory }?.doc?.uri
                val saved = repo.addKnownBookmark(
                    note.id,
                    Bookmark(
                        id = card.id, url = card.url, title = card.title,
                        description = card.description, siteName = card.siteName
                    ),
                    uriOf(card.image), uriOf(card.favicon)
                )
                // Si el id ya tenía imágenes de otra tarjeta, el repositorio le dio uno nuevo.
                if (saved.id != card.id) body = renameBookmarkMark(body, card.id, saved.id)
                saved
            }

            val html = markdownFileToBody(body, cards) { label, target ->
                val replacement = resolveTarget(target, entry.path, byPath, byName)
                val linkedNoteId = replacement?.let { noteIdByPath[it.path.lowercase(Locale.ROOT)] }
                when {
                    linkedNoteId != null ->
                        LinkReplacement("[$label](jot://note/$linkedNoteId)")

                    replacement != null && !replacement.doc.isDirectory -> {
                        val attachment = repo.addAttachment(
                            noteId = note.id,
                            uri = replacement.doc.uri,
                            // El export le antepone "<id>_" al nombre solo en attachments/:
                            // se saca, así cada ida y vuelta no le suma otro prefijo.
                            knownName = importedAttachmentName(replacement.path, replacement.doc.name),
                            knownMime = replacement.doc.type
                                ?: guessMime(replacement.doc.name ?: "")
                        )
                        attachment?.let {
                            attachmentCount++
                            LinkReplacement(NoteMarkdown.attachmentToken(it), it)
                        }
                    }

                    // Ruta relativa que no se encontró: queda solo el texto del enlace.
                    !target.startsWith("http", true) &&
                        !target.startsWith("mailto", true) &&
                        !target.startsWith("jot://", true) ->
                        LinkReplacement(label)

                    else -> null
                }
            }

            repo.update(note.id) {
                it.copy(
                    title = title,
                    body = html,
                    notebook = meta["notebook"] ?: it.notebook,
                    icon = inlineIcon(icon),
                    pinned = meta["pinned"] == "true",
                    createdAt = meta["created"]?.let { value -> parseInstant(value) }
                        ?: entry.doc.lastModified().takeIf { time -> time > 0 }
                        ?: System.currentTimeMillis(),
                    updatedAt = meta["updated"]?.let { value -> parseInstant(value) }
                        ?: System.currentTimeMillis()
                )
            }
            }
            // Ícono propio exportado como archivo en la carpeta icons/.
            if (icon != null && iconIsFile(icon)) {
                resolveTarget(icon, entry.path, byPath, byName)?.let { file ->
                    repo.setIconFromUri(note.id, file.doc.uri)
                }
            }
            // Portada (nuestro export): color o degradado tal cual, imagen desde covers/; y su encuadre.
            meta["cover"]?.takeIf { it.isNotBlank() }?.let { cover ->
                val crop = parseCoverCrop(meta["coverCrop"])
                if (cover.startsWith(NotesRepository.COVER_COLOR) || cover.startsWith(NotesRepository.COVER_GRADIENT)) {
                    repo.setCoverPreset(note.id, cover)
                } else {
                    resolveTarget(cover, entry.path, byPath, byName)?.let { file ->
                        repo.setCoverFromUri(note.id, file.doc.uri)
                    }
                }
                if (crop != null) repo.setCoverCrop(note.id, crop.first, crop.second, crop.third)
            }
            noteCount++
        }

        // Cuadernos (nuestro export), con su ícono; también los vacíos.
        byPath[NOTEBOOKS_FILE]?.let { entry ->
            val icons = readText(context, entry.doc)?.let { notebooksFromJson(it) }.orEmpty()
            // Cada cuaderno nuevo va arriba de todo: se crean del último al primero para que
            // queden en el orden del export.
            icons.entries.reversed().forEach { (raw, icon) ->
                // Los cuadernos vacíos también vienen en el export: se crean igual.
                val name = repo.createNotebook(raw) ?: return@forEach
                if (icon.isEmpty()) return@forEach
                if (icon.startsWith(NotesRepository.ICON_EMOJI)) {
                    repo.setNotebookEmojiIcon(name, icon.removePrefix(NotesRepository.ICON_EMOJI))
                } else {
                    byPath[icon.lowercase(Locale.ROOT)]?.let { file ->
                        repo.setNotebookIconFromUri(name, file.doc.uri)
                    }
                }
            }
        }

        repo.flush()
        ImportResult(noteCount, attachmentCount, skipped, inTrashedNotebook)
    }

    /** Una tarjeta del renglón `bookmarks:`; [image] y [favicon] son rutas relativas al .md. */
    internal data class ImportedBookmark(
        val id: String,
        val url: String,
        val title: String,
        val description: String,
        val siteName: String?,
        val image: String?,
        val favicon: String?
    )

    /** Dónde quedaron, en el export, la imagen y el ícono de una tarjeta. */
    internal data class BookmarkFilePaths(val image: String?, val favicon: String?)

    /**
     * El valor del renglón `bookmarks:`: un arreglo JSON en un solo renglón, o null si no hay
     * tarjetas. [files] da las rutas (`bookmarks/<archivo>`) por id de tarjeta.
     */
    internal fun bookmarksToFrontMatter(
        bookmarks: List<Bookmark>,
        files: Map<String, BookmarkFilePaths>
    ): String? {
        if (bookmarks.isEmpty()) return null
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            val paths = files[bookmark.id]
            array.put(
                JSONObject()
                    .put("id", bookmark.id)
                    .put("url", bookmark.url)
                    .put("title", bookmark.title)
                    .put("description", bookmark.description)
                    .put("siteName", bookmark.siteName ?: JSONObject.NULL)
                    .put("image", paths?.image ?: JSONObject.NULL)
                    .put("favicon", paths?.favicon ?: JSONObject.NULL)
            )
        }
        // org.json escapa cada "/" como "\/": se desescapa solo el que tiene un número impar de
        // barras antes (el escape). Con un número par, las barras son texto y la "/" ya es suelta.
        // Los separadores de renglón que org.json deja crudos (U+0085, U+2028, U+2029) se escapan
        // a mano: no son sintaxis de JSON, pero cortarían el renglón del encabezado.
        return array.toString()
            .replace(Regex("(?<!\\\\)((?:\\\\\\\\)*)\\\\/"), "$1/")
            .replace(Regex("[\\x{85}\\x{2028}\\x{2029}]")) { "\\u%04x".format(it.value[0].code) }
    }

    /** Lee el valor de `bookmarks:`. Algo mal formado da una lista vacía; un id repetido deja el primero. */
    internal fun bookmarksFromFrontMatter(value: String): List<ImportedBookmark> {
        if (value.isBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            val seen = HashSet<String>()
            val out = ArrayList<ImportedBookmark>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = if (obj.isNull("url")) "" else obj.optString("url", "").trim()
                if (url.isEmpty()) continue
                val id = if (obj.isNull("id")) "" else obj.optString("id", "")
                // Sin id no hay con qué compararlo: cada una recibe uno nuevo al importar.
                if (id.isNotEmpty() && !seen.add(id)) continue
                fun text(key: String) = if (obj.isNull(key)) null else obj.optString(key, "").takeIf { it.isNotBlank() }
                out.add(
                    ImportedBookmark(
                        id = id, url = url, title = if (obj.isNull("title")) "" else obj.optString("title", "").trim(),
                        description = if (obj.isNull("description")) "" else obj.optString("description", ""),
                        siteName = text("siteName"), image = text("image"), favicon = text("favicon")
                    )
                )
            }
            out
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    /**
     * Las tarjetas con un id que sirve como nombre de archivo: el que no sirve recibe uno nuevo,
     * y la marca del [body] pasa a llevar ese mismo id.
     */
    internal fun safeBookmarks(
        cards: List<ImportedBookmark>,
        body: String
    ): Pair<List<ImportedBookmark>, String> {
        var text = body
        val fixed = cards.map { card ->
            if (NotesRepository.isSafeId(card.id)) {
                card
            } else {
                val newId = UUID.randomUUID().toString()
                if (card.id.isNotEmpty()) text = renameBookmarkMark(text, card.id, newId)
                card.copy(id = newId)
            }
        }
        return fixed to text
    }

    /** La marca `<!-- jot:bookmark [oldId] -->` de [body] pasa a llevar [newId]. */
    internal fun renameBookmarkMark(body: String, oldId: String, newId: String): String =
        Regex("<!--\\s*jot:bookmark\\s+" + Regex.escape(oldId) + "\\s*-->")
            .replace(body, Regex.escapeReplacement("<!-- jot:bookmark $newId -->"))

    /**
     * Agrega al final de [body] la marca y el enlace de cada tarjeta de [cards] que el cuerpo
     * no nombra. Sin eso la tarjeta quedaría en la nota sin bloque, y se descartaría.
     */
    internal fun withMissingMarks(cards: List<ImportedBookmark>, body: String): String {
        val named = Regex("<!--\\s*jot:bookmark\\s+(\\S+)\\s*-->").findAll(body)
            .map { it.groupValues[1] }.toSet()
        val missing = cards.filter { it.id !in named }
        if (missing.isEmpty()) return body
        return buildString {
            append(body.trimEnd('\n'))
            missing.forEach { card ->
                if (isNotEmpty()) append("\n\n")
                val title = card.title.replace("]", "］").replace("\n", " ").ifBlank { card.url }
                append("<!-- jot:bookmark ").append(card.id).append(" -->\n")
                append('[').append(title).append("](").append(card.url.replace(")", "）")).append(')')
            }
            append('\n')
        }
    }

    /** El valor de `icon:` si se guarda tal cual: `builtin:…`, o `emoji:` con algo después. */
    internal fun inlineIcon(value: String?): String? = value?.takeIf {
        it.startsWith(NotesRepository.ICON_BUILTIN) ||
            (it.startsWith(NotesRepository.ICON_EMOJI) && it.length > NotesRepository.ICON_EMOJI.length)
    }

    /** Si el valor de `icon:` apunta a un archivo de icons/ y no a un valor propio. */
    /** Tipo de un archivo de ícono por su extensión (SVG, PNG, JPG…). */
    private fun iconMime(file: File): String = when (val ext = file.extension.lowercase()) {
        "svg" -> "image/svg+xml"
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?.takeIf { it.startsWith("image/") } ?: "image/png"
    }

    internal fun iconIsFile(value: String?): Boolean =
        value != null && !value.startsWith(NotesRepository.ICON_BUILTIN) &&
            !value.startsWith(NotesRepository.ICON_EMOJI)

    /** Con qué se reemplaza un enlace del .md al importarlo; [attachment] si se volvió un adjunto. */
    internal data class LinkReplacement(val text: String, val attachment: Attachment? = null)

    /**
     * El cuerpo HTML de una nota importada a partir del Markdown de su archivo (sin el
     * encabezado). Cada enlace pasa por [resolveLink]; `null` lo deja como está. Los
     * adjuntos que devuelve se leen como bloques, igual que en el editor.
     */
    internal suspend fun markdownFileToBody(
        markdown: String,
        bookmarks: List<Bookmark>,
        resolveLink: suspend (label: String, target: String) -> LinkReplacement?
    ): String {
        val attachments = ArrayList<Attachment>()
        val builder = StringBuilder()
        var last = 0
        MARKDOWN_LINK.findAll(markdown).forEach { match ->
            builder.append(markdown, last, match.range.first)
            val replacement = resolveLink(match.groupValues[1], match.groupValues[2])
            replacement?.attachment?.let(attachments::add)
            builder.append(replacement?.text ?: match.value)
            last = match.range.last + 1
        }
        builder.append(markdown, last, markdown.length)
        return NoteMarkdown.toHtml(builder.toString(), attachments, bookmarks)
    }

    /**
     * Separa el encabezado `---` que pone el export (title, notebook, icon…) del cuerpo.
     * Solo cuenta como encabezado si todos sus renglones son `clave: valor`: un
     * Markdown común que empieza con un separador `---` se deja entero.
     */
    internal fun splitFrontMatter(raw: String): Pair<Map<String, String>, String> {
        val text = raw.replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---\n", 4)
        if (end < 0) return emptyMap<String, String>() to text
        val lines = text.substring(4, end).lines().filter { it.isNotBlank() }
        if (lines.isEmpty() || lines.any { !FRONT_MATTER_LINE.matches(it) }) {
            return emptyMap<String, String>() to text
        }
        val meta = lines.associate { line ->
            val colon = line.indexOf(':')
            line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        return meta to text.substring(end + 5).trimStart('\n')
    }

    private val FRONT_MATTER_LINE = Regex("^[A-Za-z][\\w-]*:[^\n]*$")
    private val EXPORT_PREFIX = Regex("^[0-9a-fA-F]{8}_")

    /**
     * El nombre de un adjunto importado. El export de la app antepone "<id de 8>_" a los
     * archivos de attachments/ y se saca, así cada ida y vuelta no le suma otro prefijo; fuera
     * de esa carpeta el nombre queda tal cual ("20250902_105830.gif" es una fecha, no un id).
     */
    internal fun importedAttachmentName(path: String, name: String?): String? =
        if (path.lowercase(Locale.ROOT).startsWith("attachments/")) name?.replace(EXPORT_PREFIX, "") else name

    private const val UNTITLED = "Sin título"

    /** Destino de un enlace Markdown: sin espacios ni paréntesis que lo corten. */
    private fun linkTarget(path: String): String =
        Uri.encode(path, "/").replace("(", "%28").replace(")", "%29")

    private fun parseInstant(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun collect(dir: DocumentFile, path: String, out: MutableList<Entry>, depth: Int) {
        if (depth > 8 || out.size > 6000) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            val childPath = if (path.isEmpty()) name else "$path/$name"
            if (child.isDirectory) {
                collect(child, childPath, out, depth + 1)
            } else {
                out.add(Entry(childPath, child))
            }
        }
    }

    /**
     * Los .txt de la importación que enlaza alguno de los [markdownFiles] (ruta y texto de
     * cada .md). Esos son adjuntos de la nota que los enlaza; un .md enlazado sigue siendo nota.
     */
    internal fun <T> linkedTextFiles(
        markdownFiles: List<Pair<String, String>>,
        byPath: Map<String, T>,
        byName: Map<String, List<T>>
    ): Set<T> {
        val linked = LinkedHashSet<T>()
        markdownFiles.forEach { (path, text) ->
            val (_, content) = splitFrontMatter(text)
            MARKDOWN_LINK.findAll(content).forEach { match ->
                val target = match.groupValues[2]
                val decoded = Uri.decode(target).substringBefore('#').substringBefore('?')
                if (decoded.lowercase(Locale.ROOT).endsWith(".txt")) {
                    resolveTarget(target, path, byPath, byName)?.let(linked::add)
                }
            }
        }
        return linked
    }

    private fun <T> resolveTarget(
        target: String,
        notePath: String,
        byPath: Map<String, T>,
        byName: Map<String, List<T>>
    ): T? {
        if (target.startsWith("http", true) ||
            target.startsWith("mailto:", true) ||
            target.startsWith("data:", true) ||
            target.startsWith("jot://", true) ||
            target.startsWith("#")
        ) return null

        var clean = Uri.decode(target)
            .substringBefore('#')
            .substringBefore('?')
            .replace('\\', '/')
            .removePrefix("./")
            .removePrefix("/")
            .trim()

        if (clean.isEmpty()) return null

        val parent = notePath.substringBeforeLast('/', "")
        val relative = if (parent.isBlank()) clean else "$parent/$clean"
        val normalized = normalize(relative)

        byPath[normalized.lowercase(Locale.ROOT)]?.let { return it }
        byPath[clean.lowercase(Locale.ROOT)]?.let { return it }

        val fileName = clean.substringAfterLast('/').lowercase(Locale.ROOT)
        return byName[fileName]?.firstOrNull()
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun isText(name: String?): Boolean {
        val lower = name?.lowercase(Locale.ROOT) ?: return false
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
    }

    private fun isMarkdown(name: String?): Boolean {
        val lower = name?.lowercase(Locale.ROOT) ?: return false
        return lower.endsWith(".md") || lower.endsWith(".markdown")
    }

    private fun readText(context: Context, doc: DocumentFile): String? = runCatching {
        context.contentResolver.openInputStream(doc.uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > 8 * 1024 * 1024) null else bytes.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    // --------------------------------------------------------------- portada

    /** El encuadre de la portada en el encabezado: `x,y,zoom` con punto decimal. */
    internal fun coverCropText(x: Float, y: Float, scale: Float): String =
        String.format(Locale.ROOT, "%.4f,%.4f,%.4f", x, y, scale)

    /** Lee [coverCropText]; null si falta o está mal. */
    internal fun parseCoverCrop(text: String?): Triple<Float, Float, Float>? {
        val parts = text?.split(',')?.map { it.trim().toFloatOrNull() } ?: return null
        if (parts.size != 3 || parts.any { it == null }) return null
        return Triple(
            parts[0]!!.coerceIn(0f, 1f),
            parts[1]!!.coerceIn(0f, 1f),
            parts[2]!!.coerceIn(1f, NotesRepository.MAX_COVER_ZOOM)
        )
    }

    // --------------------------------------------------------------- cuadernos

    /** Archivo del export con el ícono de cada cuaderno (en la raíz). */
    internal const val NOTEBOOKS_FILE = "notebooks.json"

    /**
     * `{"notebooks":[{"name":…,"icon":…}]}`. [icons]: nombre → `emoji:…` o la ruta del archivo
     * en el export (`icons/<archivo>`).
     */
    internal fun notebooksToJson(icons: Map<String, String>): String {
        val array = JSONArray()
        icons.forEach { (name, icon) -> array.put(JSONObject().put("name", name).put("icon", icon)) }
        return JSONObject().put("notebooks", array).toString(2)
    }

    /**
     * Lee [notebooksToJson]. Se queda solo con nombres no vacíos y con íconos vacíos, `emoji:…` o
     * rutas de icons/ sin `..`: cualquier otra cosa se ignora. Un archivo roto da un mapa vacío.
     */
    internal fun notebooksFromJson(text: String): Map<String, String> {
        val array = runCatching { JSONObject(text).optJSONArray("notebooks") }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val icon = item.optString("icon")
            val valid = when {
                icon.isEmpty() -> true
                icon.startsWith(NotesRepository.ICON_EMOJI) ->
                    icon.length > NotesRepository.ICON_EMOJI.length
                else -> icon.startsWith("icons/") && ".." !in icon && icon.length > "icons/".length
            }
            if (name.isNotEmpty() && valid) result[name] = icon
        }
        return result
    }

    // --------------------------------------------------------------- exportar

    /** [notes]: notas escritas bien; [failed]: notas o archivos que no se pudieron escribir. */
    data class ExportResult(val notes: Int, val failed: Int)

    suspend fun exportAll(
        context: Context,
        repo: NotesRepository,
        treeUri: Uri
    ): ExportResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ExportResult(0, 1)
        val attachmentsDir = root.findFile("attachments") ?: root.createDirectory("attachments")
            ?: return@withContext ExportResult(0, 1)

        var count = 0
        var failed = 0
        val notes = repo.notes.value.filterNot { it.trashed }

        // Nombre de archivo de cada nota antes de escribir ninguna: los enlaces a
        // notas de adentro apuntan al .md de esa nota.
        val usedNames = HashSet<String>()
        val fileNames = notes.associate { note ->
            val baseName = sanitize(note.displayTitle.ifBlank { "nota" })
            var candidate = "$baseName.md"
            var suffix = 2
            while (!usedNames.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$baseName ($suffix).md"
                suffix++
            }
            note.id to candidate
        }

        // La carpeta bookmarks/ se busca o crea una sola vez, y solo si alguna tarjeta la necesita.
        val bookmarksDir by lazy { root.findFile("bookmarks") ?: root.createDirectory("bookmarks") }

        notes.forEach { note ->
            val attachmentPaths = LinkedHashMap<String, String>()
            note.attachments.forEach { attachment ->
                val file = repo.attachmentFile(attachment)
                if (!file.exists()) return@forEach

                // Con el id del adjunto en el nombre, dos archivos que se llaman
                // igual en la misma nota no se pisan.
                val exportName = "${attachment.id.take(8)}_${sanitize(attachment.name)}"
                // El sistema puede cambiarle el nombre (agregar la extensión): el
                // enlace usa el nombre con el que quedó de verdad.
                val written = copyToDocument(context, attachmentsDir, exportName, attachment.mime, file)
                if (written == null) failed++
                attachmentPaths[attachment.id] = "attachments/${written ?: exportName}"
            }

            val iconValue = note.icon?.let { icon ->
                val iconFile = repo.iconFile(note)
                when {
                    iconFile == null -> icon
                    !iconFile.exists() -> null
                    else -> {
                        val iconsDir = root.findFile("icons") ?: root.createDirectory("icons")
                        iconsDir?.let { dir ->
                            val mime = iconMime(iconFile)
                            val written = copyToDocument(context, dir, iconFile.name, mime, iconFile)
                            if (written == null) failed++
                            linkTarget("icons/${written ?: iconFile.name}")
                        }
                    }
                }
            }

            val bookmarkPaths = HashMap<String, BookmarkFilePaths>()
            note.bookmarks.forEach { bookmark ->
                fun export(file: File?, mime: String): String? {
                    if (file == null || !file.exists()) return null
                    val written = bookmarksDir?.let { copyToDocument(context, it, file.name, mime, file) }
                    if (written == null) failed++
                    return "bookmarks/${written ?: return null}"
                }
                fun mimeOf(file: File?) = guessMime(file?.name ?: "").takeIf { it.startsWith("image/") } ?: "image/*"
                val imageFile = repo.bookmarkImageFile(bookmark)
                val faviconFile = repo.bookmarkFaviconFile(bookmark)
                bookmarkPaths[bookmark.id] =
                    BookmarkFilePaths(export(imageFile, mimeOf(imageFile)), export(faviconFile, mimeOf(faviconFile)))
            }

            // Portada: color o degradado tal cual; una imagen va a la carpeta covers/.
            val coverValue = note.cover?.let { cover ->
                val coverFile = repo.coverFile(note)
                when {
                    coverFile == null -> cover
                    !coverFile.exists() -> null
                    else -> {
                        val coversDir = root.findFile("covers") ?: root.createDirectory("covers")
                        coversDir?.let { dir ->
                            val written = copyToDocument(context, dir, coverFile.name, "image/jpeg", coverFile)
                            if (written == null) failed++
                            linkTarget("covers/${written ?: coverFile.name}")
                        }
                    }
                }
            }

            val markdown = noteToMarkdownFile(note, attachmentPaths, fileNames, iconValue, bookmarkPaths, coverValue)

            if (writeDocument(context, root, fileNames.getValue(note.id), "text/markdown", markdown)) {
                count++
            } else {
                failed++
            }
        }

        // Todos los cuadernos, también los vacíos: emoji tal cual, imagen en icons/, "" sin ícono.
        val notebookIcons = LinkedHashMap<String, String>()
        val allIcons = repo.notebookIcons.value
        repo.notebookOrder.value.filter { it in allIcons }.forEach { name ->
            val icon = allIcons.getValue(name)
            val file = repo.notebookIconFile(icon)
            when {
                file == null -> notebookIcons[name] = icon
                !file.exists() -> notebookIcons[name] = ""
                else -> {
                    val dir = root.findFile("icons") ?: root.createDirectory("icons")
                    val mime = iconMime(file)
                    val written = dir?.let { copyToDocument(context, it, file.name, mime, file) }
                    if (written == null) failed++
                    notebookIcons[name] = written?.let { "icons/$it" } ?: ""
                }
            }
        }
        if (notebookIcons.isNotEmpty() &&
            !writeDocument(context, root, NOTEBOOKS_FILE, "application/json", notebooksToJson(notebookIcons))
        ) {
            failed++
        }

        repo.flush()
        ExportResult(count, failed)
    }

    /**
     * El texto del .md de una nota: el encabezado y el cuerpo en el Markdown de la app.
     * Los enlaces a adjuntos apuntan a [attachmentPaths] (id → ruta del archivo exportado)
     * y los de notas de adentro a [noteFiles] (id → nombre del .md).
     */
    internal fun noteToMarkdownFile(
        note: Note,
        attachmentPaths: Map<String, String>,
        noteFiles: Map<String, String>,
        iconValue: String?,
        bookmarkPaths: Map<String, BookmarkFilePaths> = emptyMap(),
        coverValue: String? = null
    ): String {
        var body = NoteMarkdown.fromHtml(note.body, note.attachments, note.bookmarks)
        attachmentPaths.forEach { (id, path) ->
            body = body.replace("jot://att/$id", linkTarget(path))
        }
        noteFiles.forEach { (id, fileName) ->
            body = body.replace("jot://note/$id", linkTarget(fileName))
        }
        return buildString {
            append("---\n")
            // El id evita duplicados si se vuelve a importar la misma carpeta.
            append("id: ").append(note.id).append('\n')
            // El título tal cual: una nota sin título vuelve sin título.
            append("title: ").append(note.title.replace("\n", " ")).append('\n')
            if (note.pinned) append("pinned: true\n")
            note.notebook?.let { append("notebook: ").append(it).append('\n') }
            iconValue?.let { append("icon: ").append(it).append('\n') }
            coverValue?.let {
                append("cover: ").append(it).append('\n')
                if (note.coverX != 0.5f || note.coverY != 0.5f || note.coverScale != 1f) {
                    append("coverCrop: ").append(coverCropText(note.coverX, note.coverY, note.coverScale)).append('\n')
                }
            }
            if (note.tags.isNotEmpty()) {
                append("tags: ").append(note.tags.joinToString(", ")).append('\n')
            }
            bookmarksToFrontMatter(note.bookmarks, bookmarkPaths)?.let { append("bookmarks: ").append(it).append('\n') }
            append("created: ").append(Instant.ofEpochMilli(note.createdAt)).append('\n')
            append("updated: ").append(Instant.ofEpochMilli(note.updatedAt)).append('\n')
            append("---\n\n")
            append(body)
        }
    }

    /** Escribe un archivo de texto; devuelve si se pudo. */
    private fun writeDocument(
        context: Context,
        parent: DocumentFile,
        name: String,
        mime: String,
        text: String
    ): Boolean {
        val doc = parent.findFile(name) ?: parent.createFile(mime, name) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("sin salida")
        }.isSuccess
    }

    /** Copia un archivo; devuelve el nombre con el que quedó, o null si falló. */
    private fun copyToDocument(
        context: Context,
        parent: DocumentFile,
        name: String,
        mime: String,
        file: File
    ): String? {
        val doc = parent.findFile(name) ?: parent.createFile(mime, name) ?: return null
        val ok = runCatching {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
            } ?: error("sin salida")
        }.isSuccess
        return if (ok) doc.name ?: name else null
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
        return clean.take(80).ifBlank { "archivo" }
    }

    private fun guessMime(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
