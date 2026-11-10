package com.gikoko.gicode

import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import java.util.UUID

private val Void = Color(0xFF050A08)
private val Panel = Color(0xFF0B1712)
private val Neon = Color(0xFF39FF88)
private val Muted = Color(0xFF8AA997)
private val White = Color(0xFFE2F8EA)

data class ChatMessage(val role: String, val text: String)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private var workspaceUri by mutableStateOf<Uri?>(null)
    private var workspaceName by mutableStateOf("No workspace selected")
    private var workspaceItems by mutableStateOf(listOf<WorkspaceItem>())

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                workspaceUri = uri
                prefs.edit().putString("workspaceUri", uri.toString()).apply()
                refreshWorkspace(uri)
            } catch (e: Exception) {
                workspaceName = "Folder permission error: ${e.message ?: "unknown"}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gicode", MODE_PRIVATE)
        val secrets = SecureSecretStore(this)
        val legacyKey = prefs.getString("apiKey", null)
        if (!legacyKey.isNullOrBlank() && secrets.get("provider.apiKey").isNullOrBlank()) {
            secrets.put("provider.apiKey", legacyKey)
            prefs.edit().remove("apiKey").apply()
        }
        prefs.getString("workspaceUri", null)?.let {
            try { workspaceUri = Uri.parse(it); refreshWorkspace(workspaceUri!!) }
            catch (_: Exception) { prefs.edit().remove("workspaceUri").apply() }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Neon, background = Void, surface = Panel,
                onPrimary = Void, onBackground = White, onSurface = White
            )) {
                GIApp(
                    prefs = prefs,
                    secrets = secrets,
                    workspaceUri = workspaceUri,
                    workspaceName = workspaceName,
                    workspaceItems = workspaceItems,
                    onChooseFolder = { openTree.launch(workspaceUri) },
                    onRefreshWorkspace = { workspaceUri?.let(::refreshWorkspace) },
                    onReadFile = { uri -> WorkspaceRepository(this, workspaceUri ?: error("Select a workspace first")).read(uri) },
                    onCreateFile = { name, body ->
                        WorkspaceRepository(this, workspaceUri ?: error("Select a workspace first"))
                            .write(workspaceUri!!, name, body, confirmed = true)
                        refreshWorkspace(workspaceUri!!)
                    },
                    onDeleteFile = { uri ->
                        WorkspaceRepository(this, workspaceUri ?: error("Select a workspace first"))
                            .delete(uri, confirmed = true)
                        refreshWorkspace(workspaceUri!!)
                    },
                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenTermux = {
                        val launch = packageManager.getLaunchIntentForPackage("com.termux")
                        if (launch != null) startActivity(launch)
                    }
                )
            }
        }
    }

    private fun refreshWorkspace(uri: Uri) {
        try {
            val root = DocumentFile.fromTreeUri(this, uri)
            workspaceName = root?.name ?: "Workspace"
            workspaceItems = WorkspaceRepository(this, uri).list()
        } catch (e: Exception) {
            workspaceName = "Workspace unavailable: ${e.message ?: "permission lost"}"
            workspaceItems = emptyList()
        }
    }
}

