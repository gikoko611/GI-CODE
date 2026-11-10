package com.gikoko.gicode

/** Lightweight keyword router. Skills guide the model; they do not grant tool permissions. */
object SkillRouter {
    private val keywords = mapOf(
        "blender-3d" to listOf("blender", "bpy", "3d model", "3d animation", "fbx", "glb", "obj"),
        "android-developer" to listOf("android", "apk", "kotlin", "java", "jetpack compose", "gradle", "manifest"),
        "fullstack-developer" to listOf("website", "web app", "frontend", "backend", "react", "node", "api", "database", "sql"),
        "senior-code-engineer" to listOf("refactor", "code review", "review code", "architecture", "debug", "bug", "crash", "performance"),
        "cybersecurity" to listOf("security", "owasp", "vulnerability", "threat model", "secure", "audit"),
        "github-devops" to listOf("github", "actions", "workflow", "ci/cd", "deploy", "release", "git", "pipeline"),
        "ai-agent-engineer" to listOf("agent", "tool calling", "tool call", "mcp", "function calling", "autonomous", "orchestration"),
        "ui-ux-designer" to listOf("ui", "ux", "layout", "design system", "accessibility", "responsive", "interface"),
        "video-editing" to listOf("video", "ffmpeg", "subtitle", "srt", "trim", "merge clips", "timeline", "voice-over", "reels", "shorts")
    )

    fun select(skills: List<AgentSkill>, prompt: String, maxSkills: Int = 2): List<AgentSkill> {
        val query = prompt.lowercase()
        val ranked = skills.mapNotNull { skill ->
            val terms = keywords[skill.id] ?: emptyList()
            val score = terms.count { query.contains(it) }
            if (score > 0) skill to score else null
        }.sortedWith(compareByDescending<Pair<AgentSkill, Int>> { it.second }.thenBy { it.first.id })
        return ranked.take(maxSkills).map { it.first }
    }

    fun buildContext(skills: List<AgentSkill>, prompt: String): String =
        select(skills, prompt).joinToString("\n\n") {
            "\n[ACTIVE SKILL: ${it.title}]\n${it.instructions}"
        }
}
