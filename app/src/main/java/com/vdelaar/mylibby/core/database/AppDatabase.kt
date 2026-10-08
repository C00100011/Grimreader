package com.vdelaar.mylibby.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        FavouriteEntity::class,
        DownloadEntity::class,
        ProgressEntity::class,
        AnnotationEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        SectionStatEntity::class,
        BookTextStatsEntity::class,
        OutboxEntity::class,
        LocalBookEntity::class,
        SwipeCardEntity::class,
        WantedEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun favourites(): FavouriteDao
    abstract fun downloads(): DownloadDao
    abstract fun progress(): ProgressDao
    abstract fun annotations(): AnnotationDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun sessions(): SessionDao
    abstract fun sectionStats(): SectionStatDao
    abstract fun textStats(): TextStatsDao
    abstract fun outbox(): OutboxDao
    abstract fun localBooks(): LocalBookDao
    abstract fun swipe(): SwipeDao
    abstract fun wanted(): WantedDao

    companion object {
        /** v2: books imported from the device. Keeps all existing data. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_books` (`id` INTEGER NOT NULL, `title` TEXT NOT NULL, `authors` TEXT NOT NULL, " +
                        "`language` TEXT, `fileType` TEXT NOT NULL, `filePath` TEXT NOT NULL, `coverPath` TEXT, `sizeBytes` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, `readStatus` TEXT, `readProgress` REAL, `lastReadTime` TEXT, PRIMARY KEY(`id`))"
                )
            }
        }

        /** v3: your own star rating per book. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `personalRating` INTEGER")
            }
        }

        /** v4: the weekly swipe deck and the "want to read / want to buy" lists. Keeps all existing data. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `swipe_cards` (`week` TEXT NOT NULL, `key` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`authors` TEXT NOT NULL, `year` INTEGER, `coverId` INTEGER, `isbn` TEXT, `languages` TEXT NOT NULL, `subjects` TEXT NOT NULL, " +
                        "`decision` TEXT, `decidedAt` INTEGER, PRIMARY KEY(`week`, `key`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wanted` (`key` TEXT NOT NULL, `title` TEXT NOT NULL, `authors` TEXT NOT NULL, `year` INTEGER, `coverId` INTEGER, " +
                        "`isbn` TEXT, `languages` TEXT NOT NULL, `subjects` TEXT NOT NULL, `list` TEXT NOT NULL, `love` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mylibby.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
