package com.tuissh.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI provider configuration, mirroring `AIServiceProviderModel` +
 * `AIServiceProviderTemplate`.
 *
 * The provider catalogue recovered from the binary:
 *   https://chatgpt.com/backend-api/codex   (OpenAI Codex / ChatGPT OAuth)
 *   https://api.openai.com/v1
 *   https://api.deepseek.com/v1
 *   https://openrouter.ai/api/v1
 *   https://api.siliconflow.cn/v1
 *   https://api.anthropic.com
 *   https://open.bigmodel.cn/api/paas/v4
 *   https://open.bigmodel.cn/api/anthropic
 */
@Entity(tableName = "ai_providers", indices = [Index(value = ["slug"], unique = true)])
data class AiProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable identifier: openai, codex, deepseek, openrouter, siliconflow, anthropic, bigmodel. */
    var slug: String,
    var displayName: String,
    var baseUrl: String,
    /** openai_chat | openai_responses | anthropic_messages | codex */
    var protocol: String,
    var apiKeyCipher: String? = null,
    /** OAuth access token blob for ChatGPT/Codex sign-in. */
    var oauthTokenCipher: String? = null,
    var oauthRefreshCipher: String? = null,
    var oauthExpiresAt: Long = 0,
    var customUserAgent: String? = null,
    var defaultModel: String? = null,
    var enabled: Boolean = true,
    /** Bumped so the UI can surface "signed in" state. */
    var lastVerifiedAt: Long = 0,
    var createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val PROTOCOL_OPENAI_CHAT = "openai_chat"
        const val PROTOCOL_OPENAI_RESPONSES = "openai_responses"
        const val PROTOCOL_ANTHROPIC = "anthropic_messages"
        const val PROTOCOL_CODEX = "codex"
        const val PROTOCOL_GEMINI = "gemini"

        /**
         * Provider presets.
         *
         * The first block is the catalogue recovered from the original iOS binary.
         * The second block is user-supplied: two Vietnamese OpenAI-compatible
         * proxies (DC AI and CHJBI). Both speak the OpenAI wire format, so they use
         * [PROTOCOL_OPENAI_CHAT] and only differ in base URL and auth header.
         */
        fun presets(): List<AiProviderEntity> = listOf(
            AiProviderEntity(
                slug = "openai",
                displayName = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                protocol = PROTOCOL_OPENAI_CHAT,
                defaultModel = "gpt-4o",
            ),
            AiProviderEntity(
                slug = "codex",
                displayName = "ChatGPT (Codex)",
                baseUrl = "https://chatgpt.com/backend-api/codex",
                protocol = PROTOCOL_CODEX,
                defaultModel = "gpt-5-codex",
            ),
            AiProviderEntity(
                slug = "anthropic",
                displayName = "Anthropic",
                baseUrl = "https://api.anthropic.com",
                protocol = PROTOCOL_ANTHROPIC,
                defaultModel = "claude-sonnet-4-5",
            ),
            AiProviderEntity(
                slug = "deepseek",
                displayName = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                protocol = PROTOCOL_OPENAI_CHAT,
                defaultModel = "deepseek-chat",
            ),
            AiProviderEntity(
                slug = "openrouter",
                displayName = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                protocol = PROTOCOL_OPENAI_CHAT,
                defaultModel = "anthropic/claude-sonnet-4.5",
            ),
            AiProviderEntity(
                slug = "siliconflow",
                displayName = "SiliconFlow",
                baseUrl = "https://api.siliconflow.cn/v1",
                protocol = PROTOCOL_OPENAI_CHAT,
                defaultModel = "deepseek-ai/DeepSeek-V3",
            ),
            AiProviderEntity(
                slug = "bigmodel",
                displayName = "Zhipu GLM",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                protocol = PROTOCOL_OPENAI_CHAT,
                defaultModel = "glm-4.6",
            ),
            AiProviderEntity(
                slug = "bigmodel-anthropic",
                displayName = "Zhipu GLM (Anthropic)",
                baseUrl = "https://open.bigmodel.cn/api/anthropic",
                protocol = PROTOCOL_ANTHROPIC,
                defaultModel = "glm-4.6",
            ),

            // --- user-supplied Vietnamese proxies ---------------------------------
            // DC AI is a multi-protocol proxy: it also serves /v1/messages
            // (Anthropic), /v1/responses and /v1beta (Gemini) on the same host.
            AiProviderEntity(
                slug = "dcai",
                displayName = "DC AI",
                baseUrl = "https://dc-ai.dabeecao.org/v1",
                protocol = PROTOCOL_OPENAI_CHAT,
                defaultModel = "gemini-2.5-flash",
            ),
            AiProviderEntity(
                slug = "dcai-anthropic",
                displayName = "DC AI (Anthropic)",
                baseUrl = "https://dc-ai.dabeecao.org/v1",
                protocol = PROTOCOL_ANTHROPIC,
                defaultModel = "claude-sonnet-4-5",
            ),
            AiProviderEntity(
                slug = "chjbi",
                displayName = "CHJBI",
                baseUrl = "https://api.chjbi.net/v1",
                protocol = PROTOCOL_OPENAI_CHAT,
            ),
        )
    }
}

