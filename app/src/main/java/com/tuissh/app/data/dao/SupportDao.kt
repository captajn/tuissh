package com.tuissh.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tuissh.app.data.model.CipherBlobEntity
import com.tuissh.app.data.model.ForwardEntity
import com.tuissh.app.data.model.HighlightRuleEntity
import com.tuissh.app.data.model.KnownHostEntity
import com.tuissh.app.data.model.KvEntity
import com.tuissh.app.data.model.PrivateKeyEntity
import com.tuissh.app.data.model.ProxyEntity
import com.tuissh.app.data.model.RecentSessionEntity
import com.tuissh.app.data.model.ScriptEntity
import com.tuissh.app.data.model.ScriptGroupEntity
import com.tuissh.app.data.model.ScriptRunEntity
import com.tuissh.app.data.model.SftpCustomMenuEntity
import com.tuissh.app.data.model.SftpRecentLocationEntity
import com.tuissh.app.data.model.TerminalThemeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CipherBlobDao {

    @Insert
    suspend fun insert(blob: CipherBlobEntity): Long

    @Query("SELECT * FROM cipher_blobs WHERE id = :id")
    suspend fun findById(id: Long): CipherBlobEntity?

    @Query("DELETE FROM cipher_blobs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM cipher_blobs WHERE id NOT IN (SELECT cipherBlobId FROM private_keys)")
    suspend fun deleteOrphans()
}

@Dao
interface PrivateKeyDao {

    @Query("SELECT * FROM private_keys ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PrivateKeyEntity>>

    @Query("SELECT * FROM private_keys ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAll(): List<PrivateKeyEntity>

    @Query("SELECT * FROM private_keys WHERE id = :id")
    suspend fun findById(id: Long): PrivateKeyEntity?

    @Query("SELECT * FROM private_keys WHERE name = :name COLLATE NOCASE")
    suspend fun findByName(name: String): PrivateKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: PrivateKeyEntity): Long

    @Update
    suspend fun update(key: PrivateKeyEntity)

    @Delete
    suspend fun delete(key: PrivateKeyEntity)

    @Query("SELECT COUNT(*) FROM servers WHERE privateKeyId = :keyId")
    suspend fun usageCount(keyId: Long): Int
}

@Dao
interface KnownHostDao {

    @Query("SELECT * FROM known_hosts ORDER BY host ASC")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts WHERE hostKey = :hostKey LIMIT 1")
    suspend fun findByHostKey(hostKey: String): KnownHostEntity?

    @Query("SELECT * FROM known_hosts ORDER BY host ASC")
    suspend fun findAll(): List<KnownHostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: KnownHostEntity): Long

    @Query("UPDATE known_hosts SET lastSeenAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM known_hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM known_hosts")
    suspend fun clear()
}

@Dao
interface ProxyDao {

