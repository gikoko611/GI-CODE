package com.gi.gicode

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class AgentSkill(val id: String, val title: String, val description: String, val instructions: String, val source: Uri? = null)

/** Loads built-in skills and imports user-authored SKILL.md files through SAF. */
class SkillLibrary(private val context: Context) {
    fun loadBundled(): List<AgentSkill> {
        val result = mutableListOf<AgentSkill>()
        fun visit(path: String) {
            if (result.size >= 100) return
            val children = try { context.assets.list(path).orEmpty() } catch (_: Exception) { emptyArray() }
            if (children.isEmpty()) {
                if (path.substringAfterLast('/').equals("SKILL.md", true)) {
                    try {
                        val text = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText().take(128 * 1024) }
                        parseText(text, path.substringAfterLast('/'))?.let { result.add(it) }
                    } catch (_: Exception) { }
                }
            } else {
                children.forEach { child -> visit("$path/$child") }
            }
        }
        visit("skills")
        return result
    }

    fun loadFromFolder(treeUri: Uri, maxSkills: Int = 100): List<AgentSkill> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<AgentSkill>()
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < maxSkills) {
            val folder = queue.removeFirst()
            folder.listFiles().forEach { item ->
                if (item.isDirectory && queue.size < maxSkills) queue.add(item)
                else if (item.isFile && item.name.equals("SKILL.md", true) && result.size < maxSkills) {
                    parse(item)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    private fun parse(file: DocumentFile): AgentSkill? = try {
        val text = context.contentResolver.openInputStream(file.uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < 128 * 1024) {
                val count = input.read(buffer, 0, minOf(buffer.size, 128 * 1024 - total))
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
            String(output.toByteArray(), Charsets.UTF_8)
        } ?: return null
        parseText(text, file.name ?: "Imported skill")
    } catch (_: Exception) { null }

    private fun parseText(text: String, fallback: String): AgentSkill? {
        if (text.isBlank()) return null
        val title = text.lines().firstOrNull { it.trim().startsWith("# ") }
            ?.trim()?.removePrefix("# ") ?: fallback
        val description = text.lines().firstOrNull { it.trim().startsWith("description:", true) }
            ?.substringAfter(":")?.trim() ?: "User imported skill"
        return AgentSkill(
            title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64),
            title.take(100), description.take(240), text
        )
    }
}
