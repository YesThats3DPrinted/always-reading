package com.alwaysreading.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, SentenceEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun sentenceDao(): SentenceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE books ADD COLUMN readerSpineIndex INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE books ADD COLUMN readerScrollPercent REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE books ADD COLUMN notifWasActiveBeforeReader INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sentences ADD COLUMN spineItemIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sentences ADD COLUMN blockIndex INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sentences ADD COLUMN type TEXT NOT NULL DEFAULT 'SENTENCE'")
                database.execSQL("""
                    CREATE TABLE books_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        coverPath TEXT,
                        filePath TEXT NOT NULL,
                        totalSentences INTEGER NOT NULL DEFAULT 0,
                        parsedSentenceCount INTEGER NOT NULL DEFAULT 0,
                        isParsing INTEGER NOT NULL DEFAULT 1,
                        currentIndex INTEGER NOT NULL DEFAULT 0,
                        currentChapter TEXT NOT NULL DEFAULT '',
                        notificationActive INTEGER NOT NULL DEFAULT 0,
                        readerSpineIndex INTEGER NOT NULL DEFAULT 0,
                        notifWasActiveBeforeReader INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO books_new
                    SELECT id, title, author, coverPath, filePath, totalSentences,
                           parsedSentenceCount, isParsing, currentIndex, currentChapter,
                           notificationActive, readerSpineIndex, notifWasActiveBeforeReader
                    FROM books
                """.trimIndent())
                database.execSQL("DROP TABLE books")
                database.execSQL("ALTER TABLE books_new RENAME TO books")
            }
        }

        // Replace readerSpineIndex + notifWasActiveBeforeReader with readerCharOffset.
        // -1 = "use notification's currentIndex on next reader open"
        // >= 0 = exact character offset into the normalized book text
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE books_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        coverPath TEXT,
                        filePath TEXT NOT NULL,
                        totalSentences INTEGER NOT NULL DEFAULT 0,
                        parsedSentenceCount INTEGER NOT NULL DEFAULT 0,
                        isParsing INTEGER NOT NULL DEFAULT 1,
                        currentIndex INTEGER NOT NULL DEFAULT 0,
                        currentChapter TEXT NOT NULL DEFAULT '',
                        notificationActive INTEGER NOT NULL DEFAULT 0,
                        readerCharOffset INTEGER NOT NULL DEFAULT -1
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO books_new
                    SELECT id, title, author, coverPath, filePath, totalSentences,
                           parsedSentenceCount, isParsing, currentIndex, currentChapter,
                           notificationActive, -1
                    FROM books
                """.trimIndent())
                database.execSQL("DROP TABLE books")
                database.execSQL("ALTER TABLE books_new RENAME TO books")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "always_reading.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
            }
    }
}
