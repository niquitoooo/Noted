package com.jot.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Una fila por nota. `html` es el cuerpo y `text` el texto plano derivado. */
@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val html: String,
    val text: String,
    /** Las direcciones de los enlaces de párrafos y tarjetas, para buscar. */
    val links: String,
    val summary: String,
    val hasTodo: Boolean,
    val notebook: String?,
    /** JSON, igual que en `NotesJson`. */
    val tags: String,
    val pinned: Boolean,
    val trashed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val sortOrder: Long,
    /** JSON, igual que en `NotesJson`. */
    val attachments: String,
    /** JSON, igual que en `NotesJson`. */
    val bookmarks: String,
    val icon: String?,
    val trashedWith: String?,
    /** JSON: ids de las notas de adentro. */
    val noteLinkIds: String,
    val revision: Long,
    val legacyMarkdown: String?,
    /** Portada: "color:…", "gradient:…" o "file:…". */
    val cover: String? = null,
    @ColumnInfo(defaultValue = "0.5") val coverX: Float = 0.5f,
    @ColumnInfo(defaultValue = "0.5") val coverY: Float = 0.5f,
    @ColumnInfo(defaultValue = "1.0") val coverScale: Float = 1f,
    /** Título, texto y enlaces para buscar ([SearchKey.of]). Se calcula al guardar. */
    @ColumnInfo(defaultValue = "") val searchKey: String
)
