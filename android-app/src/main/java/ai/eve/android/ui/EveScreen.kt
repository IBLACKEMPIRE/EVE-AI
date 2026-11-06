package ai.eve.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ai.eve.android.avatar.PlaceholderAvatar
import java.text.DateFormat
import java.util.Date

private val Accent = Color(0xFF8E7CFF)

@Composable
fun EveTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, background = Color(0xFF0E0E14), surface = Color(0xFF16161F)),
    content = content,
)

private enum class Screen { CHAT, TASKS, SETTINGS }

@Composable
fun EveScreen(vm: EveViewModel) {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            TopBar(vm, screen) { screen = it }
            if (vm.stopEngaged) Banner("Emergency stop engaged — EVE will not run any action. Tap RESUME to continue.")
            when (screen) {
                Screen.CHAT -> ChatScreen(vm)
                Screen.TASKS -> TasksScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
        vm.pending?.let { p ->
            AlertDialog(
                onDismissRequest = { vm.answerApproval(false) },
                title = { Text("Allow EVE to do this?") },
                text = { Text("${p.request.summary}\n\n${p.request.reason} (${p.request.risk.name.lowercase()})") },
                confirmButton = { Button({ vm.answerApproval(true) }) { Text("Allow once") } },
                dismissButton = { TextButton({ vm.answerApproval(false) }) { Text("Deny") } },
            )
        }
        vm.notice?.let { msg ->
            AlertDialog(onDismissRequest = { vm.notice = null }, text = { Text(msg) },
                confirmButton = { TextButton({ vm.notice = null }) { Text("OK") } })
        }
    }
}

@Composable
private fun TopBar(vm: EveViewModel, screen: Screen, go: (Screen) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text("EVE", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(if (vm.keyConfigured) vm.modelName else "offline demo", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton({ go(Screen.CHAT) }, enabled = screen != Screen.CHAT) { Text("Chat") }
        TextButton({ go(Screen.TASKS) }, enabled = screen != Screen.TASKS) { Text("Tasks") }
        TextButton({ go(Screen.SETTINGS) }, enabled = screen != Screen.SETTINGS) { Text("Settings") }
        Button(
            onClick = vm::toggleEmergencyStop,
            colors = ButtonDefaults.buttonColors(containerColor = if (vm.stopEngaged) Color(0xFF2E7D32) else Color(0xFFC62828)),
        ) { Text(if (vm.stopEngaged) "RESUME" else "STOP") }
    }
}

@Composable
private fun Banner(text: String) =
    Text(text, Modifier.fillMaxWidth().background(Color(0xFF5D1F1F)).padding(10.dp), fontSize = 13.sp)

@Composable
private fun ChatScreen(vm: EveViewModel) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showMicRationale by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleMic() else vm.notice = "Without microphone access you can still type to EVE."
    }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.lines.size) { if (vm.lines.isNotEmpty()) listState.animateScrollToItem(vm.lines.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ vm.avatarVisible = !vm.avatarVisible }) { Text(if (vm.avatarVisible) "Hide avatar" else "Show avatar") }
        }
        if (vm.avatarVisible) PlaceholderAvatar(vm.avatar)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            items(vm.lines) { line -> Bubble(line) }
            if (vm.busy) item { Text("EVE is thinking…", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (vm.listening) Text(vm.partial.ifEmpty { "Listening…" }, Modifier.padding(horizontal = 16.dp), color = Accent)
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message EVE") }, maxLines = 4)
            Spacer(Modifier.width(6.dp))
            OutlinedButton({
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleMic()
                else showMicRationale = true
            }, enabled = !vm.busy && !vm.stopEngaged) { Text(if (vm.listening) "■" else "🎤") }
            Spacer(Modifier.width(6.dp))
            Button({ vm.send(input); input = "" }, enabled = input.isNotBlank() && !vm.busy) { Text("Send") }
        }
    }
    if (showMicRationale) AlertDialog(
        onDismissRequest = { showMicRationale = false },
        title = { Text("Microphone access") },
        text = { Text("EVE listens only while the mic button is active, to turn your speech into text. Audio is processed by your device's speech service and is not recorded by EVE.") },
        confirmButton = { Button({ showMicRationale = false; micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } },
        dismissButton = { TextButton({ showMicRationale = false }) { Text("Not now") } },
    )
}

@Composable
private fun Bubble(line: ChatLine) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            line.text,
            Modifier.widthIn(max = 300.dp)
                .background(if (line.fromUser) Accent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TasksScreen(vm: EveViewModel) {
    val tasks = remember { vm.tasks() }
    val audit = remember { vm.audit() }
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { Text("Tasks (this session)", fontWeight = FontWeight.Bold) }
        if (tasks.isEmpty()) item { Text("No tasks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks) { t ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("${t.status} · ${fmt.format(Date(t.createdAt))}", fontSize = 12.sp, color = Accent)
                    Text(t.title)
                    t.result?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text("Audit log (latest first)", fontWeight = FontWeight.Bold) }
        items(audit) { e -> Text("${fmt.format(Date(e.timestamp))}  ${e.kind}  ${e.detail}", fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun SettingsScreen(vm: EveViewModel) {
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(vm.modelName) }
    var speak by remember { mutableStateOf(vm.speakReplies) }
    var memories by remember { mutableStateOf(vm.memories()) }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("AI service", fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (vm.keyConfigured) "Anthropic API key (saved — enter to replace)" else "Anthropic API key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        item { OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speak replies aloud", Modifier.weight(1f)); Switch(speak, { speak = it })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.saveSettings(key.ifBlank { null }, model, speak); key = "" }) { Text("Save") }
                if (vm.keyConfigured) OutlinedButton({ vm.saveSettings("", model, speak) }) { Text("Remove key") }
            }
        }
        item { Text("The key is encrypted with a key held in the Android Keystore and is only sent to api.anthropic.com.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        item { HorizontalDivider(); Text("Memory (${memories.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item { Text("EVE only stores what you ask her to remember. Stored on this device only.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(memories) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.text, Modifier.weight(1f))
                TextButton({ vm.forgetMemory(m.id); memories = vm.memories() }) { Text("Delete") }
            }
        }
        if (memories.isNotEmpty()) item { OutlinedButton({ confirmClear = true }) { Text("Delete all memories") } }

        item { HorizontalDivider(); Text("Permissions", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item {
            Text(
                "• Microphone — only while the mic button is active.\n" +
                    "• Calls and SMS are never sent silently: EVE opens the dialer/SMS app and you press Call/Send.\n" +
                    "• Camera, Accessibility automation and the floating overlay are not used in this version.",
                fontSize = 13.sp,
            )
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all memories?") },
        text = { Text("This permanently deletes ${memories.size} stored items.") },
        confirmButton = { Button({ vm.clearMemories(); memories = vm.memories(); confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}
