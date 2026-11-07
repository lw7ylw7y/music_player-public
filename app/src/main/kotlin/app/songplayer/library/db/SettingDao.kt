package app.songplayer.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SettingDao {
    @Query("SELECT value FROM setting WHERE settingKey = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(setting: SettingEntity)
}
