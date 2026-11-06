package ai.eve.android.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.eve.android.avatar.AvatarStateHolder
import ai.eve.android.eve
import ai.eve.android.voice.SpeechInput
import ai.eve.android.voice.VoiceOutput
import ai.eve.core.audit.AuditEntry
import ai.eve.core.audit.AuditKind
import ai.eve.core.avatar.AvatarMode
import ai.eve.core.avatar.Emotion
import ai.eve.core.avatar.VisemeMapper
import ai.eve.core.memory.MemoryItem
import ai.eve.core.policy.ApprovalGateway
import ai.eve.core.policy.ApprovalRequest
import ai.eve.core.tasks.AgentTask
import ai.eve.core.tasks.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ChatLine(val fromUser: Boolean, val text: String, val status: TaskStatus? = null)
class PendingApproval(val request: ApprovalRequest, val answer: CompletableDeferred<Boolean>)

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
    var keyConfigured by mutableStateOf(!rt.settings.apiKey().isNullOrBlank()); private set
    val modelName: String get() = rt.settings.model()
    val speakReplies: Boolean get() = rt.settings.speakReplies

    private var job: Job? = null

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
                lines += ChatLine(false, res.text, res.status)
                avatar.setEmotion(Emotion.parse(res.emotion))
                avatar.setMode(AvatarMode.IDLE)
                if (rt.settings.speakReplies) voice.speak(res.text) else avatar.playVisemes(VisemeMapper.fromText(res.text))
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
            job?.cancel()
            pending?.answer?.complete(false)
            voice.stop(); speech.stop(); listening = false
            rt.audit.record(AuditKind.EMERGENCY_STOP, null, "engaged: running task cancelled")
            avatar.setMode(AvatarMode.IDLE); avatar.setEmotion(Emotion.CONCERNED)
        }
        stopEngaged = rt.stop.isEngaged
    }

    fun saveSettings(apiKey: String?, model: String, speak: Boolean) {
        if (apiKey != null) rt.settings.setApiKey(apiKey)
        rt.settings.setModel(model)
        rt.settings.speakReplies = speak
        rt.rebuildAgent()
        keyConfigured = !rt.settings.apiKey().isNullOrBlank()
        lines += ChatLine(false, if (keyConfigured) "Connected to ${rt.settings.model()}. Conversation restarted." else "No API key — running in offline demo mode.")
    }

    fun tasks(): List<AgentTask> = rt.tasks.all()
    fun audit(limit: Int = 60): List<AuditEntry> = rt.audit.entries().takeLast(limit).reversed()
    fun memories(): List<MemoryItem> = rt.memory.all()
    fun forgetMemory(id: String) { rt.memory.forget(id) }
    fun clearMemories() { rt.memory.clear(); rt.audit.record(AuditKind.TOOL_RESULT, null, "user cleared all memories from settings") }

    override fun onCleared() { speech.stop(); voice.shutdown() }
}
