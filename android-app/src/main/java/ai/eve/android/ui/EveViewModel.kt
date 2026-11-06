package ai.eve.android.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.eve.android.avatar.AvatarStateHolder
import ai.eve.android.eve
import ai.eve.android.security.KeyState
import ai.eve.android.voice.SpeechInput
import ai.eve.android.voice.VoiceOutput
import ai.eve.core.audit.AuditEntry
import ai.eve.core.audit.AuditKind
import ai.eve.core.avatar.AvatarMode
import ai.eve.core.avatar.Emotion
import ai.eve.core.avatar.VisemeMapper
import ai.eve.core.llm.ConnectionReport
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.RoutingMode
import ai.eve.core.memory.MemoryItem
import ai.eve.core.policy.ApprovalGateway
import ai.eve.core.policy.ApprovalRequest
import ai.eve.core.providers.ModelCatalog
import ai.eve.core.tasks.AgentTask
import ai.eve.core.tasks.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ChatLine(val fromUser: Boolean, val text: String, val status: TaskStatus? = null, val meta: String? = null)
class PendingApproval(val request: ApprovalRequest, val answer: CompletableDeferred<Boolean>)

/** Everything the settings screen shows for one provider. Never contains the key itself. */
data class ProviderUi(
    val id: ProviderId,
    val keyState: KeyState,
    val model: String,
    val enabled: Boolean,
    val status: String?,
    val statusOk: Boolean?,
    val testing: Boolean = false,
)

class EveViewModel(app: Application) : AndroidViewModel(app) {
    private val rt = app.eve
    val avatar = AvatarStateHolder(viewModelScope)
    val lines = mutableStateListOf(ChatLine(false, "Hi, I'm EVE. Type or tap the mic to talk to me."))

    var busy by mutableStateOf(false); private set
    var listening by mutableStateOf(false); private set
    var partial by mutableStateOf(""); private set
    var pending by mutableStateOf<PendingApproval?>(null); private set
    var stopEngaged by mutableStateOf(rt.stop.isEngaged); private set
    var notice by mutableStateOf<String?>(null)
    var avatarVisible by mutableStateOf(true)

    val providers = mutableStateMapOf<ProviderId, ProviderUi>()
    var mode by mutableStateOf(rt.settings.mode); private set
    var preferred by mutableStateOf(rt.settings.preferred); private set
    var fallback by mutableStateOf(rt.settings.fallback); private set
    var collaboration by mutableStateOf(rt.settings.collaboration); private set
    var speakReplies by mutableStateOf(rt.settings.speakReplies); private set
    var routingLabel by mutableStateOf(""); private set
    var testAllRunning by mutableStateOf(false); private set
    val testResults = mutableStateListOf<ConnectionReport>()

    private var job: Job? = null
    private var testJob: Job? = null

    init {
        refreshProviders()
        rt.onHealthChanged = { viewModelScope.launch(Dispatchers.Main) { refreshProviders() } }
        if (rt.settings.migratedCorruptModel) lines += ChatLine(false, "I fixed an invalid saved model ID from an older version. Your API keys were kept.")
    }

    private fun refreshProviders() {
        for (id in ProviderId.entries) {
            val h = rt.health[id]
            providers[id] = ProviderUi(id, rt.settings.keyState(id), rt.settings.model(id), rt.settings.enabled(id), h?.message, h?.ok,
                testing = providers[id]?.testing == true)
        }
        val active = rt.activeProviders().map { it.id }
        routingLabel = Labels.routing(active, mode, preferred, rt.settings::model)
    }

    private fun applyChange() { rt.rebuildAgent(); refreshProviders() }

    // ── provider settings ──
    /** @return null on success, otherwise a validation message. */
    fun saveProvider(id: ProviderId, newKey: String?, model: String, enabled: Boolean): String? {
        if (!ModelCatalog.isPlausibleModelId(model)) return "That doesn't look like a model ID (letters, digits, '.', '-', '_' only)."
        if (!newKey.isNullOrBlank()) rt.settings.setKey(id, newKey)
        rt.settings.setModel(id, model)
        rt.settings.setEnabled(id, enabled)
        rt.health.remove(id)
        rt.audit.record(AuditKind.TASK_STATUS, null, "settings: ${id.key} saved (enabled=$enabled)")
        applyChange()
        return null
    }

    fun setProviderEnabled(id: ProviderId, enabled: Boolean) { rt.settings.setEnabled(id, enabled); applyChange() }

    fun deleteKey(id: ProviderId) {
        rt.settings.setKey(id, null)
        rt.health.remove(id)
        rt.audit.record(AuditKind.TASK_STATUS, null, "settings: ${id.key} key deleted")
        applyChange()
    }

