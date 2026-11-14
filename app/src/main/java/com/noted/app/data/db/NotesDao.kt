package com.noted.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface NotesDao {
    @Query("SELECT * FROM note")
    suspend fun all(): List<NoteEntity>

    /** Las notas cuyo título, texto o enlaces contienen [key], que ya pasó por [SearchKey.fold]. */
    @Query("SELECT id FROM note WHERE instr(searchKey, :key) > 0")
    suspend fun searchIds(key: String): List<String>

    @Upsert
    suspend fun upsert(notes: List<NoteEntity>)

    @Query("DELETE FROM note WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Insert
    suspend fun insertHistory(entry: NoteHistoryEntity)

    /** Deja las [keep] versiones más nuevas de la nota. */
    @Query(
        """DELETE FROM note_history WHERE noteId = :noteId AND id NOT IN (
            SELECT id FROM note_history WHERE noteId = :noteId
            ORDER BY revision DESC, savedAt DESC, id DESC LIMIT :keep)"""
    )
    suspend fun trimHistory(noteId: String, keep: Int)

    @Query("SELECT COUNT(*) FROM note_history WHERE noteId = :noteId")
    suspend fun historyCount(noteId: String): Int

    @Query("DELETE FROM note_history WHERE noteId IN (:noteIds)")
    suspend fun deleteHistory(noteIds: List<String>)

    /**
     * Todo junto: o se guarda entero o no se guarda nada. Los borrados van en lotes de
     * [DELETE_BATCH]: SQLite no acepta más de 999 valores en un `IN`.
     */
    @Transaction
    suspend fun save(
        upserts: List<NoteEntity>,
        deletes: List<String>,
        history: List<NoteHistoryEntity>,
        keep: Int
    ) {
        deletes.chunked(DELETE_BATCH).forEach { ids ->
            delete(ids)
            deleteHistory(ids)
        }
        if (upserts.isNotEmpty()) upsert(upserts)
        history.forEach { insertHistory(it) }
        history.map { it.noteId }.distinct().forEach { trimHistory(it, keep) }
    }

    @Query("SELECT * FROM notebook")
    suspend fun notebooks(): List<NotebookEntity>

    @Upsert
    suspend fun upsertNotebooks(notebooks: List<NotebookEntity>)

    @Query("DELETE FROM notebook WHERE name IN (:names)")
    suspend fun deleteNotebooks(names: List<String>)

    @Transaction
    suspend fun saveNotebooks(upserts: List<NotebookEntity>, deletes: List<String>) {
        deletes.chunked(DELETE_BATCH).forEach { deleteNotebooks(it) }
        if (upserts.isNotEmpty()) upsertNotebooks(upserts)
    }

    companion object {
        const val DELETE_BATCH = 500
    }
}
