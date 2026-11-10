package com.gikoko.gicode

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteSkill(
    val name: String,
    val description: String,
    val source: String,
    val license: String,
    val safety: String,
    val rawUrl: String
)

/** Public SkillMD registry client. It downloads text only; skill scripts are never executed. */
class SkillRegistryClient {
    fun search(query: String, limit: Int = 10): List<RemoteSkill> {
        require(query.trim().length >= 2) { "Enter at least 2 characters" }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = get("https://api.skillmd.com/v1/search?q=$encoded&limit=${limit.coerceIn(1, 20)}")
        val root = JSONObject(json)
        val items = root.optJSONArray("results")
            ?: root.optJSONArray("skills")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("items")
            ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val raw = item.optString("raw_url").ifBlank {
                item.optString("rawUrl").ifBlank { item.optString("skill_md_url") }
            }
            if (!raw.startsWith("https://")) return@mapNotNull null
            val safetyObj = item.optJSONObject("safety") ?: item.optJSONObject("security")
            val safety = safetyObj?.toString() ?: item.optString("safety_verdict", "Not specified")
            RemoteSkill(
                name = item.optString("name").ifBlank { item.optString("title", "Unnamed skill") }.take(100),
                description = item.optString("description", "No description provided").take(500),
                source = item.optString("source").ifBlank { item.optString("owner", "Community") }.take(120),
                license = item.optString("license", "Unknown").take(80),
                safety = safety.take(240),
                rawUrl = raw
            )
        }
    }

    fun fetchSkill(rawUrl: String): String {
        require(rawUrl.startsWith("https://")) { "Only HTTPS skill downloads are allowed" }
        val content = get(rawUrl)
        require(content.isNotBlank() && content.length <= 128 * 1024) { "Skill file is empty or too large" }
        require(content.contains("SKILL", true) || content.contains("---")) { "Downloaded content is not a valid SKILL.md" }
        return content
    }

    private fun get(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json, text/markdown, text/plain")
            setRequestProperty("User-Agent", "GIcode-Android-Agent/0.0.1")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            stream.use { input ->
                while (total <= 128 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 128 * 1024 + 1 - total))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    total += count
                }
            }
            require(total <= 128 * 1024) { "Response exceeds 128 KB" }
            val body = String(output.toByteArray(), Charsets.UTF_8)
            if (code !in 200..299) error("Registry HTTP $code: ${body.take(180)}")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
