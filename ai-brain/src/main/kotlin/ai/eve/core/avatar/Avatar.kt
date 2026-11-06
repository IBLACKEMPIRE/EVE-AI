package ai.eve.core.avatar

/**
 * Renderer-neutral avatar contract. The prototype uses a 2D placeholder; the production
 * EVE character (Unity-as-a-Library or Filament, see docs/05-avatar-pipeline.md) implements
 * the same interface, so the agent and UI never change when the renderer does.
 */
enum class AvatarMode { IDLE, LISTENING, THINKING, SPEAKING, SLEEPING }

enum class Emotion { NEUTRAL, HAPPY, SAD, SURPRISED, CONCERNED, THINKING;
    companion object {
        fun parse(tag: String?): Emotion = when (tag?.trim()?.lowercase()) {
            "happy", "joy", "glad" -> HAPPY
            "sad", "sorrow" -> SAD
            "surprised", "surprise" -> SURPRISED
            "concerned", "worried", "warning", "angry" -> CONCERNED
            "thinking" -> THINKING
            else -> NEUTRAL
        }
    }
}

/** Oculus/ARKit-compatible reduced viseme set; maps 1:1 to blendshape groups in the rig spec. */
enum class Viseme { SIL, PP, FF, TH, DD, KK, CH, SS, NN, RR, AA, E, IH, OH, OU }

data class VisemeFrame(val viseme: Viseme, val startMs: Long, val durationMs: Long)

/** Body-level animation states the rig must provide (Phase 3 list). */
enum class BodyState { IDLE_STAND, IDLE_SIT, WALK, TURN_LEFT, TURN_RIGHT, SIT_DOWN, STAND_UP, GESTURE_EXPLAIN, GESTURE_WAVE, NOD, HEAD_TILT }

interface AvatarController {
    fun setMode(mode: AvatarMode)
    fun setEmotion(emotion: Emotion, intensity: Float = 1f)
    fun playVisemes(frames: List<VisemeFrame>)
    /** Live mouth openness 0..1 from audio amplitude; fallback when timed visemes are unavailable. */
    fun setMouthOpen(amount: Float)
    fun playBody(state: BodyState)
    fun lookAt(x: Float, y: Float)
}

/**
 * Text → approximate viseme timeline. Used when the TTS engine gives no phoneme timing
 * (Android's platform TTS doesn't). A cloud TTS with viseme events replaces this.
 */
object VisemeMapper {
    fun fromText(text: String, msPerChar: Long = 65): List<VisemeFrame> {
        val frames = mutableListOf<VisemeFrame>()
        var t = 0L
        val lower = text.lowercase()
        var i = 0
        while (i < lower.length) {
            val c = lower[i]
            val next = lower.getOrNull(i + 1)
            val (v, consumed) = when {
                c == 't' && next == 'h' -> Viseme.TH to 2
                (c == 'c' || c == 's') && next == 'h' -> Viseme.CH to 2
                c in "pbm" -> Viseme.PP to 1
                c in "fv" -> Viseme.FF to 1
                c in "td" -> Viseme.DD to 1
                c in "kgq" -> Viseme.KK to 1
                c in "jz" -> Viseme.CH to 1
                c in "sx" -> Viseme.SS to 1
                c in "nl" -> Viseme.NN to 1
                c == 'r' -> Viseme.RR to 1
                c == 'a' -> Viseme.AA to 1
                c == 'e' -> Viseme.E to 1
                c in "iy" -> Viseme.IH to 1
                c == 'o' -> Viseme.OH to 1
                c in "uw" -> Viseme.OU to 1
                c.isLetter() -> Viseme.AA to 1 // non-Latin scripts: generic open mouth
                else -> Viseme.SIL to 1
            }
            val dur = msPerChar * consumed
            val last = frames.lastOrNull()
            if (last != null && last.viseme == v) frames[frames.lastIndex] = last.copy(durationMs = last.durationMs + dur)
            else frames += VisemeFrame(v, t, dur)
            t += dur
            i += consumed
        }
        return frames
    }

    /** Openness per viseme, for simple renderers that only have a jaw/mouth-open control. */
    fun openness(v: Viseme): Float = when (v) {
        Viseme.SIL, Viseme.PP -> 0f
        Viseme.FF, Viseme.TH, Viseme.NN -> 0.2f
        Viseme.DD, Viseme.SS, Viseme.KK, Viseme.CH, Viseme.RR -> 0.35f
        Viseme.IH, Viseme.E -> 0.5f
        Viseme.OU -> 0.45f
        Viseme.OH -> 0.7f
        Viseme.AA -> 0.9f
    }
}
