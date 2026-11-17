package com.noted.app.data

import java.util.UUID

data class Attachment(
    val id: String,
    val noteId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val storedName: String,
    val createdAt: Long
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")
    val isAudio: Boolean get() = mime.startsWith("audio/")
    val isPdf: Boolean get() = mime.equals("application/pdf", ignoreCase = true)
}

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val imageFile: String? = null,
    val siteName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Ícono del sitio, archivo dentro de la carpeta de tarjetas (`<id>_icon.<ext>`). */
    val faviconFile: String? = null
)

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    /** HTML del cuerpo (HTML propio). */
    val body: String = "",
    val notebook: String? = null,
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val trashed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortOrder: Long = 0L,
    val attachments: List<Attachment> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    /**
     * Ícono de la lista: "builtin:<clave>" para uno incluido en la app o
     * "file:<nombre>" para un PNG/SVG copiado a la carpeta de íconos.
     */
    val icon: String? = null,
    /**
     * Si la nota fue a la papelera junto con la nota que la contenía, el id de esa
     * nota: al restaurarla vuelven juntas, y la papelera muestra solo la de afuera.
     */
    val trashedWith: String? = null,
    /** El cuerpo como texto, un renglón por bloque (`NoteHtml.plainText`). */
    val plainText: String = "",
    /** Las direcciones de los enlaces de párrafos y tarjetas, para buscar (`NoteHtml.linkText`). */
    val linkText: String = "",
    /** El primer renglón con texto del cuerpo, hasta 200 caracteres (`NoteHtml.summary`). */
    val summary: String = "",
    /** Si el cuerpo tiene alguna tarea sin marcar (`NoteHtml.hasTodo`). */
    val hasTodo: Boolean = false,
    /** Ids de las notas que están adentro, en orden (`NoteHtml.noteLinkBlockIds`). */
    val noteLinkIds: List<String> = emptyList(),
    /** Número de versión del cuerpo. */
    val revision: Long = 0L,
    /** El Markdown que tenía la nota antes de pasar a HTML, si lo había. */
    val legacyMarkdown: String? = null,
    /**
     * Portada: "color:#RRGGBB", "gradient:<clave>" o "file:<nombre>" (imagen
     * copiada a la carpeta de portadas). Null: sin portada.
     */
    val cover: String? = null,
    /** Encuadre de una portada de imagen: de 0 a 1, qué parte de lo que sobra queda a la izquierda / arriba. */
    val coverX: Float = 0.5f,
    val coverY: Float = 0.5f,
    /** Zoom de la portada de imagen sobre lo justo para cubrir la franja (1 = sin zoom). */
    val coverScale: Float = 1f
) {
    val displayTitle: String get() = title.ifBlank { "Sin título" }

    /** Lo que busca la lista: solo el título. */
    fun matchesSearch(query: String): Boolean = title.contains(query, ignoreCase = true)
}

/**
 * Notas que están dentro de otra (enlazadas con un bloque "nota") y no se
 * muestran sueltas en la lista. Se recorre desde las notas que no están dentro
 * de ninguna: si una nota solo está en un ciclo (A dentro de B y B dentro de A),
 * no se oculta, así nunca queda inaccesible. Las de la papelera no cuentan como
 * contenedoras: si se borra la nota de afuera, la de adentro vuelve a la lista.
 */
fun nestedNoteIds(notes: List<Note>): Set<String> {
    val active = notes.filterNot { it.trashed }
    val activeIds = active.map { it.id }.toSet()
    val children = active.associate { note ->
        note.id to note.noteLinkIds.filter { it in activeIds && it != note.id }.toSet()
    }
    val referenced = children.values.flatten().toSet()
    val roots = activeIds - referenced

    val reached = HashSet<String>()
    val pending = ArrayDeque(roots)
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        children[id].orEmpty().forEach { child ->
            if (child !in roots && reached.add(child)) pending.addLast(child)
        }
    }
    return reached
}

/**
 * Las notas que se van con los cuadernos de [trashedNotebooks]: las de esos cuadernos y las que
 * están adentro de ellas, a cualquier profundidad. Una nota que además está dentro de otra que
 * se sigue viendo no se esconde: se llega a ella por esa. Las de la papelera de notas no cuentan.
 */
