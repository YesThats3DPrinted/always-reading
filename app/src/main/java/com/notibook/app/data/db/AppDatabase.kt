package com.notibook.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, SentenceEntity::class],
    version = 2,
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

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notibook.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
            }
    }
}