@Composable
private fun GIApp(
    prefs: SharedPreferences,
    secrets: SecureSecretStore,
    workspaceUri: Uri?,
    workspaceName: String,
    workspaceItems: List<WorkspaceItem>,
    onChooseFolder: () -> Unit,
    onRefreshWorkspace: () -> Unit,
    onReadFile: (Uri) -> String,
    onCreateFile: (String, String) -> Unit,
    onDeleteFile: (Uri) -> Unit,
    onAccessibility: () -> Unit,
    onOpenTermux: () -> Unit
) {
    val context = LocalContext.current
    val history = remember { ChatHistoryStore(context) }
    var page by remember { mutableStateOf("CHAT") }
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "") }
    var apiKey by remember { mutableStateOf(secrets.get("provider.apiKey") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "") }
    var modelList by remember { mutableStateOf(listOf<String>()) }
    var chatInput by remember { mutableStateOf("") }
    var conversationId by remember {
        mutableStateOf(prefs.getString("conversationId", null) ?: UUID.randomUUID().toString())
    }
    val greeting = "GIcode Agent ready. Configure an OpenAI-compatible provider in Settings.\n\nChoose ASK, PLAN, CODE, DEBUG, REVIEW or REFACTOR for a focused coding workflow. File changes require your confirmation."
    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", greeting))) }
    var status by remember { mutableStateOf("LOCAL READY • ARMv7") }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(AgentMode.ASK) }
    var historyIds by remember { mutableStateOf(emptyList<String>()) }
    var skills by remember { mutableStateOf(SkillLibrary(context).loadBundled()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(conversationId) {
        val saved = withContext(Dispatchers.IO) { history.load(conversationId) }
        if (saved.isNotEmpty()) messages = saved.map { ChatMessage(it.role, it.text) }
    }
    LaunchedEffect(Unit) {
        historyIds = withContext(Dispatchers.IO) { history.conversations() }
    }

    Column(Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFF0B2115), Void, Color(0xFF020403)))
    )) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GIcode", color = Neon, fontSize = 27.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text("AI CODING AGENT  //  v0.0.1", color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("●  ${status}", color = Muted, fontSize = 9.sp)
                Text("ARMv7 • ANDROID 10+", color = Neon, fontSize = 8.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            listOf("CHAT", "TOOLS", "PROJECT", "SKILLS", "HISTORY", "SETTINGS").forEach { tab ->
                TextButton(onClick = { page = tab }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 1.dp)) {
                    Text(tab, color = if (page == tab) Neon else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        HorizontalDivider(color = Neon.copy(alpha = .35f))
        when (page) {
            "CHAT" -> ChatScreen(
                messages, chatInput, { chatInput = it }, busy, model, mode,
                onMode = { mode = it },
                onNewChat = {
                    conversationId = UUID.randomUUID().toString()
                    prefs.edit().putString("conversationId", conversationId).apply()
                    messages = listOf(ChatMessage("assistant", "New conversation ready."))
                    page = "CHAT"
                },
                onSend = {
                    val prompt = chatInput.trim()
                    if (prompt.isNotEmpty() && !busy) {
                        val updated = messages + ChatMessage("user", prompt)
                        chatInput = ""
                        messages = updated
                        busy = true
                        status = "THINKING..."
                        scope.launch {
                            withContext(Dispatchers.IO) { history.add(conversationId, "user", prompt) }
                            val result = withContext(Dispatchers.IO) {
                                val blenderSkillContext = skills.filter { skill ->
                                    val q = prompt.lowercase()
                                    listOf("blender", "3d", "bpy", "fbx", "glb", "obj", "animation", "modeling").any { q.contains(it) } && skill.id.contains("blender")
                                }.joinToString("\n\n") { "\n[SKILL: ${it.title}]\n${it.instructions}" }
                                requestChat(baseUrl, apiKey, model, updated, mode.instruction + blenderSkillContext)
                            }
                            messages = updated + ChatMessage("assistant", result)
                            withContext(Dispatchers.IO) { history.add(conversationId, "assistant", result) }
                            historyIds = withContext(Dispatchers.IO) { history.conversations() }
                            busy = false
                            status = if (result.startsWith("GIcode error:")) "CHECK PROVIDER" else "CONNECTED"
                        }
                    }
                }
            )
            "TOOLS" -> ToolsScreen(onChooseFolder, onAccessibility, onOpenTermux)
            "PROJECT" -> ProjectScreen(
                workspaceName, workspaceUri != null, workspaceItems,
                onChooseFolder, onRefreshWorkspace, onReadFile, onCreateFile, onDeleteFile
            )
            "SKILLS" -> SkillsScreen(
                skills, workspaceUri != null,
                onImport = {
                    val uri = workspaceUri
                    if (uri != null) scope.launch {
                        status = "IMPORTING SKILLS..."
                        skills = withContext(Dispatchers.IO) { SkillLibrary(context).loadFromFolder(uri) }
                        status = "SKILLS: ${skills.size}"
                    }
                }
            )
            "HISTORY" -> HistoryScreen(
                historyIds,
                onOpen = { id ->
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { history.load(id) }
                        conversationId = id
                        prefs.edit().putString("conversationId", id).apply()
                        messages = saved.map { ChatMessage(it.role, it.text) }
                        page = "CHAT"
                    }
                },
                onDelete = { id ->
                    scope.launch {
                        withContext(Dispatchers.IO) { history.deleteConversation(id) }
                        historyIds = withContext(Dispatchers.IO) { history.conversations() }
                    }
                }
            )
            else -> SettingsScreen(
                baseUrl, { baseUrl = it }, apiKey, { apiKey = it }, model, { model = it },
                modelList, onSave = {
                    prefs.edit().putString("baseUrl", baseUrl.trim().trimEnd('/'))
                        .putString("model", model.trim()).apply()
                    if (apiKey.isBlank()) secrets.remove("provider.apiKey")
                    else secrets.put("provider.apiKey", apiKey)
                    status = "SETTINGS SAVED"
                },
                onLoadModels = {
                    scope.launch {
                        status = "LOADING MODELS..."
                        val result = withContext(Dispatchers.IO) { requestModels(baseUrl, apiKey) }
                        modelList = result.first
                        status = if (result.first.isNotEmpty()) "MODELS: ${result.first.size}" else result.second
                    }
                }
            )
        }
    }
}

