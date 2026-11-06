package com.tuissh.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.data.model.ServerGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY favorite DESC, sortIndex ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE groupId IS :groupId ORDER BY sortIndex ASC, name COLLATE NOCASE ASC")
    fun observeByGroup(groupId: Long?): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observeById(id: Long): Flow<ServerEntity?>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun findById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<ServerEntity>

    @Query("SELECT * FROM servers ORDER BY favorite DESC, sortIndex ASC, name COLLATE NOCASE ASC")
    suspend fun findAll(): List<ServerEntity>

    @Query(
        "SELECT * FROM servers WHERE name LIKE '%' || :q || '%' " +
            "OR host LIKE '%' || :q || '%' OR username LIKE '%' || :q || '%' " +
            "OR tags LIKE '%' || :q || '%' ORDER BY name COLLATE NOCASE ASC",
    )
    suspend fun search(q: String): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<ServerEntity>): List<Long>

    @Update
    suspend fun update(server: ServerEntity)

    @Delete
    suspend fun delete(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE servers SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE servers SET offline = :offline, updatedAt = :now WHERE id = :id")
    suspend fun setOffline(id: Long, offline: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE servers SET lastConnectedAt = :at WHERE id = :id")
    suspend fun markConnected(id: Long, at: Long = System.currentTimeMillis())

    @Query("UPDATE servers SET sortIndex = :index WHERE id = :id")
    suspend fun setSortIndex(id: Long, index: Int)

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM servers WHERE groupId IS :groupId")
    suspend fun countInGroup(groupId: Long?): Int
}

@Dao
interface ServerGroupDao {

    @Query("SELECT * FROM server_groups ORDER BY sortIndex ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ServerGroupEntity>>

    @Query("SELECT * FROM server_groups ORDER BY sortIndex ASC, name COLLATE NOCASE ASC")
    suspend fun findAll(): List<ServerGroupEntity>

    @Query("SELECT * FROM server_groups WHERE name = :name COLLATE NOCASE")
    suspend fun findByName(name: String): ServerGroupEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: ServerGroupEntity): Long

    @Update
    suspend fun update(group: ServerGroupEntity)

    @Delete
    suspend fun delete(group: ServerGroupEntity)

    @Query("DELETE FROM server_groups WHERE id = :id")
    suspend fun deleteById(id: Long)
}
