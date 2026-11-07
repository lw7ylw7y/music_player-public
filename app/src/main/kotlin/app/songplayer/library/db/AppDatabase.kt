package app.songplayer.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * `app.db`: everything the app can rebuild (Technical Design 6). It is not backed up. A version with a migration (4 to 5,
 * for the error log, and 5 to 6, for the song details) keeps its data; any other older version is dropped and filled again by the next library sync.
 */
@Database(
    entities = [
        SongEntity::class, PlayFailureEntity::class, SettingEntity::class, ResumeEntity::class, QueueItemEntity::class,
        ErrorLogEntity::class, DetailsStateEntity::class, DetailsChangeEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    abstract fun settingDao(): SettingDao

    abstract fun resumeDao(): ResumeDao

    abstract fun errorLogDao(): ErrorLogDao

    abstract fun detailsDao(): DetailsDao

    companion object {
        const val NAME = "app.db"

        /** Version 5 adds the error log (D3); everything else is kept. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `error_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`code` TEXT NOT NULL, `atEpochSec` INTEGER NOT NULL, `numbers` TEXT NOT NULL)",
                )
            }
        }

        /** Version 6 adds the song details: the status of each song and the changes made to titles, artists and albums (H3); everything else is kept. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `details_state` (`songKey` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`checkedAt` INTEGER NOT NULL, `candidates` TEXT, PRIMARY KEY(`songKey`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `details_change` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batch` INTEGER NOT NULL, " +
                        "`songKey` TEXT NOT NULL, `field` TEXT NOT NULL, `previous` TEXT, `newValue` TEXT, `source` TEXT NOT NULL, " +
                        "`atEpochSec` INTEGER NOT NULL, `writeState` INTEGER NOT NULL, `undone` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_details_change_songKey` ON `details_change` (`songKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_details_change_batch` ON `details_change` (`batch`)")
            }
        }

        /**
         * Opens `app.db` now, so a first start (or the version bump that drops and recreates the tables) is paid for
         * off the main thread. A damaged file needs no code of ours: Android deletes it and creates it again empty,
         * the library is read again and the saved queue is lost, which is acceptable because everything in it can be
         * rebuilt (Technical Design 10). Any other failure (a full disk, a lock) leaves the file alone and reaches the
         * caller, so the settings and marks in it are not lost without cause.
         */
        fun open(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                // Older versions (and any later bump without a migration) are dropped and rebuilt, as decided.
                .fallbackToDestructiveMigration(true)
                .build()
                .also { it.openHelper.writableDatabase }
    }
}