@Composable
private fun ChatScreen(
    messages: List<ChatMessage>, input: String, onInput: (String) -> Unit,
    busy: Boolean, model: String, mode: AgentMode, onMode: (AgentMode) -> Unit,
    onNewChat: () -> Unit, onSend: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AGENT CONSOLE", color = Neon, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(model.ifBlank { "No model" }, color = Muted, fontSize = 9.sp)
            TextButton(onClick = onNewChat) { Text("+ NEW", color = Neon, fontSize = 10.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AgentMode.values().forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { onMode(item) },
                    label = { Text(item.label, fontSize = 9.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Neon.copy(alpha = .18f),
                        selectedLabelColor = Neon
                    )
                )
            }
        }
        Text(mode.instruction, color = Muted, fontSize = 9.sp, maxLines = 2)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(messages) { msg ->
                val user = msg.role == "user"
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (user) Color(0xFF10291C) else Panel),
                    modifier = Modifier.fillMaxWidth().border(
                        1.dp, (if (user) Neon else Muted).copy(alpha = .25f), RoundedCornerShape(12.dp)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(11.dp)) {
                        Text(if (user) "YOU" else "GICODE", color = if (user) Neon else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(msg.text, color = White, fontSize = 13.sp, fontFamily =
                            if (msg.text.contains("fun ") || msg.text.contains("class ")) FontFamily.Monospace else FontFamily.Default)
                    }
                }
            }
            if (busy) item { Text("GIcode is thinking…", color = Neon, fontSize = 11.sp) }
        }
        OutlinedTextField(
            value = input, onValueChange = onInput, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask GIcode to plan, write or debug code…", color = Muted) },
            minLines = 2, maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Neon, cursorColor = Neon)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("Provider requests may use paid API credits", color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
            Button(
                onClick = onSend, enabled = !busy && input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Neon)
            ) { Text(if (busy) "WAIT" else "SEND ➤", color = Void, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ToolsScreen(onChooseFolder: () -> Unit, onAccessibility: () -> Unit, onOpenTermux: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("AGENT TOOLBOX", color = Neon, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        ToolCard("FILE WORKSPACE", "Select a project folder. Read, create and delete files with confirmation.", "SELECT FOLDER", onChooseFolder)
        ToolCard("TERMINAL BRIDGE", "Launch Termux for local shell commands. Commands are not silently executed by GIcode.", "OPEN TERMUX", onOpenTermux)
        ToolCard("ACCESSIBILITY BRIDGE", "Optional Android UI access. Enable manually in system settings; automation is not active in this build.", "ACCESSIBILITY SETTINGS", onAccessibility)
        Text("AGENT TOOL REGISTRY", color = Neon, fontWeight = FontWeight.Bold)
        val universalTools = remember { UniversalToolSystem.createDefault().definitions() }
        universalTools.forEach { tool ->
            val risk = tool.risk.name
            val state = if (tool.category == ToolCategory.WORKSPACE) "REGISTERED • LOCAL SAF" else "REGISTERED • RUNTIME PENDING"
            Text("${tool.id}  •  $risk\\n${tool.description}\\n$state", color = White, fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(10.dp))
        }
        Text("Guarded Tool Calling dispatcher is included. Termux execution, GitHub write, MCP and FFmpeg require runtime adapters before they can execute.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ProjectScreen(
    workspaceName: String, selected: Boolean, files: List<WorkspaceItem>,
    onChooseFolder: () -> Unit, onRefresh: () -> Unit, onRead: (Uri) -> String,
    onCreate: (String, String) -> Unit, onDelete: (Uri) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf("") }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorkspaceItem?>(null) }
    var newName by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("PROJECT WORKSPACE", color = Neon, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onChooseFolder, modifier = Modifier.weight(1f)) { Text("SELECT FOLDER", fontSize = 10.sp) }
            OutlinedButton(onClick = onRefresh, enabled = selected) { Text("REFRESH", fontSize = 10.sp) }
            OutlinedButton(onClick = { createOpen = true }, enabled = selected) { Text("+ FILE", fontSize = 10.sp) }
        }
        Text(workspaceName, color = Muted, fontSize = 10.sp)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter root files by name") }, singleLine = true)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(files.filter { query.isBlank() || it.name.contains(query, true) }) { file ->
                Row(
                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text((if (file.isDirectory) "📁 " else "📄 ") + file.name, color = White, fontSize = 11.sp)
                        Text(if (file.isDirectory) "Folder" else "${file.size} bytes", color = Muted, fontSize = 9.sp)
                    }
                    if (!file.isDirectory) TextButton(onClick = {
                        scope.launch {
                            try {
                                preview = withContext(Dispatchers.IO) { onRead(file.uri) }
                                previewTitle = file.name
                                error = ""
                            } catch (e: Exception) { error = e.message ?: "Unable to read file" }
                        }
                    }) { Text("READ", color = Neon, fontSize = 9.sp) }
                    TextButton(onClick = { deleteTarget = file }) { Text("DELETE", color = Color(0xFFFF7777), fontSize = 9.sp) }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF7777), fontSize = 10.sp)
    }

    if (createOpen) AlertDialog(
        onDismissRequest = { createOpen = false },
        title = { Text("Create project file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("File name") }, singleLine = true)
                OutlinedTextField(newBody, { newBody = it }, label = { Text("File content") }, minLines = 4)
                Text("Existing files will not be overwritten.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { onCreate(newName.trim(), newBody); createOpen = false; newName = ""; newBody = ""; error = "" }
                catch (e: Exception) { error = e.message ?: "Create failed" }
            }) { Text("CREATE", color = Neon) }
        },
        dismissButton = { TextButton(onClick = { createOpen = false }) { Text("CANCEL") } }
    )
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This action cannot be undone. Confirm only if this is the correct file or folder.") },
            confirmButton = {
                TextButton(onClick = {
                    try { onDelete(target.uri); error = "" }
                    catch (e: Exception) { error = e.message ?: "Delete failed" }
                    deleteTarget = null
                }) { Text("DELETE", color = Color(0xFFFF7777)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("CANCEL") } }
        )
    }
    previewTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { previewTitle = null },
            title = { Text(title) },
            text = { Text(preview.take(12000), color = White, fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            confirmButton = { TextButton(onClick = { previewTitle = null }) { Text("CLOSE", color = Neon) } }
        )
    }
}

