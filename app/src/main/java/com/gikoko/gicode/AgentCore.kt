package com.gikoko.gicode

enum class AgentMode(val label: String, val instruction: String) {
    ASK("ASK", "Answer clearly. Explain assumptions and ask before making changes."),
    PLAN("PLAN", "Break the request into ordered, verifiable steps. Do not claim execution."),
    CODE("CODE", "Produce complete, practical code with file paths and integration notes."),
    DEBUG("DEBUG", "Diagnose from evidence. Rank likely causes and give a minimal safe fix."),
    REVIEW("REVIEW", "Review correctness, security, performance and maintainability."),
    REFACTOR("REFACTOR", "Preserve behavior while improving structure. Explain changed files.")
}
data class AgentTaskStep(val title: String, val detail: String, val requiresApproval: Boolean = false)
data class AgentTaskPlan(val goal: String, val steps: List<AgentTaskStep>)

object AgentPlanner {
    fun plan(goal: String): AgentTaskPlan {
        val lower = goal.trim().lowercase()
        val steps = when {
            listOf("delete", "remove", "overwrite", "publish", "deploy", "install").any { lower.contains(it) } ->
                listOf(
                    AgentTaskStep("Inspect scope", "Identify exact target, permissions and affected files."),
                    AgentTaskStep("Prepare change", "Create a reversible plan and show a preview."),
                    AgentTaskStep("Confirm action", "Ask the user to approve the destructive or external action.", true),
                    AgentTaskStep("Execute and verify", "Run only the approved action and report the actual result.")
                )
            listOf("fix", "error", "crash", "bug", "fail").any { lower.contains(it) } ->
                listOf(
                    AgentTaskStep("Collect evidence", "Read the error, relevant source and environment constraints."),
                    AgentTaskStep("Find root cause", "Separate confirmed facts from likely causes."),
                    AgentTaskStep("Apply minimal fix", "Change only files required to address the root cause."),
                    AgentTaskStep("Verify", "Run build/tests and inspect resulting logs.")
                )
            else -> listOf(
                AgentTaskStep("Understand", "Clarify expected behavior, inputs and constraints."),
                AgentTaskStep("Plan", "Choose the smallest complete implementation."),
                AgentTaskStep("Implement", "Make the change while preserving existing behavior."),
                AgentTaskStep("Verify", "Run relevant checks and summarize limitations.")
            )
        }
        return AgentTaskPlan(goal.trim(), steps)
    }
}
enum class ToolRisk { READ_ONLY, WRITE, DESTRUCTIVE, EXTERNAL }
data class AgentToolSpec(val id: String, val description: String, val risk: ToolRisk)
object AgentToolRegistry {
    val tools = listOf(
        AgentToolSpec("workspace.list", "List user-selected folder items", ToolRisk.READ_ONLY),
        AgentToolSpec("workspace.read", "Read an accessible project file", ToolRisk.READ_ONLY),
        AgentToolSpec("workspace.search", "Search text in accessible project files", ToolRisk.READ_ONLY),
        AgentToolSpec("workspace.write", "Write a file after preview and approval", ToolRisk.WRITE),
        AgentToolSpec("workspace.delete", "Delete a selected file or folder", ToolRisk.DESTRUCTIVE),
        AgentToolSpec("provider.chat", "Send prompt and context to configured provider", ToolRisk.EXTERNAL),
        AgentToolSpec("github.request", "Access GitHub using a configured token", ToolRisk.EXTERNAL),
        AgentToolSpec("termux.launch", "Open the installed Termux app", ToolRisk.EXTERNAL)
    )
    fun requiresConfirmation(toolId: String): Boolean =
        tools.firstOrNull { it.id == toolId }?.risk in setOf(ToolRisk.WRITE, ToolRisk.DESTRUCTIVE, ToolRisk.EXTERNAL)
}
