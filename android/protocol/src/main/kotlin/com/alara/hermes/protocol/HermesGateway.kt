package com.alara.hermes.protocol

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An open conversation bound to the gateway. All mutation goes through the
 * backend; [timeline] is the single source the UI renders.
 */
interface SessionHandle {
    val sessionKey: String
    val profileId: String?
    val timeline: StateFlow<TimelineState>
    val config: StateFlow<SessionConfig>

    /**
     * Submit a prompt with optional attachments. The suspend returns on
     * gateway ack; completion arrives through [timeline]. Implementations
     * must never auto-resubmit an ambiguous send — callers re-sync and let
     * the user retry.
     */
    suspend fun send(text: String, attachments: List<OutgoingAttachment> = emptyList())
    suspend fun interrupt()
    suspend fun respondApproval(entryId: EntryId, choice: String)
    suspend fun setModel(model: String, provider: String?)
    suspend fun setReasoning(level: String)
    suspend fun setFastMode(enabled: Boolean)

    /** Re-fetch authoritative state (history + running flag) from the backend. */
    suspend fun refresh()
    fun close()
}

/**
 * The Hermes compatibility layer. One implementation per protocol generation;
 * the UI depends only on this interface so protocol churn stays inside the
 * protocol module.
 */
interface HermesGateway {
    val connection: StateFlow<ConnectionState>

    /** Capabilities of the connected surface; the UI gates on these. */
    val features: GatewayFeatures

    /** Fires when session metadata likely changed (titles, running, new turns). */
    val sessionsChanged: SharedFlow<Unit>

    /** Coarse turn lifecycle for notifications (completed / failed / approval). */
    val turnEvents: SharedFlow<TurnEvent>

    suspend fun connect()
    fun disconnect()

    /** One-shot connectivity + auth probe for the setup screen. */
    suspend fun testConnection(): Result<String>

    suspend fun listProfiles(): List<HermesProfile>
    suspend fun listSessions(profileId: String?): List<SessionSummary>
    suspend fun searchSessions(query: String, profileId: String?): List<SessionSummary>
    suspend fun listModels(sessionKey: String?): List<ModelOption>

    /** Open (resume) an existing stored session, or create a new one when [sessionKey] is null. */
    suspend fun openSession(sessionKey: String?, profileId: String?): SessionHandle

    suspend fun renameSession(sessionKey: String, title: String)
    suspend fun deleteSession(sessionKey: String)

    /** Durable per-session flags shared with the desktop sidebar. */
    suspend fun setPinned(sessionKey: String, pinned: Boolean)
    suspend fun setArchived(sessionKey: String, archived: Boolean)

    /** Installed skills, read-only. */
    suspend fun listSkills(): List<SkillInfo>

    /** Cron automations with lifecycle control. */
    suspend fun listAutomations(): List<AutomationInfo>
    suspend fun setAutomationPaused(id: String, paused: Boolean)
    suspend fun runAutomation(id: String)
    suspend fun deleteAutomation(id: String)

    /**
     * Provider allowance windows (Codex/Claude limits, credits), as collected
     * by the server's account-usage machinery. Throws when the deployment has
     * no usage endpoint yet.
     */
    suspend fun accountUsage(): List<AccountUsage>

    /** Token/cost totals aggregated from the stored session rows. */
    suspend fun usageSummary(profileId: String?): UsageSummary
}
