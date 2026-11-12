package com.jot.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Una versión vieja del cuerpo, comprimida con [HistoryCodec]. */
@Entity(tableName = "note_history", indices = [Index("noteId")])
data class NoteHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: String,
    val revision: Long,
    val savedAt: Long,
    val html: ByteArray
)
