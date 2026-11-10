package com.gikoko.gicode

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Void = Color(0xFF050A08)
private val Panel = Color(0xFF0B1712)
private val Neon = Color(0xFF39FF88)
private val Muted = Color(0xFF8AA997)
private val White = Color(0xFFE2F8EA)

data class ChatMessage(val role: String, val text: String)

class MainActivity : ComponentActivity() {
    private var workspaceUri by mutableStateOf<Uri?>(null)
    private var workspaceName by mutableStateOf("No workspace selected")
    private var workspaceFiles by mutableStateOf(listOf<String>())
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            workspaceUri = uri
            workspaceName = DocumentFile.fromTreeUri(this, uri)?.name ?: "Workspace"
            workspaceFiles = DocumentFile.fromTreeUri(this, uri)?.listFiles()?.map { (if (it.isDirectory) "📁 " else "📄 ") + it.name } ?: emptyList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gicode", MODE_PRIVATE)
        val secrets = SecureSecretStore(this)
        val legacyKey = prefs.getString("apiKey", null)
        if (!legacyKey.isNullOrBlank() && secrets.get("provider.apiKey").isNullOrBlank()) {
            secrets.put("provider.apiKey", legacyKey)
            prefs.edit().remove("apiKey").apply()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Neon, background = Void, surface = Panel,
                onPrimary = Void, onBackground = White, onSurface = White)) {
                GIApp(
                    prefs = prefs,
                    secrets = secrets,
                    workspaceName = workspaceName,
                    workspaceFiles = workspaceFiles,
                    onChooseFolder = { openTree.launch(null) },
                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenTermux = {
                        val launch = packageManager.getLaunchIntentForPackage("com.termux")
                        if (launch != null) startActivity(launch)
                    }
                )
            }
        }
    }
}

@Composable
private fun GIApp(
    prefs: android.content.SharedPreferences,
    secrets: SecureSecretStore,
    workspaceName: String,
    workspaceFiles: List<String>,
    onChooseFolder: () -> Unit,
    onAccessibility: () -> Unit,
    onOpenTermux: () -> Unit
) {
    var page by remember { mutableStateOf("CHAT") }
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "") }
    var apiKey by remember { mutableStateOf(secrets.get("provider.apiKey") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "") }
    var modelList by remember { mutableStateOf(listOf<String>()) }
    var chatInput by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", "GIcode Agent ready. Add an OpenAI-compatible provider in Settings, then send a message.\n\nI can help plan, explain and generate code. Device actions require your explicit permission."))) }
    var status by remember { mutableStateOf("LOCAL READY • ARMv7") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B2115), Void, Color(0xFF020403))))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GIcode", color = Neon, fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text("AI CODING AGENT  //  v0.0.1", color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text("●", color = Neon, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(status, color = Muted, fontSize = 9.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("CHAT", "TOOLS", "PROJECT", "SKILLS", "SETTINGS").forEach { tab ->
                TextButton(onClick = { page = tab }, modifier = Modifier.weight(1f)) {
                    Text(tab, color = if (page == tab) Neon else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        HorizontalDivider(color = Neon.copy(alpha = .35f))
        when (page) {
            "CHAT" -> ChatScreen(messages, chatInput, { chatInput = it }, busy, model, onNewChat = {
                messages = listOf(ChatMessage("assistant", "New conversation started."))
            }, onSend = {
                val prompt = chatInput.trim()
                if (prompt.isNotEmpty() && !busy) {
                    chatInput = ""
                    messages = messages + ChatMessage("user", prompt)
                    busy = true
                    status = "THINKING..."
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { requestChat(baseUrl, apiKey, model, messages + ChatMessage("user", prompt)) }
                        messages = messages + ChatMessage("assistant", result)
                        busy = false
                        status = if (result.startsWith("GIcode error:")) "CHECK PROVIDER" else "CONNECTED"
                    }
                }
            })
            "TOOLS" -> ToolsScreen(onChooseFolder, onAccessibility, onOpenTermux)
            "PROJECT" -> ProjectScreen(workspaceName, workspaceFiles, onChooseFolder)
            "SKILLS" -> SkillsScreen()
            else -> SettingsScreen(baseUrl, { baseUrl = it }, apiKey, { apiKey = it }, model, { model = it },
                modelList, { modelList = it }, onSave = {
                    prefs.edit().putString("baseUrl", baseUrl.trim().trimEnd('/'))
                        .putString("model", model).remove("apiKey").apply()
                    if (apiKey.isBlank()) secrets.remove("provider.apiKey")
                    else secrets.put("provider.apiKey", apiKey)
                    status = "SETTINGS SAVED"
                }, onLoadModels = {
                    scope.launch {
                        status = "LOADING MODELS..."
                        val result = withContext(Dispatchers.IO) { requestModels(baseUrl, apiKey) }
                        if (result.first.isNotEmpty()) {
                            modelList = result.first
                            status = "MODELS LOADED: ${result.first.size}"
                        } else status = result.second
                    }
                })
        }
    }
}

