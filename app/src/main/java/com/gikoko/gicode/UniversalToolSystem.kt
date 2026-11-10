package com.gikoko.gicode

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider-neutral tool execution layer.
 *
 * AI providers may propose a ToolCall, but this layer validates the tool,
 * validates arguments, enforces approval, executes a registered handler,
 * and returns a bounded result. No arbitrary reflection or shell execution.
 */
enum class ToolCategory { PHONE_LOCAL, WORKSPACE, TERMUX, ONLINE, GITHUB, MCP, MEDIA }
enum class ToolRiskLevel { READ_ONLY, WRITE, DESTRUCTIVE, EXTERNAL }

data class ToolDefinition(
    val id: String,
    val title: String,
    val description: String,
    val category: ToolCategory,
    val risk: ToolRiskLevel,
    val requiresWorkspace: Boolean = false,
    val enabled: Boolean = true
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{2,63}")))
        require(title.isNotBlank() && description.isNotBlank())
    }
}

data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val arguments: JSONObject
) {
    companion object {
        fun fromJson(json: JSONObject): ToolCall {
            val function = json.optJSONObject("function") ?: json
            val argsValue = function.opt("arguments")
            val args = when (argsValue) {
                is JSONObject -> argsValue
                is String -> JSONObject(argsValue)
                else -> JSONObject()
            }
            return ToolCall(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = function.optString("name"),
                arguments = args
            )
        }
    }
}

data class ToolExecutionContext(
    val workspaceAvailable: Boolean,
    val approvedCallIds: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap()
)

data class ToolExecutionResult(
    val callId: String,
    val toolId: String,
    val success: Boolean,
    val output: String,
    val needsApproval: Boolean = false
)

data class ToolAuditEntry(
    val callId: String,
    val toolId: String,
    val success: Boolean,
    val timestamp: Long,
    val summary: String
)

fun interface ToolHandler {
    suspend fun execute(arguments: JSONObject, context: ToolExecutionContext): String
}

class UniversalToolSystem {
    private data class RegisteredTool(val definition: ToolDefinition, val handler: ToolHandler?)
    private val tools = ConcurrentHashMap<String, RegisteredTool>()
    private val audit = ArrayDeque<ToolAuditEntry>()

    @Synchronized
    fun register(definition: ToolDefinition, handler: ToolHandler? = null) {
        require(!tools.containsKey(definition.id)) { "Tool already registered: ${definition.id}" }
        tools[definition.id] = RegisteredTool(definition, handler)
    }

    fun definitions(): List<ToolDefinition> =
        tools.values.map { it.definition }.filter { it.enabled }.sortedWith(compareBy({ it.category.name }, { it.id }))

    fun definition(id: String): ToolDefinition? = tools[id]?.definition

    suspend fun dispatch(call: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val registered = tools[call.name]
            ?: return record(ToolExecutionResult(call.id, call.name, false, "Unknown tool: ${call.name}"))
        val def = registered.definition
        if (!def.enabled) return record(ToolExecutionResult(call.id, def.id, false, "Tool is disabled"))
        if (def.requiresWorkspace && !context.workspaceAvailable) {
            return record(ToolExecutionResult(call.id, def.id, false, "Select a workspace folder first"))
        }
        if (def.risk != ToolRiskLevel.READ_ONLY && call.id !in context.approvedCallIds) {
            return record(ToolExecutionResult(call.id, def.id, false, "Approval required before execution", true))
        }
        val handler = registered.handler
            ?: return record(ToolExecutionResult(call.id, def.id, false, "Tool is registered but its runtime is not connected yet"))
        return try {
            val output = handler.execute(call.arguments, context).take(MAX_OUTPUT_CHARS)
            record(ToolExecutionResult(call.id, def.id, true, output))
        } catch (e: Exception) {
            record(ToolExecutionResult(call.id, def.id, false, e.message ?: e.javaClass.simpleName))
        }
    }

    @Synchronized
    private fun record(result: ToolExecutionResult): ToolExecutionResult {
        audit.addLast(
            ToolAuditEntry(result.callId, result.toolId, result.success, System.currentTimeMillis(),
                result.output.take(240))
        )
        while (audit.size > MAX_AUDIT_ENTRIES) audit.removeFirst()
        return result
    }

    @Synchronized
    fun recentAudit(): List<ToolAuditEntry> = audit.toList().asReversed()

    companion object {
        const val MAX_OUTPUT_CHARS = 12_000
        const val MAX_AUDIT_ENTRIES = 100

        fun createDefault(): UniversalToolSystem = UniversalToolSystem().apply {
            register(ToolDefinition("workspace.list", "List workspace", "List files in the selected project folder.", ToolCategory.WORKSPACE, ToolRiskLevel.READ_ONLY, true))
            register(ToolDefinition("workspace.read", "Read file", "Read a UTF-8 text file from the selected workspace.", ToolCategory.WORKSPACE, ToolRiskLevel.READ_ONLY, true))
            register(ToolDefinition("workspace.search", "Search project", "Search source files in the selected workspace.", ToolCategory.WORKSPACE, ToolRiskLevel.READ_ONLY, true))
            register(ToolDefinition("workspace.write", "Create file", "Create a new file; existing files are never overwritten automatically.", ToolCategory.WORKSPACE, ToolRiskLevel.WRITE, true))
            register(ToolDefinition("workspace.delete", "Delete file", "Delete a selected workspace item.", ToolCategory.WORKSPACE, ToolRiskLevel.DESTRUCTIVE, true))
            register(ToolDefinition("phone.share", "Share content", "Open Android share sheet for user-selected content.", ToolCategory.PHONE_LOCAL, ToolRiskLevel.EXTERNAL))
            register(ToolDefinition("termux.execute", "Run Termux command", "Run an allowlisted command through an explicit Termux bridge.", ToolCategory.TERMUX, ToolRiskLevel.WRITE))
            register(ToolDefinition("online.http", "Online API request", "Call a configured and approved external API.", ToolCategory.ONLINE, ToolRiskLevel.EXTERNAL))
            register(ToolDefinition("github.read", "Read GitHub", "Read repository files and metadata using configured access.", ToolCategory.GITHUB, ToolRiskLevel.EXTERNAL))
            register(ToolDefinition("github.write", "Update GitHub", "Create or update repository files after approval.", ToolCategory.GITHUB, ToolRiskLevel.EXTERNAL))
            register(ToolDefinition("mcp.call", "MCP tool call", "Invoke a user-connected MCP server tool.", ToolCategory.MCP, ToolRiskLevel.EXTERNAL))
            register(ToolDefinition("media.ffmpeg", "Media processing", "Process selected media using an available FFmpeg runtime.", ToolCategory.MEDIA, ToolRiskLevel.WRITE))
        }
    }
}
