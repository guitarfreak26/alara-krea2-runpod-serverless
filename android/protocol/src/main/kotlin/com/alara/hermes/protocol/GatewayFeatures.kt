package com.alara.hermes.protocol

/**
 * What the connected Hermes surface actually supports. The UI hides or gates
 * anything the backend can't do rather than faking it.
 */
data class GatewayFeatures(
    /** Multiple profiles discoverable/selectable. */
    val profiles: Boolean,
    /** Session rename supported. */
    val rename: Boolean,
    /** Durable pinned/archived flags supported (recent hermes-agent builds). */
    val sessionFlags: Boolean = false,
    /** Server can filter session lists by archived state (`archived=exclude|only|include`). */
    val archivedListing: Boolean = false,
    /** Per-session reasoning level + fast mode (config.set). */
    val sessionConfig: Boolean,
    /** Interactive approval/clarify prompts arrive on this surface. */
    val approvals: Boolean,
    /** Skills listing available. */
    val skills: Boolean = false,
    /** Cron automations available. */
    val automations: Boolean = false,
    /** Server-backed Bot Mode rooms (features.bot_mode_rooms). */
    val botRooms: Boolean = false,
) {
    companion object {
        /** Full dashboard gateway (`/api/ws` + REST, default port 9119). */
        val DASHBOARD = GatewayFeatures(
            profiles = true,
            rename = true,
            sessionFlags = true,
            archivedListing = true,
            sessionConfig = true,
            approvals = true,
            skills = true,
            automations = true,
        )

        /** OpenAI-compatible API server (default port 8642): REST + SSE only. */
        val API_SERVER = GatewayFeatures(
            profiles = false,
            rename = false,
            sessionConfig = false,
            approvals = false,
        )
    }
}
