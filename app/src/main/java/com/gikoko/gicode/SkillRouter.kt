package com.gikoko.gicode

/** Selects relevant bundled/imported skills by id, title, description and task vocabulary. */
object SkillRouter {
    private val keywordGroups = listOf(
        listOf("blender", "bpy", "3d model", "3d animation", "fbx", "glb", "obj", "rigging", "render"),
        listOf("android", "apk", "kotlin", "java", "jetpack compose", "gradle", "manifest", "anr", "r8", "camerax", "permissions"),
        listOf("website", "web app", "frontend", "backend", "react", "node", "api", "database", "sql", "html", "css"),
        listOf("refactor", "code review", "review code", "architecture", "debug", "bug", "crash", "performance", "testing", "unit test"),
        listOf("security", "owasp", "vulnerability", "threat model", "secure", "audit", "permission", "intent"),
        listOf("github", "actions", "workflow", "ci/cd", "deploy", "release", "git", "pipeline", "build"),
        listOf("agent", "tool calling", "tool call", "mcp", "function calling", "autonomous", "orchestration", "skill"),
        listOf("ui", "ux", "layout", "design system", "accessibility", "responsive", "interface", "visual design"),
        listOf("video", "ffmpeg", "subtitle", "srt", "trim", "merge clips", "timeline", "voice-over", "reels", "shorts", "audio"),
        listOf("pdf", "docx", "word document", "xlsx", "spreadsheet", "pptx", "powerpoint", "document", "report")
    )

    private val idHints = mapOf(
        "blender" to listOf("blender", "3d"),
        "android" to listOf("android", "anr", "r8", "compose", "testing", "intent", "permission", "edge-to-edge"),
        "fullstack" to listOf("frontend", "webapp", "web"),
        "senior" to listOf("code", "review", "testing", "anr", "performance"),
        "cyber" to listOf("security", "intent", "permission"),
        "github" to listOf("github", "devops"),
        "ai-agent" to listOf("agent", "mcp", "skill"),
        "ui-ux" to listOf("design", "frontend"),
        "video" to listOf("video", "media", "ffmpeg"),
        "pdf" to listOf("pdf"),
        "docx" to listOf("docx", "word"),
        "xlsx" to listOf("xlsx", "spreadsheet"),
        "pptx" to listOf("pptx", "powerpoint"),
        "mcp" to listOf("mcp", "agent")
    )

    fun select(skills: List<AgentSkill>, prompt: String, maxSkills: Int = 2): List<AgentSkill> {
        val query = prompt.lowercase()
        val ranked = skills.mapNotNull { skill ->
            val identity = "${skill.id} ${skill.title} ${skill.description}".lowercase()
            val lexical = keywordGroups.sumOf { group -> group.count { query.contains(it) && identity.contains(it) } }
            val topical = keywordGroups.maxOf { group -> group.count { query.contains(it) } }
            val idScore = idHints.entries.sumOf { (hint, terms) ->
                if (skill.id.contains(hint) || skill.title.lowercase().contains(hint)) {
                    terms.count { query.contains(it) }
                } else 0
            }
            val score = lexical * 3 + idScore + if (lexical == 0) topical.coerceAtMost(2) else 0
            if (score > 0) skill to score else null
        }.sortedWith(compareByDescending<Pair<AgentSkill, Int>> { it.second }.thenBy { it.first.id })
        return ranked.take(maxSkills).map { it.first }
    }

    fun buildContext(skills: List<AgentSkill>, prompt: String): String =
        select(skills, prompt).joinToString("\n\n") { skill ->
            val bounded = skill.instructions.take(MAX_SKILL_CHARS)
            "\n[ACTIVE SKILL: ${skill.title}]\n${bounded}"
        }

    const val MAX_SKILL_CHARS = 10_000
}