@Composable
private fun ChatScreen(
    messages: List<ChatMessage>, input: String, onInput: (String) -> Unit, busy: Boolean, model: String,
    onNewChat: () -> Unit, onSend: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AGENT CONSOLE", color = Neon, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(model.ifBlank { "No model" }, color = Muted, fontSize = 10.sp)
            TextButton(onClick = onNewChat) { Text("+ NEW", color = Neon, fontSize = 10.sp) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages) { msg ->
                val user = msg.role == "user"
                Card(colors = CardDefaults.cardColors(containerColor = if (user) Color(0xFF10291C) else Panel),
                    modifier = Modifier.fillMaxWidth().border(1.dp, (if (user) Neon else Muted).copy(alpha = .25f), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (user) "YOU" else "GICODE", color = if (user) Neon else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(msg.text, color = White, fontSize = 13.sp, fontFamily = if (msg.text.contains("fun ") || msg.text.contains("class ")) FontFamily.Monospace else FontFamily.Default)
                    }
                }
            }
            if (busy) item { Text("GIcode is thinking…", color = Neon, fontSize = 11.sp) }
        }
        OutlinedTextField(value = input, onValueChange = onInput, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask GIcode to explain, write or fix code…", color = Muted) }, minLines = 2, maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Neon, cursorColor = Neon))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("Enter prompt • provider API usage may cost money", color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Button(onClick = onSend, enabled = !busy && input.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
                Text(if (busy) "WAIT" else "SEND ➤", color = Void, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ToolsScreen(onChooseFolder: () -> Unit, onAccessibility: () -> Unit, onOpenTermux: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AGENT TOOLBOX", color = Neon, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        ToolCard("📂 FILE WORKSPACE", "Choose a folder using Android Storage Access Framework", "SELECT FOLDER", onChooseFolder)
        ToolCard("⌨ TERMINAL BRIDGE", "Open Termux for local shell commands (Termux must be installed)", "OPEN TERMUX", onOpenTermux)
        ToolCard("📱 ACCESSIBILITY", "Optional UI inspection and gesture bridge. Enable manually in Android Settings.", "ACCESSIBILITY SETTINGS", onAccessibility)
        listOf("Code Generator • Chat-based", "Code Explainer • Chat-based", "Bug Fix Assistant • Chat-based",
            "Git / GitHub • Planned integration", "APK Build & Gradle Repair • Planned integration",
            "Diff / Patch Review • Planned integration", "Log Analyzer • Planned integration", "ZIP Import / Export • Planned integration")
            .forEach { ToolCard(it, "Tool availability depends on the connected provider and project permissions.", "COMING IN NEXT MODULE", {}) }
    }
}

@Composable
private fun ProjectScreen(workspaceName: String, files: List<String>, onChooseFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PROJECT WORKSPACE", color = Neon, fontWeight = FontWeight.Bold)
        ToolCard("CURRENT WORKSPACE", workspaceName, "CHOOSE PROJECT FOLDER", onChooseFolder)
        Text("ROOT ITEMS", color = Muted, fontSize = 10.sp)
        if (files.isEmpty()) Text("No folder selected or folder is empty.", color = Muted)
        files.forEach { Text(it, color = White, modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(12.dp)) }
        Text("Current build provides folder selection and top-level listing. File editing, patch apply and Git operations are not enabled yet.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SkillsScreen() {
    val skills = listOf("code-review", "debugging", "refactoring", "test-generator", "documentation",
        "android-apk-builder", "gradle-fixer", "armv7-compatibility", "git-manager", "github-actions",
        "security-audit", "log-analyzer", "myanmar-translation", "subtitle-generator", "3d-modeling")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SKILL LIBRARY", color = Neon, fontWeight = FontWeight.Bold)
        Text("SKILL.md compatible skill catalogue concept. Dynamic loading/import is a later implementation step.", color = Muted, fontSize = 11.sp)
        skills.forEach { Text("✦  $it", color = White, modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(12.dp)) }
    }
}

@Composable
private fun SettingsScreen(
    baseUrl: String, onBaseUrl: (String) -> Unit, apiKey: String, onApiKey: (String) -> Unit,
    model: String, onModel: (String) -> Unit, models: List<String>, onModels: (List<String>) -> Unit,
    onSave: () -> Unit, onLoadModels: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI PROVIDER SETTINGS", color = Neon, fontWeight = FontWeight.Bold)
        OutlinedTextField(baseUrl, onBaseUrl, label = { Text("OpenAI-compatible Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(apiKey, onApiKey, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(model, onModel, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text("SAVE", color = Void) }
            OutlinedButton(onClick = onLoadModels) { Text("LOAD MODELS", color = Neon) }
        }
        if (models.isNotEmpty()) {
            Text("AVAILABLE MODELS", color = Neon, fontWeight = FontWeight.Bold)
            models.take(100).forEach { item ->
                TextButton(onClick = { onModel(item) }, modifier = Modifier.fillMaxWidth()) {
                    Text(item, color = if (item == model) Neon else White)
                }
            }
        }
        Text("API keys are encrypted with Android Keystore AES-GCM. Use a trusted provider; requests are sent to the Base URL you enter. No free-only guarantee is made.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ToolCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Neon.copy(alpha = .25f), RoundedCornerShape(13.dp))) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Neon, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(detail, color = Muted, fontSize = 11.sp)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Neon)) {
                Text(action, fontSize = 10.sp)
            }
        }
    }
}

private fun requestModels(base: String, key: String): Pair<List<String>, String> {
    if (base.isBlank() || key.isBlank()) return Pair(emptyList(), "SET PROVIDER KEY")
    return try {
        val conn = (URL(base.trim().trimEnd('/') + "/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $key")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) Pair(emptyList(), "HTTP $code")
        else {
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val list = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.sorted()
            Pair(list, if (list.isEmpty()) "NO MODELS FOUND" else "OK")
        }
    } catch (e: Exception) { Pair(emptyList(), "NETWORK ERROR: ${e.message ?: "unknown"}") }
}

private fun requestChat(base: String, key: String, model: String, messages: List<ChatMessage>): String {
    if (base.isBlank() || key.isBlank() || model.isBlank()) return "GIcode error: Open Settings and configure Base URL, API Key and Model."
    return try {
        val payload = JSONObject().put("model", model).put("messages", JSONArray().apply {
            messages.takeLast(30).forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
        }).put("temperature", 0.4)
        val conn = (URL(base.trim().trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 90000; doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) "GIcode error: HTTP $code • ${body.take(500)}"
        else JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() } ?: "GIcode error: Empty response from provider."
    } catch (e: Exception) { "GIcode error: ${e.message ?: "Network request failed"}" }
}
