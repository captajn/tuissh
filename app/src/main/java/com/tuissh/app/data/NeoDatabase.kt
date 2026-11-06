package com.tuissh.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tuissh.app.data.dao.AiConversationDao
import com.tuissh.app.data.dao.CipherBlobDao
import com.tuissh.app.data.dao.ForwardDao
import com.tuissh.app.data.dao.HighlightRuleDao
import com.tuissh.app.data.dao.KnownHostDao
import com.tuissh.app.data.dao.KvDao
import com.tuissh.app.data.dao.PrivateKeyDao
import com.tuissh.app.data.dao.ProxyDao
import com.tuissh.app.data.dao.ScriptDao
import com.tuissh.app.data.dao.ServerDao
import com.tuissh.app.data.dao.ServerGroupDao
import com.tuissh.app.data.dao.SessionHistoryDao
import com.tuissh.app.data.dao.ThemeDao
import com.tuissh.app.data.model.AiConversationEntity
import com.tuissh.app.data.model.AiMessageEntity
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
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.data.model.ServerGroupEntity
import com.tuissh.app.data.model.SftpCustomMenuEntity
import com.tuissh.app.data.model.SftpRecentLocationEntity
import com.tuissh.app.data.model.TerminalThemeEntity
import com.tuissh.app.terminal.builtin.BuiltInThemes

@Database(
    entities = [
        ServerEntity::class,
        ServerGroupEntity::class,
        PrivateKeyEntity::class,
        CipherBlobEntity::class,
        KnownHostEntity::class,
        ProxyEntity::class,
        ForwardEntity::class,
        ScriptEntity::class,
        ScriptGroupEntity::class,
        ScriptRunEntity::class,
        TerminalThemeEntity::class,
        HighlightRuleEntity::class,
        RecentSessionEntity::class,
        SftpRecentLocationEntity::class,
        SftpCustomMenuEntity::class,
        KvEntity::class,
        // Assistant history. Added in version 2; the migration below only creates the
        // two new tables, so an existing install keeps its servers and keys.
        AiConversationEntity::class,
        AiMessageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NeoDatabase : RoomDatabase() {

    abstract fun serverDao(): ServerDao
    abstract fun serverGroupDao(): ServerGroupDao
    abstract fun cipherBlobDao(): CipherBlobDao
    abstract fun privateKeyDao(): PrivateKeyDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun proxyDao(): ProxyDao
    abstract fun forwardDao(): ForwardDao
    abstract fun scriptDao(): ScriptDao
    abstract fun themeDao(): ThemeDao
    abstract fun highlightRuleDao(): HighlightRuleDao
    abstract fun sessionHistoryDao(): SessionHistoryDao
    abstract fun kvDao(): KvDao
    abstract fun aiConversationDao(): AiConversationDao

    companion object {

        private const val NAME = "neoserver.db"

        @Volatile
        private var instance: NeoDatabase? = null

        fun get(context: Context): NeoDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): NeoDatabase =
            Room.databaseBuilder(context.applicationContext, NeoDatabase::class.java, NAME)
                .addCallback(SeedCallback)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Adds the assistant history tables.
         *
         * Written by hand rather than with a destructive fallback: an existing install
         * holds the user servers, keys and known hosts, and dropping the file to gain a
         * chat log would be an unforced data loss. The SQL matches what Room generates
         * for the two entities, which is what its identity check compares against.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_conversations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                        "`providerId` INTEGER NOT NULL, `modelId` TEXT NOT NULL, " +
                        "`systemPrompt` TEXT, `selectedServerIds` TEXT NOT NULL, " +
                        "`temperatureEnabled` INTEGER NOT NULL, `temperature` REAL NOT NULL, " +
                        "`reasoningEffort` TEXT, `planMode` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`toolCallsJson` TEXT, `toolCallId` TEXT, `toolName` TEXT, " +
                        "`reasoningText` TEXT, `inputTokens` INTEGER NOT NULL, " +
                        "`outputTokens` INTEGER NOT NULL, " +
                        "`cachedTokens` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_messages_conversationId` " +
                        "ON `ai_messages` (`conversationId`)",
                )
            }
        }

        /**
         * Seeds the built-in terminal themes on first creation. The original ships
         * these as SwiftUI colour literals; here they are rows so users can clone
         * and edit them like any imported theme.
         */
        private object SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Inserted through raw SQL because DAOs are not available yet.
                for (theme in BuiltInThemes.all()) {
                    db.execSQL(
                        "INSERT INTO terminal_themes " +
                            "(name, isDark, foreground, background, cursor, selection, ansiColors, source, builtIn, createdAt) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                        arrayOf(
                            theme.name,
                            if (theme.isDark) 1 else 0,
                            theme.foreground,
                            theme.background,
                            theme.cursor,
                            theme.selection,
                            theme.ansiColors,
                            theme.source,
                            System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }
}
