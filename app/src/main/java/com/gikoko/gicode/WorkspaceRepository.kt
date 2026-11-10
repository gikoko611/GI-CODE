package com.gikoko.gicode

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class WorkspaceItem(val name: String, val uri: Uri, val isDirectory: Boolean, val size: Long)

/** SAF-only project operations; no arbitrary filesystem paths are accepted. */
class WorkspaceRepository(private val context: Context, treeUri: Uri) {
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Selected folder is no longer accessible")

    fun list(folder: DocumentFile = root): List<WorkspaceItem> =
        folder.listFiles().map { WorkspaceItem(it.name ?: "(unnamed)", it.uri, it.isDirectory, it.length()) }
            .sortedWith(compareByDescending<WorkspaceItem> { it.isDirectory }.thenBy { it.name.lowercase() })

    fun read(uri: Uri, maxBytes: Int = 1_000_000): String {
        require(maxBytes in 1..5_000_000)
        val file = DocumentFile.fromSingleUri(context, uri) ?: error("File unavailable")
        require(file.isFile && file.length() <= maxBytes)
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= maxBytes)
            String(bytes, Charsets.UTF_8)
        } ?: error("Unable to open file")
    }

    fun write(parentUri: Uri, fileName: String, content: String, confirmed: Boolean): Uri {
        require(confirmed) { "Explicit confirmation required" }
        require(fileName.isNotBlank() && !fileName.contains("/") && !fileName.contains("\\\\"))
        require(content.toByteArray(Charsets.UTF_8).size <= 2_000_000)
        val parent = DocumentFile.fromTreeUri(context, parentUri) ?: error("Folder unavailable")
        require(parent.isDirectory && parent.canWrite())
        require(parent.findFile(fileName) == null) { "Existing files are never overwritten automatically" }
        val created = parent.createFile("application/octet-stream", fileName) ?: error("Unable to create file")
        try {
            context.contentResolver.openOutputStream(created.uri, "w")?.use {
                it.write(content.toByteArray(Charsets.UTF_8)); it.flush()
            } ?: error("Unable to write file")
        } catch (e: Exception) { created.delete(); throw e }
        return created.uri
    }

    fun delete(uri: Uri, confirmed: Boolean): Boolean {
        require(confirmed) { "Explicit confirmation required" }
        val target = DocumentFile.fromSingleUri(context, uri) ?: error("Item unavailable")
        require(target.canWrite())
        return target.delete()
    }

    fun searchText(query: String, maxFiles: Int = 250): List<Pair<WorkspaceItem, Int>> {
        require(query.isNotBlank())
        val matches = mutableListOf<Pair<WorkspaceItem, Int>>()
        var visited = 0
        fun walk(folder: DocumentFile) {
            if (visited >= maxFiles) return
            folder.listFiles().forEach { file ->
                if (visited >= maxFiles) return
                if (file.isDirectory) walk(file)
                else if (file.isFile && (file.name ?: "").matches(
                    Regex(".*\\.(kt|java|xml|json|md|txt|gradle|kts|py|js|ts)$", RegexOption.IGNORE_CASE))) {
                    visited++
                    try {
                        val content = read(file.uri, 250_000)
                        val count = content.lineSequence().count { it.contains(query, ignoreCase = true) }
                        if (count > 0) matches += WorkspaceItem(file.name ?: "", file.uri, false, file.length()) to count
                    } catch (_: Exception) { /* Skip unreadable or oversized files. */ }
                }
            }
        }
        walk(root)
        return matches
    }
}