fun hiddenWithTrashedNotebooks(notes: List<Note>, trashedNotebooks: Set<String>): Set<String> {
    if (trashedNotebooks.isEmpty()) return emptySet()
    val active = notes.filterNot { it.trashed }
    val activeIds = active.map { it.id }.toSet()
    val children = active.associate { note ->
        note.id to note.noteLinkIds.filter { it in activeIds && it != note.id }
    }
    fun reach(start: Collection<String>): Set<String> {
        val seen = HashSet(start)
        val pending = ArrayDeque(start)
        while (pending.isNotEmpty()) {
            children[pending.removeFirst()].orEmpty().forEach { if (seen.add(it)) pending.addLast(it) }
        }
        return seen
    }
    val inTrashed = active.filter { it.notebook != null && it.notebook in trashedNotebooks }.map { it.id }
    val referenced = children.values.flatten().toSet()
    // Lo que se sigue viendo: las notas sueltas de afuera de esos cuadernos y lo que tienen adentro.
    val visibleRoots = active.filter { it.id !in referenced && it.id !in inTrashed }.map { it.id }
    val visible = reach(visibleRoots) - inTrashed.toSet()
    return reach(inTrashed) - visible
}

private val SEARCH_ATTACHMENT = Regex("^\\[([^\\]]*)]\\(noted://att/[^)]+\\)$")
private val SEARCH_NOTE = Regex("^\\[[^\\]]*]\\(noted://note/[^)]+\\)$")
private val SEARCH_BOOKMARK_MARKER = Regex("^<!--\\s*noted:bookmark\\s+\\S+\\s*-->$")
private val SEARCH_DETAILS = Regex("^</?details(\\s+open)?>$", RegexOption.IGNORE_CASE)
private val SEARCH_SUMMARY = Regex("^<summary>(.*)</summary>$", RegexOption.IGNORE_CASE)

/**
 * El texto de búsqueda del Markdown viejo. Solo lo usa el control de la
 * conversión (`ConversionCheck`); para el cuerpo en HTML está `Note.plainText`.
 *
 * El cuerpo como lo ve el usuario, para buscar: sin los renglones internos
 * (`noted://…`, marcas de enlace, `<details>`). De un adjunto queda su nombre y
 * de un desplegable su título. Las notas de adentro no cuentan: se encuentran por
 * su propio título, no por el de la nota que las contiene.
 */
fun searchableText(body: String): String =
    body.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        when {
            SEARCH_NOTE.matches(trimmed) ||
                SEARCH_BOOKMARK_MARKER.matches(trimmed) ||
                SEARCH_DETAILS.matches(trimmed) -> null
            SEARCH_ATTACHMENT.matches(trimmed) ->
                SEARCH_ATTACHMENT.matchEntire(trimmed)?.groupValues?.get(1)
            SEARCH_SUMMARY.matches(trimmed) ->
                SEARCH_SUMMARY.matchEntire(trimmed)?.groupValues?.get(1)
                    ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
            else -> line
        }
    }.joinToString("\n")

/**
 * Notas que se van a la papelera junto con [rootId]: las que están adentro (y
 * adentro de esas), salvo las que también están dentro de otra nota que sigue
 * fuera de la papelera. No incluye a [rootId].
 */
fun notesTrashedWith(notes: List<Note>, rootId: String): Set<String> {
    val active = notes.filterNot { it.trashed }
    val containers = HashMap<String, MutableSet<String>>()
    active.forEach { note ->
        note.noteLinkIds.forEach { child ->
            if (child != note.id) containers.getOrPut(child) { HashSet() }.add(note.id)
        }
    }
    val going = linkedSetOf(rootId)
    var changed = true
    while (changed) {
        changed = false
        active.forEach { note ->
            val parents = containers[note.id]
            if (note.id !in going && !parents.isNullOrEmpty() && parents.all { it in going }) {
                going.add(note.id)
                changed = true
            }
        }
    }
    return going - rootId
}

/** Ids de las notas que [note] tiene adentro (bloques "nota", de `Note.noteLinkIds`). */
fun linkedNoteIds(note: Note): Set<String> =
    note.noteLinkIds.filter { it != note.id }.toSet()

/** Nota (fuera de la papelera) que tiene adentro a [noteId], si hay alguna. */
fun containerOf(notes: List<Note>, noteId: String): Note? =
    notes.firstOrNull { note ->
        !note.trashed && note.id != noteId &&
            noteId in note.noteLinkIds
    }

/** Filtros de la barra lateral. */
sealed interface NoteFilter {
    data object All : NoteFilter
    data object Pinned : NoteFilter
    data object Trash : NoteFilter
    data class Notebook(val name: String) : NoteFilter
    data class Tag(val name: String) : NoteFilter
}

fun NoteFilter.label(): String = when (this) {
    NoteFilter.All -> "Notas"
    NoteFilter.Pinned -> "Fijadas"
    NoteFilter.Trash -> "Papelera"
    is NoteFilter.Notebook -> name
    is NoteFilter.Tag -> "#$name"
}