    fun testProvider(id: ProviderId) {
        providers[id] = providers.getValue(id).copy(testing = true)
        testJob = viewModelScope.launch {
            try { withContext(Dispatchers.Default) { rt.testProvider(id) } }
            finally { providers[id]?.let { providers[id] = it.copy(testing = false) }; refreshProviders() }
        }
    }

    fun testAll() {
        if (testAllRunning) return
        testAllRunning = true
        testResults.clear()
        testJob = viewModelScope.launch {
            try {
                val reports = withContext(Dispatchers.Default) {
                    ProviderId.entries.map { id -> async { rt.testProvider(id) } }.awaitAll()
                }
                testResults.addAll(reports)
            } finally { testAllRunning = false; refreshProviders() }
        }
    }

    fun updateMode(m: RoutingMode) { rt.settings.mode = m; mode = m; applyChange() }
    fun updatePreferred(p: ProviderId?) { rt.settings.preferred = p; preferred = p; applyChange() }
    fun updateFallback(v: Boolean) { rt.settings.fallback = v; fallback = v; applyChange() }
    fun updateCollaboration(v: Boolean) { rt.settings.collaboration = v; collaboration = v; applyChange() }
    fun updateSpeak(v: Boolean) { rt.settings.speakReplies = v; speakReplies = v }

    // ── voice ──
    private val voice = VoiceOutput(app,
        onSpeaking = { s -> avatar.setMode(if (s) AvatarMode.SPEAKING else AvatarMode.IDLE) },
        onMouth = avatar::setMouthOpen)

    private val speech = SpeechInput(app,
        onPartial = { partial = it },
        onFinal = { send(it) },
        onLevel = { avatar.level = it },
        onEnd = { err ->
            listening = false; partial = ""
            if (avatar.currentMode == AvatarMode.LISTENING) avatar.setMode(AvatarMode.IDLE)
            if (err != null) notice = err
        })

    /** UI-backed approval: shows a dialog; no answer within 60 s counts as "deny". */
    private val gateway = ApprovalGateway { req ->
        val d = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { pending = PendingApproval(req, d) }
        try { withTimeoutOrNull(60_000) { d.await() } ?: false }
        finally { withContext(NonCancellable + Dispatchers.Main) { pending = null } }
    }

    fun answerApproval(ok: Boolean) { pending?.answer?.complete(ok) }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        lines += ChatLine(true, t)
        busy = true
        voice.stop()
        avatar.setMode(AvatarMode.THINKING); avatar.setEmotion(Emotion.THINKING)
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { rt.agent.run(t, gateway) }
                val meta = listOfNotNull(Labels.attribution(res, rt.settings::model), res.note).joinToString(" · ").ifBlank { null }
                lines += ChatLine(false, res.text, res.status, meta)
                avatar.setEmotion(Emotion.parse(res.emotion))
                avatar.setMode(AvatarMode.IDLE)
                if (speakReplies) voice.speak(res.text) else avatar.playVisemes(VisemeMapper.fromText(res.text))
            } catch (e: CancellationException) {
                lines += ChatLine(false, "Stopped.", TaskStatus.CANCELLED)
                avatar.setMode(AvatarMode.IDLE)
                throw e
            } finally {
                busy = false
            }
        }
    }

    fun toggleMic() {
        if (listening) { speech.stop(); listening = false; avatar.setMode(AvatarMode.IDLE); return }
        if (!speech.available) { notice = "No speech recognition service on this device."; return }
        voice.stop()
        listening = true
        avatar.setMode(AvatarMode.LISTENING)
        speech.start()
    }

    fun toggleEmergencyStop() {
        if (rt.stop.isEngaged) {
            rt.stop.release()
            rt.audit.record(AuditKind.EMERGENCY_STOP, null, "released")
        } else {
            rt.stop.engage()
            job?.cancel()      // cancels the agent, every in-flight provider request and collaboration analysts
            testJob?.cancel()
            pending?.answer?.complete(false)
            voice.stop(); speech.stop(); listening = false
            rt.audit.record(AuditKind.EMERGENCY_STOP, null, "engaged: running task and provider requests cancelled")
            avatar.setMode(AvatarMode.IDLE); avatar.setEmotion(Emotion.CONCERNED)
        }
        stopEngaged = rt.stop.isEngaged
    }

    fun tasks(): List<AgentTask> = rt.tasks.all()
    fun audit(limit: Int = 60): List<AuditEntry> = rt.audit.entries().takeLast(limit).reversed()
    fun memories(): List<MemoryItem> = rt.memory.all()
    fun forgetMemory(id: String) { rt.memory.forget(id) }
    fun clearMemories() { rt.memory.clear(); rt.audit.record(AuditKind.TOOL_RESULT, null, "user cleared all memories from settings") }

    override fun onCleared() { rt.onHealthChanged = {}; speech.stop(); voice.shutdown() }
}
