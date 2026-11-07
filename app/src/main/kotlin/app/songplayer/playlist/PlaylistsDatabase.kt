package app.songplayer.playlist

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

@Entity(tableName = "playlist")
data class PlaylistRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** One song of a playlist. Deleting the playlist deletes its rows; `position` is the order, dense from 0. */
@Entity(
    tableName = "playlist_entry",
    foreignKeys = [ForeignKey(entity = PlaylistRow::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "position")],
)
data class EntryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val songKey: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
)

data class CountRow(@ColumnInfo(name = "playlistId") val playlistId: Long, @ColumnInfo(name = "songCount") val songCount: Int)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY id")
    fun playlists(): List<PlaylistRow>

    @Query("SELECT playlistId, COUNT(*) AS songCount FROM playlist_entry GROUP BY playlistId")
    fun counts(): List<CountRow>

    @Query("SELECT COUNT(*) FROM playlist WHERE id = :id")
    fun exists(id: Long): Int

    @Insert
    fun insertPlaylist(row: PlaylistRow): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM playlist_entry WHERE playlistId = :playlistId")
    fun deleteEntriesOf(playlistId: Long)

    @Query("SELECT * FROM playlist_entry WHERE playlistId = :playlistId ORDER BY position")
    fun entries(playlistId: Long): List<EntryRow>

    @Query("SELECT * FROM playlist_entry ORDER BY playlistId, position")
    fun allEntries(): List<EntryRow>

    @Insert
    fun insertEntry(row: EntryRow): Long

    @Query("DELETE FROM playlist_entry WHERE id = :id")
    fun deleteEntry(id: Long)

    @Query("UPDATE playlist_entry SET position = :position WHERE id = :id")
    fun setPosition(id: Long, position: Int)

    @Query("UPDATE playlist_entry SET songKey = :songKey WHERE id = :id")
    fun setSongKey(id: Long, songKey: String)

    @Query("UPDATE playlist_entry SET sizeBytes = :sizeBytes WHERE songKey = :songKey")
    fun setSize(songKey: String, sizeBytes: Long)
}

/**
 * `playlists.db`: the only data that must survive a reinstall (Technical Design 6). It has its own version and, from
 * the first release on, a tested migration for every change; the schema is exported to `app/schemas` for that.
 */
@Database(entities = [PlaylistRow::class, EntryRow::class], version = 1, exportSchema = true)
abstract class PlaylistsDatabase : RoomDatabase() {
    abstract fun dao(): PlaylistDao

    companion object {
        const val NAME = "playlists.db"

        fun open(context: Context, name: String = NAME): PlaylistsDatabase =
            Room.databaseBuilder(context, PlaylistsDatabase::class.java, name)
                .openHelperFactory(KeepDamagedFile(FrameworkSQLiteOpenHelperFactory()))
                // Android's backup copies the main file only. With the default write-ahead log the newest changes sit in a side file
                // and would be missing from a backup; the rollback journal keeps every committed change in the main file (R-17).
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
    }
}

/**
 * Android's default reaction to a damaged database is to delete the file. For the playlists that would destroy the
 * only copy of data the user made, so it is turned off: the damaged file stays exactly as it is, the open fails, and the
 * store reports `StoreError.Corrupt` (Technical Design 10). Everything else is passed on to Room unchanged.
 */
class KeepDamagedFile(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val room = configuration.callback
        val keeping = object : SupportSQLiteOpenHelper.Callback(room.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = room.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = room.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Deliberately nothing: the file is not deleted.
            }
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(keeping)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build(),
        )
    }
}