    @Query("SELECT * FROM proxies ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ProxyEntity>>

    @Query("SELECT * FROM proxies ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAll(): List<ProxyEntity>

    @Query("SELECT * FROM proxies WHERE id = :id")
    suspend fun findById(id: Long): ProxyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(proxy: ProxyEntity): Long

    @Delete
    suspend fun delete(proxy: ProxyEntity)

    @Query("DELETE FROM proxies WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ForwardDao {

    @Query("SELECT * FROM forwards WHERE serverId = :serverId ORDER BY id ASC")
    fun observeForServer(serverId: Long): Flow<List<ForwardEntity>>

    @Query("SELECT * FROM forwards ORDER BY serverId ASC, id ASC")
    suspend fun findAll(): List<ForwardEntity>

    @Query("SELECT * FROM forwards WHERE serverId = :serverId ORDER BY id ASC")
    suspend fun findByServer(serverId: Long): List<ForwardEntity>

    @Query("SELECT * FROM forwards WHERE autoStart = 1")
    suspend fun findAutoStart(): List<ForwardEntity>

    @Query("SELECT * FROM forwards WHERE id = :id")
    suspend fun findById(id: Long): ForwardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(forward: ForwardEntity): Long

    @Delete
    suspend fun delete(forward: ForwardEntity)

    @Query("DELETE FROM forwards WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM forwards WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: Long)
}

@Dao
interface ScriptDao {

    @Query("SELECT * FROM scripts ORDER BY sortIndex ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts ORDER BY sortIndex ASC, name COLLATE NOCASE ASC")
    suspend fun findAll(): List<ScriptEntity>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun findById(id: Long): ScriptEntity?

    @Query("SELECT * FROM scripts WHERE groupId IS :groupId ORDER BY sortIndex ASC")
    suspend fun findByGroup(groupId: Long?): List<ScriptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(script: ScriptEntity): Long

    @Delete
    suspend fun delete(script: ScriptEntity)

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM script_groups ORDER BY sortIndex ASC")
    fun observeGroups(): Flow<List<ScriptGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: ScriptGroupEntity): Long

    @Delete
    suspend fun deleteGroup(group: ScriptGroupEntity)

    @Insert
    suspend fun insertRun(run: ScriptRunEntity): Long

    @Query("SELECT * FROM script_run_history ORDER BY ranAt DESC LIMIT :limit")
    fun observeHistory(limit: Int = 200): Flow<List<ScriptRunEntity>>

    @Query("SELECT * FROM script_run_history WHERE scriptId = :scriptId ORDER BY ranAt DESC LIMIT :limit")
    suspend fun historyFor(scriptId: Long, limit: Int = 50): List<ScriptRunEntity>

    @Query("DELETE FROM script_run_history WHERE ranAt < :before")
    suspend fun pruneHistory(before: Long)
}

@Dao
interface ThemeDao {

    @Query("SELECT * FROM terminal_themes ORDER BY builtIn DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TerminalThemeEntity>>

    @Query("SELECT * FROM terminal_themes ORDER BY builtIn DESC, name COLLATE NOCASE ASC")
    suspend fun findAll(): List<TerminalThemeEntity>

    @Query("SELECT * FROM terminal_themes WHERE id = :id")
    suspend fun findById(id: Long): TerminalThemeEntity?

    @Query("SELECT * FROM terminal_themes WHERE name = :name COLLATE NOCASE")
    suspend fun findByName(name: String): TerminalThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(theme: TerminalThemeEntity): Long

    @Delete
    suspend fun delete(theme: TerminalThemeEntity)

    @Query("DELETE FROM terminal_themes WHERE id = :id AND builtIn = 0")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM terminal_themes")
    suspend fun count(): Int
}

@Dao
interface HighlightRuleDao {

    @Query("SELECT * FROM highlight_rules WHERE enabled = 1 ORDER BY sortIndex ASC")
    fun observeEnabled(): Flow<List<HighlightRuleEntity>>

    @Query("SELECT * FROM highlight_rules WHERE enabled = 1 ORDER BY sortIndex ASC")
    suspend fun findEnabled(): List<HighlightRuleEntity>

    @Query("SELECT * FROM highlight_rules ORDER BY sortIndex ASC")
    suspend fun findAll(): List<HighlightRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: HighlightRuleEntity): Long

    @Delete
    suspend fun delete(rule: HighlightRuleEntity)
}

@Dao
interface SessionHistoryDao {

    @Query("SELECT * FROM recent_sessions ORDER BY openedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<RecentSessionEntity>>

    @Query("SELECT * FROM recent_sessions ORDER BY openedAt DESC LIMIT :limit")
    suspend fun findRecent(limit: Int = 50): List<RecentSessionEntity>

    @Insert
    suspend fun insert(session: RecentSessionEntity): Long

    @Query("DELETE FROM recent_sessions WHERE id NOT IN (SELECT id FROM recent_sessions ORDER BY openedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int = 100)

    @Query("DELETE FROM recent_sessions")
    suspend fun clear()

    @Query("SELECT * FROM sftp_recent_locations WHERE serverId = :serverId ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun recentLocations(serverId: Long, limit: Int = 20): List<SftpRecentLocationEntity>

    @Insert
    suspend fun insertLocation(location: SftpRecentLocationEntity): Long

    @Query("DELETE FROM sftp_recent_locations WHERE serverId = :serverId AND path = :path")
    suspend fun deleteLocation(serverId: Long, path: String)

    @Query("SELECT * FROM sftp_custom_menus ORDER BY sortIndex ASC")
    fun observeCustomMenus(): Flow<List<SftpCustomMenuEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomMenu(menu: SftpCustomMenuEntity): Long

    @Delete
    suspend fun deleteCustomMenu(menu: SftpCustomMenuEntity)
}

@Dao
interface KvDao {

    @Query("SELECT value FROM kv_store WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: KvEntity)

    suspend fun put(key: String, value: String) =
        put(KvEntity(key = key, value = value, updatedAt = System.currentTimeMillis()))

    @Query("DELETE FROM kv_store WHERE key = :key")
    suspend fun remove(key: String)

    @Query("SELECT * FROM kv_store")
    suspend fun all(): List<KvEntity>
}
