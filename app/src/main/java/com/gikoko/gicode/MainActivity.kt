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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val Void = Color(0xFF050A08)
private val Panel = Color(0xFF0B1712)
private val Neon = Color(0xFF39FF88)
private val Muted = Color(0xFF8AA997)

class MainActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var fileStatus by mutableStateOf("No workspace selected")
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedUri = uri
            fileStatus = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Neon, background = Void, surface = Panel,
                onPrimary = Void, onBackground = Color(0xFFE2F8EA), onSurface = Color(0xFFE2F8EA)
            )) {
                GIHome(
                    fileStatus = fileStatus,
                    onChooseFolder = { openTree.launch(null) },
                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenTermux = {
                        val launch = packageManager.getLaunchIntentForPackage("com.termux")
                        if (launch != null) startActivity(launch) else fileStatus = "Termux not installed"
                    }
                )
            }
        }
    }
}

@Composable
private fun GIHome(fileStatus: String, onChooseFolder: () -> Unit, onAccessibility: () -> Unit, onOpenTermux: () -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("GIcode core online. Awaiting user command...") }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1B12), Void, Color(0xFF020403))))
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GIcode", color = Neon, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text("MOBILE AGENT  //  v0.0.1", color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text("● ONLINE", color = Neon, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Neon.copy(alpha=.55f), RoundedCornerShape(18.dp))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("╔══ SYSTEM TERMINAL ══╗", color = Neon, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("ROOT: DISABLED   •   API: 29+", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(log, color = Neon, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Enter agent instruction") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Neon, cursorColor = Neon, focusedLabelColor = Neon))
                Button(onClick = { log = if (prompt.isBlank()) "Type a command first." else "Queued locally: $prompt\nAI provider setup is required to execute agent tasks."; prompt = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text("RUN COMMAND", color = Void, fontWeight = FontWeight.Bold) }
            }
        }
        Text("PHONE CONTROL MODULES", color = Neon, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        ModuleCard("01  FILE WORKSPACE", fileStatus, "SELECT FOLDER / READ-WRITE PERMISSION", onChooseFolder)
        ModuleCard("02  ACCESSIBILITY BRIDGE", "User-enabled Android UI automation", "OPEN ACCESSIBILITY SETTINGS", onAccessibility)
        ModuleCard("03  TERMUX BRIDGE", "Launch Termux for local shell workflows", "OPEN TERMUX", onOpenTermux)
        Text("SECURITY: File access is limited to the folder you select. Destructive operations require a separate confirmation in future tool flows. Accessibility is opt-in in Android Settings.", color = Muted, fontSize = 11.sp)
        Text("NEON GREEN // MOBILE-FIRST // ARMV7", color = Neon.copy(alpha=.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ModuleCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Neon.copy(alpha=.25f), RoundedCornerShape(14.dp))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = Neon, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(detail, color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Neon)) { Text(action, fontSize = 11.sp) }
        }
    }
}