@Composable
private fun SkillsScreen(skills: List<AgentSkill>, canImport: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SKILL LIBRARY", color = Neon, fontWeight = FontWeight.Bold)
        Text("Import user-provided SKILL.md files from the selected workspace folder.", color = Muted, fontSize = 10.sp)
        Button(onClick = onImport, enabled = canImport, colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
            Text("IMPORT SKILL.MD FROM WORKSPACE", color = Void, fontSize = 10.sp)
        }
        if (skills.isEmpty()) Text("No imported skills yet. Select a project folder, then import.", color = Muted)
        skills.forEach { skill ->
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(9.dp)).padding(11.dp)) {
                Text(skill.title, color = Neon, fontWeight = FontWeight.Bold)
                Text(skill.description, color = Muted, fontSize = 10.sp)
                Text("ID: ${skill.id}  •  ${skill.instructions.length} chars", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun HistoryScreen(ids: List<String>, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("LOCAL CHAT HISTORY", color = Neon, fontWeight = FontWeight.Bold)
        Text("Saved on this device in SQLite. Delete removes only the selected conversation.", color = Muted, fontSize = 10.sp)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(ids) { id ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(id.take(8) + "…", color = White, modifier = Modifier.weight(1f), fontSize = 11.sp)
                    TextButton(onClick = { onOpen(id) }) { Text("OPEN", color = Neon, fontSize = 9.sp) }
                    TextButton(onClick = { onDelete(id) }) { Text("DELETE", color = Color(0xFFFF7777), fontSize = 9.sp) }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    baseUrl: String, onBaseUrl: (String) -> Unit, apiKey: String, onApiKey: (String) -> Unit,
    model: String, onModel: (String) -> Unit, models: List<String>,
    onSave: () -> Unit, onLoadModels: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("AI PROVIDER SETTINGS", color = Neon, fontWeight = FontWeight.Bold)
        OutlinedTextField(baseUrl, onBaseUrl, label = { Text("OpenAI-compatible Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(apiKey, onApiKey, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(model, onModel, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
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
        Text("API keys are encrypted with Android Keystore AES-GCM. Requests go to the Base URL you configure. Model pricing and free access depend on your provider.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ToolCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Neon.copy(alpha = .25f), RoundedCornerShape(13.dp))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Neon, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(detail, color = Muted, fontSize = 10.sp)
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
        if (code !in 200..299) Pair(emptyList(), "HTTP $code • ${body.take(180)}")
        else {
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val list = (0 until data.length()).mapNotNull {
                data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank)
            }.distinct().sorted()
            Pair(list, if (list.isEmpty()) "NO MODELS FOUND" else "OK")
        }
    } catch (e: Exception) { Pair(emptyList(), "NETWORK ERROR: ${e.message ?: "unknown"}") }
}

private fun requestChat(
    base: String, key: String, model: String, messages: List<ChatMessage>, modeInstruction: String
): String {
    if (base.isBlank() || key.isBlank() || model.isBlank())
        return "GIcode error: Open Settings and configure Base URL, API Key and Model."
    return try {
        val payload = JSONObject().put("model", model).put("messages", JSONArray().apply {
            put(JSONObject().put("role", "system").put(
                "content",
                "You are GIcode, a careful Android coding agent. Current mode: ${modeInstruction}. " +
                    "Give actionable steps, complete code when requested, and never claim to have edited or executed files unless a tool actually did so. " +
                    "Ask for confirmation before destructive actions. Keep device constraints in mind."
            ))
            messages.takeLast(30).forEach {
                put(JSONObject().put("role", it.role).put("content", it.text))
            }
        }).put("temperature", 0.35)
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
        else JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.takeIf { it.isNotBlank() }
            ?: "GIcode error: Empty response from provider."
    } catch (e: Exception) { "GIcode error: ${e.message ?: "Network request failed"}" }
}
