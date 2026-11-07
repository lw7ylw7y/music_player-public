package app.songplayer.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** One row of the status counts. */
data class StatusCount(val status: String, val n: Int)

@Dao
interface DetailsDao {
    @Insert
    fun insertChanges(rows: List<DetailsChangeEntity>): List<Long>

    /** Changes the library does not show yet (not confirmed) and that are not undone: what the screens lay over the file's tags. */
    @Query("SELECT * FROM details_change WHERE writeState != 1 AND undone = 0 ORDER BY id")
    fun pending(): List<DetailsChangeEntity>

    @Query("SELECT * FROM details_change WHERE atEpochSec >= :since ORDER BY id DESC")
    fun since(since: Long): List<DetailsChangeEntity>

    @Query("SELECT MAX(batch) FROM details_change")
    fun maxBatch(): Long?

    @Query("SELECT * FROM details_change WHERE songKey = :songKey AND undone = 0 ORDER BY id")
    fun liveOf(songKey: String): List<DetailsChangeEntity>

    @Query("SELECT * FROM details_change WHERE batch = :batch ORDER BY id")
    fun batch(batch: Long): List<DetailsChangeEntity>

    @Query("UPDATE details_change SET writeState = :state WHERE id IN (:ids)")
    fun setWriteState(ids: List<Long>, state: Int)

    @Query("UPDATE details_change SET undone = 1 WHERE id IN (:ids)")
    fun markUndone(ids: List<Long>)

    @Query("DELETE FROM details_change WHERE atEpochSec < :before AND (writeState = 1 OR undone = 1)")
    fun trim(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putState(row: DetailsStateEntity)

    @Query("SELECT status FROM details_state WHERE songKey = :songKey")
    fun status(songKey: String): String?

    @Query("SELECT status, COUNT(*) AS n FROM details_state GROUP BY status")
    fun statusCounts(): List<StatusCount>

    @Query("SELECT songKey FROM details_state WHERE status = 'held' ORDER BY songKey")
    fun heldKeys(): List<String>

    @Query("SELECT songKey FROM details_state WHERE status = 'notFound' ORDER BY songKey")
    fun notFoundKeys(): List<String>

    @Query("SELECT COUNT(*) FROM details_state WHERE status = 'held'")
    fun heldCount(): Int

    @Query("SELECT * FROM details_state WHERE status != 'pending' ORDER BY songKey")
    fun decisions(): List<DetailsStateEntity>

    /** Adds the states of songs that have none; a song that has one keeps it. Returns the new row id, or -1 for each ignored row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addStatesIfAbsent(rows: List<DetailsStateEntity>): List<Long>

    @Query("SELECT songKey FROM details_state WHERE status != 'pending'")
    fun decidedKeys(): List<String>

    @Query("SELECT candidates FROM details_state WHERE songKey = :songKey AND status = 'held'")
    fun candidates(songKey: String): String?
}
