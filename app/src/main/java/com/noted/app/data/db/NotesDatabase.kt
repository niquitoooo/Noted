package com.noted.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, NoteHistoryEntity::class, NotebookEntity::class],
    version = 6,
    exportSchema = true
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        /** El archivo de la base, en `context.getDatabasePath`. */
        const val NAME = "noted.db"

        /** 1 → 2: tabla `notebook` para el ícono de cada cuaderno. Las notas no se tocan. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notebook` " +
                        "(`name` TEXT NOT NULL, `icon` TEXT NOT NULL, PRIMARY KEY(`name`))"
                )
            }
        }

        /** 2 → 3: el lugar de cada cuaderno en el menú. Arrancan en 0 (orden alfabético). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notebook` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 3 → 4: la portada de cada nota y su encuadre. Las notas existentes quedan sin portada. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `note` ADD COLUMN `cover` TEXT")
                db.execSQL("ALTER TABLE `note` ADD COLUMN `coverX` REAL NOT NULL DEFAULT 0.5")
                db.execSQL("ALTER TABLE `note` ADD COLUMN `coverY` REAL NOT NULL DEFAULT 0.5")
                db.execSQL("ALTER TABLE `note` ADD COLUMN `coverScale` REAL NOT NULL DEFAULT 1.0")
            }
        }

        /**
         * 4 → 5: la columna `searchKey`, para buscar en la base y no en la lista en memoria. Se
         * llena con las notas que ya están; el resto de la fila no se toca.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `note` ADD COLUMN `searchKey` TEXT NOT NULL DEFAULT ''")
                val keys = ArrayList<Pair<String, String>>()
                db.query("SELECT `id`, `title`, `text`, `links` FROM `note`").use { c ->
                    while (c.moveToNext()) {
                        keys += c.getString(0) to SearchKey.of(c.getString(1), c.getString(2), c.getString(3))
                    }
                }
                for ((id, key) in keys) {
                    db.execSQL("UPDATE `note` SET `searchKey` = ? WHERE `id` = ?", arrayOf<Any>(key, id))
                }
            }
        }

        /**
         * 5 → 6: la papelera de los cuadernos. Un cuaderno eliminado queda marcado acá; sus
         * notas no se tocan (siguen con su nombre), así se puede restaurar entero.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notebook` ADD COLUMN `trashed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Todas, en orden. */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        @Volatile
        private var instance: NotesDatabase? = null

        fun get(context: Context): NotesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, NotesDatabase::class.java, NAME
                ).addMigrations(*MIGRATIONS).build().also { instance = it }
            }

        fun inMemory(context: Context): NotesDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, NotesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