/** Cached model list per provider (`AIServiceProviderModel` model refresh). */
@Entity(tableName = "ai_models", indices = [Index(value = ["providerId", "modelId"], unique = true)])
data class AiModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var providerId: Long,
    var modelId: String,
    var displayName: String? = null,
    var contextWindow: Int = 0,
    var supportsTools: Boolean = true,
    var supportsReasoning: Boolean = false,
    var fetchedAt: Long = System.currentTimeMillis(),
)

/** Chat conversation, mirroring `AIChatHistoryModel` + `AIChatSessionManager`. */
@Entity(tableName = "ai_conversations", indices = [Index("providerId"), Index("updatedAt")])
data class AiConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var title: String = "New chat",
    var providerId: Long,
    var modelId: String,
    var systemPrompt: String? = null,
    /** Comma separated server ids the conversation is scoped to. */
    var selectedServerIds: String = "",
    var temperatureEnabled: Boolean = false,
    var temperature: Float = 0.7f,
    var reasoningEffort: String? = null,
    var planMode: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
)

/** One persisted message in a conversation. */
@Entity(tableName = "ai_messages", indices = [Index("conversationId"), Index("createdAt")])
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var conversationId: Long,
    /** user | assistant | tool | system */
    var role: String,
    var content: String,
    /** Serialized tool-call array for assistant messages. */
    var toolCallsJson: String? = null,
    /** For role=tool: which call this answers. */
    var toolCallId: String? = null,
    var toolName: String? = null,
    var reasoningText: String? = null,
    var inputTokens: Int = 0,
    var outputTokens: Int = 0,
    var cachedTokens: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
        const val ROLE_SYSTEM = "system"
    }
}

/**
 * A reusable skill, mirroring `AISkillsStore`.
 *
 * On disk the original stores `SKILL.md` files with YAML frontmatter:
 *   ---
 *   name: lowercase-kebab-name
 *   description: trigger description
 *   ---
 *   <markdown body>
 */
@Entity(tableName = "ai_skills", indices = [Index(value = ["name"], unique = true)])
data class AiSkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    var description: String,
    var body: String,
    var enabled: Boolean = true,
    var builtIn: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
)

/** Browser tab owned by the AI assistant, mirroring `AIBrowserTab` / `AIBrowserSession`. */
@Entity(tableName = "ai_browser_tabs", indices = [Index("conversationId")])
data class AiBrowserTabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var conversationId: Long,
    var title: String = "New tab",
    var url: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis(),
)

/** Server stats sample, written by the monitor loop (mirrors the Swift chart store). */
@Entity(tableName = "metric_samples", indices = [Index("serverId"), Index("sampledAt")])
data class MetricSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var serverId: Long,
    var sampledAt: Long,
    var cpuPercent: Double = 0.0,
    var memTotalBytes: Long = 0,
    var memUsedBytes: Long = 0,
    var swapTotalBytes: Long = 0,
    var swapUsedBytes: Long = 0,
    var diskTotalBytes: Long = 0,
    var diskUsedBytes: Long = 0,
    var netRxBytesPerSec: Double = 0.0,
    var netTxBytesPerSec: Double = 0.0,
    var load1: Double = 0.0,
    var load5: Double = 0.0,
    var load15: Double = 0.0,
    var uptimeSeconds: Long = 0,
    var processCount: Int = 0,
    var gpuUtilPercent: Double = -1.0,
    var gpuTempC: Double = -1.0,
)

/** Per-server monitor configuration. */
@Entity(tableName = "monitor_settings")
data class MonitorSettingEntity(
    @PrimaryKey val serverId: Long,
    var enabled: Boolean = true,
    var intervalSeconds: Int = 60,
    var alertCpuAbove: Int = 90,
    var alertMemAbove: Int = 90,
    var alertDiskAbove: Int = 90,
    var notifyOnOffline: Boolean = true,
    var notifyOnOnline: Boolean = true,
)

/** Docker host snapshot metadata cached per server. */
@Entity(tableName = "docker_hosts", indices = [Index("serverId")])
data class DockerHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var serverId: Long,
    /** `docker` or `podman`. */
    var engine: String = "docker",
    var version: String? = null,
    var rootDir: String? = null,
    var containersTotal: Int = 0,
    var containersRunning: Int = 0,
    var imagesTotal: Int = 0,
    var rawJson: String? = null,
    var fetchedAt: Long = System.currentTimeMillis(),
)

/** Generic key/value store for things that do not deserve their own table. */
@Entity(tableName = "kv_store")
data class KvEntity(
    @PrimaryKey var key: String,
    var value: String,
    var updatedAt: Long = System.currentTimeMillis(),
)
