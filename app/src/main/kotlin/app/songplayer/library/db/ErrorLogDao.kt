package app.songplayer.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ErrorLogDao {
    @Insert
    fun insert(entry: ErrorLogEntity)

    @Query("DELETE FROM error_log WHERE id NOT IN (SELECT id FROM error_log ORDER BY id DESC LIMIT :keep)")
    fun trim(keep: Int)

    @Query("SELECT * FROM error_log ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): List<ErrorLogEntity>
}
