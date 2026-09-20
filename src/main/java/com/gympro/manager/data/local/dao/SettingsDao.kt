package com.gympro.manager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gympro.manager.data.local.entities.GymSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: GymSettingsEntity)

    @Query("SELECT * FROM gym_settings WHERE id = 1 LIMIT 1")
    fun observe(): Flow<GymSettingsEntity?>

    @Query("SELECT * FROM gym_settings WHERE id = 1 LIMIT 1")
    suspend fun getOnce(): GymSettingsEntity?
}
