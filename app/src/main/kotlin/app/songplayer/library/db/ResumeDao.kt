package app.songplayer.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ResumeDao {
    @Query("SELECT * FROM queue_item ORDER BY position")
    fun queue(): List<QueueItemEntity>

    @Query("SELECT * FROM resume WHERE id = 1")
    fun resume(): ResumeEntity?

    @Query("DELETE FROM queue_item")
    fun clearQueue()

    @Query("DELETE FROM resume")
    fun clearResume()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putQueue(items: List<QueueItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putResume(resume: ResumeEntity)

    @Query("SELECT songKey FROM queue_item WHERE position = :position")
    fun keyAt(position: Int): String?

    @Query("UPDATE resume SET currentIndex = :index, currentKey = :key, positionMs = :positionMs WHERE id = 1")
    fun updatePosition(index: Int, key: String, positionMs: Long)
}
