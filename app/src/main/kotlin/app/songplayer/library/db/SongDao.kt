package app.songplayer.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM song")
    fun all(): List<SongEntity>

    @Query("SELECT * FROM song WHERE songKey = :key")
    fun get(key: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(songs: List<SongEntity>)

    @Query("DELETE FROM song WHERE songKey IN (:keys)")
    fun delete(keys: List<String>)

    @Query("SELECT songKey FROM play_failure")
    fun failedKeysFlow(): Flow<List<String>>

    @Query("DELETE FROM play_failure WHERE songKey IN (:keys)")
    fun clearFailures(keys: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun markFailed(failure: PlayFailureEntity)

    @Query("SELECT songKey FROM play_failure")
    fun failedKeys(): List<String>
}
