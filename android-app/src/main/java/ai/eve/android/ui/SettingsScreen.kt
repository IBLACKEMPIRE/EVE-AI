package ai.eve.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eve.android.security.KeyState
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.RoutingMode
import ai.eve.core.providers.ModelCatalog

private val Ok = Color(0xFF66BB6A)
private val Bad = Color(0xFFEF5350)

private fun ProviderId.title() = "${vendor} ${displayName}"

@Composable
internal fun SettingsScreen(vm: EveViewModel) {
    var memories by remember { mutableStateOf(vm.memories()) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ProviderId?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Section("AI providers", "Keys are encrypted with the Android Keystore and sent only to that provider's API.") }
        items(ProviderId.entries) { id -> vm.providers[id]?.let { ui -> ProviderCard(vm, ui) { confirmDelete = id } } }

        item { Section("AI intelligence", null) }
        item { IntelligenceCard(vm) }

        item { Section("Voice", null) }
        item { SwitchRow("Speak replies aloud", null, vm.speakReplies, vm::updateSpeak) }

        item { HorizontalDivider(); Section("Memory (${memories.size})", "EVE only stores what you ask her to remember. Stored on this device only.") }
        items(memories) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.text, Modifier.weight(1f))
                TextButton({ vm.forgetMemory(m.id); memories = vm.memories() }) { Text("Delete") }
            }
        }
        if (memories.isNotEmpty()) item { OutlinedButton({ confirmClear = true }) { Text("Delete all memories") } }

        item { HorizontalDivider(); Section("Permissions", null) }
        item {
            Text(
                "• Microphone — only while the mic button is active.\n" +
                    "• Calls and SMS are never sent silently: EVE opens the dialer/SMS app and you press Call/Send.\n" +
                    "• Camera, Accessibility automation and the floating overlay are not used in this version.",
                fontSize = 13.sp,
            )
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all memories?") },
        text = { Text("This permanently deletes ${memories.size} stored items.") },
        confirmButton = { Button({ vm.clearMemories(); memories = vm.memories(); confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${id.title()} key?") },
            text = { Text("The encrypted key is removed from this phone. You can enter it again later.") },
            confirmButton = { Button({ vm.deleteKey(id); confirmDelete = null }) { Text("Delete key") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, subtitle: String?) = Column(Modifier.padding(top = 8.dp)) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ProviderCard(vm: EveViewModel, ui: ProviderUi, onDelete: () -> Unit) {
    var key by remember(ui.id) { mutableStateOf("") }
    var showKey by remember(ui.id) { mutableStateOf(false) }
    var model by remember(ui.id, ui.model) { mutableStateOf(ui.model) }
    var error by remember(ui.id) { mutableStateOf<String?>(null) }

    val err = error
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ui.id.title(), fontWeight = FontWeight.SemiBold)
                    val (label, color) = when {
                        ui.testing -> "Testing…" to MaterialTheme.colorScheme.onSurfaceVariant
                        ui.keyState == KeyState.NONE -> "No key" to MaterialTheme.colorScheme.onSurfaceVariant
                        ui.keyState == KeyState.UNREADABLE -> "Saved key can't be decrypted — enter it again" to Bad
                        ui.statusOk == true -> (ui.status ?: "Connected") to Ok
                        ui.statusOk == false -> (ui.status ?: "Failed") to Bad
                        !ui.enabled -> "Key saved · disabled" to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> "Key saved · not tested yet" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(label, fontSize = 12.sp, color = color)
                }
                Switch(ui.enabled, { vm.setProviderEnabled(ui.id, it) })
            }
            OutlinedTextField(
                key, { key = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (ui.keyState == KeyState.SAVED) "API key (saved — type a new one to replace)" else "API key") },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
            )
            OutlinedTextField(
                model, { model = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Model ID") },
                isError = err != null || !ModelCatalog.isPlausibleModelId(model),
                supportingText = if (err != null) { { Text(err) } } else null,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModelCatalog.suggestions[ui.id].orEmpty().forEach { s ->
                    TextButton({ model = s; error = null }) { Text(if (s == model) "✓ $s" else s, fontSize = 12.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    error = vm.saveProvider(ui.id, key.ifBlank { null }, model, ui.enabled)
                    if (error == null) key = ""
                }) { Text("Save") }
                OutlinedButton({ vm.testProvider(ui.id) }, enabled = !ui.testing && ui.keyState == KeyState.SAVED) { Text("Test") }
                if (ui.keyState != KeyState.NONE) TextButton(onDelete) { Text("Delete key") }
            }
        }
    }
}

@Composable
private fun IntelligenceCard(vm: EveViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mode", fontWeight = FontWeight.SemiBold)
            RadioRow("Automatic — EVE picks the best available provider", vm.mode == RoutingMode.AUTOMATIC) { vm.updateMode(RoutingMode.AUTOMATIC) }
            RadioRow("Manual — always use the preferred provider", vm.mode == RoutingMode.MANUAL) { vm.updateMode(RoutingMode.MANUAL) }

            Text("Preferred provider", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            if (vm.mode == RoutingMode.AUTOMATIC) RadioRow("Default order (Claude → GPT → Gemini)", vm.preferred == null) { vm.updatePreferred(null) }
            ProviderId.entries.forEach { p -> RadioRow(p.title(), vm.preferred == p) { vm.updatePreferred(p) } }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SwitchRow(
                "Automatic fallback",
                "If the chosen provider fails, EVE may send the same request to another enabled provider. Off: your conversation stays with one provider.",
                vm.fallback && vm.mode == RoutingMode.AUTOMATIC, vm::updateFallback, enabled = vm.mode == RoutingMode.AUTOMATIC,
            )
            SwitchRow("Multi-AI collaboration", Labels.COLLABORATION_COST, vm.collaboration, vm::updateCollaboration)

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Diagnostics", fontWeight = FontWeight.SemiBold)
            Button(vm::testAll, enabled = !vm.testAllRunning) { Text(if (vm.testAllRunning) "Testing…" else "Test all providers") }
            vm.testResults.forEach { r ->
                Text("${r.provider.displayName}: ${if (r.ok) "✓ " else "✗ "}${r.message}", fontSize = 12.sp, color = if (r.ok) Ok else Bad)
            }
            Text("Tests send a one-word prompt and may use a few tokens. Keys and responses are never shown or logged.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, fontSize = 14.sp)
    }

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, enabled = enabled)
    }
