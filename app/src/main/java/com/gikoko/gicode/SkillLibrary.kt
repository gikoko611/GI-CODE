package com.gikoko.gicode

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class AgentSkill(val id: String, val title: String, val description: String, val instructions: String, val source: Uri? = null)

/** Imports user-authored SKILL.md files only from a folder granted through SAF. */
class SkillLibrary(private val context: Context) {
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
        val title = text.lines().firstOrNull { it.trim().startsWith("# ") }
            ?.trim()?.removePrefix("# ") ?: (file.parentFile?.name ?: "Imported skill")
        val description = text.lines().firstOrNull { it.trim().startsWith("description:", true) }
            ?.substringAfter(":")?.trim() ?: "User imported skill"
        AgentSkill(title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64),
            title.take(100), description.take(240), text, file.uri)
    } catch (_: Exception) { null }
}
