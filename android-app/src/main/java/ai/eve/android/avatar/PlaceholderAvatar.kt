package ai.eve.android.avatar

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eve.core.avatar.AvatarController
import ai.eve.core.avatar.AvatarMode
import ai.eve.core.avatar.BodyState
import ai.eve.core.avatar.Emotion
import ai.eve.core.avatar.VisemeFrame
import ai.eve.core.avatar.VisemeMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * State holder implementing the renderer-neutral AvatarController. The production 3D EVE
 * renderer implements the same interface (see docs/05-avatar-pipeline.md).
 */
class AvatarStateHolder(private val scope: CoroutineScope) : AvatarController {
    var currentMode by mutableStateOf(AvatarMode.IDLE); private set
    var emotion by mutableStateOf(Emotion.NEUTRAL); private set
    var mouth by mutableFloatStateOf(0f); private set
    var level by mutableFloatStateOf(0f)
    var lookX by mutableFloatStateOf(0f); private set
    var lookY by mutableFloatStateOf(0f); private set
    var body by mutableStateOf(BodyState.IDLE_STAND); private set
    private var visemeJob: Job? = null

    override fun setMode(mode: AvatarMode) { currentMode = mode }
    override fun setEmotion(emotion: Emotion, intensity: Float) { this.emotion = emotion }
    override fun setMouthOpen(amount: Float) { mouth = amount.coerceIn(0f, 1f) }
    override fun playBody(state: BodyState) { body = state }
    override fun lookAt(x: Float, y: Float) { lookX = x.coerceIn(-1f, 1f); lookY = y.coerceIn(-1f, 1f) }
    override fun playVisemes(frames: List<VisemeFrame>) {
        visemeJob?.cancel()
        visemeJob = scope.launch {
            for (f in frames) { mouth = VisemeMapper.openness(f.viseme); delay(f.durationMs) }
            mouth = 0f
        }
    }
}

/**
 * 2D stand-in for EVE: proves the state wiring (blink, gaze, breathing, emotion, lip-sync,
 * listening/thinking cues). It is intentionally abstract — it is NOT the approved EVE design.
 */
@Composable
fun PlaceholderAvatar(state: AvatarStateHolder, modifier: Modifier = Modifier) {
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3800), RepeatMode.Reverse), label = "breathV",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseV",
    )
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { delay(Random.nextLong(2200, 6000)); blink = true; delay(130); blink = false }
    }
    var saccade by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(state.currentMode) {
        while (true) {
            delay(Random.nextLong(900, 2600))
            saccade = if (state.currentMode == AvatarMode.THINKING) Offset(0.5f, -0.6f) else Offset(Random.nextFloat() * 0.5f - 0.25f, Random.nextFloat() * 0.3f - 0.15f)
        }
    }
    val mouth by animateFloatAsState(state.mouth, tween(70), label = "mouth")
    val eyeOpen by animateFloatAsState(if (blink || state.currentMode == AvatarMode.SLEEPING) 0.05f else 1f, tween(60), label = "eye")
    val smile by animateFloatAsState(when (state.emotion) { Emotion.HAPPY -> 1f; Emotion.SAD, Emotion.CONCERNED -> -0.6f; else -> 0.2f }, label = "smile")
    val brow by animateFloatAsState(when (state.emotion) { Emotion.SURPRISED -> -1f; Emotion.CONCERNED, Emotion.SAD -> 0.7f; Emotion.THINKING -> 0.3f; else -> 0f }, label = "brow")

    val skin = Color(0xFFE9C7B0); val hair = Color(0xFF2B1D1A); val iris = Color(0xFF5A7D6B); val accent = Color(0xFF8E7CFF)

    Box(modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(260.dp)) {
            val cx = size.width / 2; val cy = size.height * 0.5f + sin(breath * Math.PI).toFloat() * 3f
            val headW = size.height * 0.52f; val headH = size.height * 0.66f
            if (state.currentMode == AvatarMode.LISTENING) {
                drawCircle(accent.copy(alpha = 0.18f + 0.25f * state.level), radius = headH * (0.62f + 0.06f * pulse + 0.1f * state.level), center = Offset(cx, cy))
            }
            // shoulders
            drawOval(Color(0xFF1E1E2A), Offset(cx - headW, cy + headH * 0.42f), Size(headW * 2, headH * 0.7f))
            // hair back
            drawOval(hair, Offset(cx - headW * 0.62f, cy - headH * 0.56f), Size(headW * 1.24f, headH * 1.25f))
            // face
            drawOval(Brush.verticalGradient(listOf(skin, skin.copy(red = 0.86f)), cy - headH / 2, cy + headH / 2), Offset(cx - headW / 2, cy - headH / 2), Size(headW, headH))
            // fringe
            val fringe = Path().apply {
                moveTo(cx - headW * 0.55f, cy - headH * 0.05f)
                quadraticTo(cx - headW * 0.3f, cy - headH * 0.72f, cx + headW * 0.45f, cy - headH * 0.45f)
                quadraticTo(cx + headW * 0.62f, cy - headH * 0.2f, cx + headW * 0.52f, cy + headH * 0.05f)
                quadraticTo(cx + headW * 0.2f, cy - headH * 0.4f, cx - headW * 0.55f, cy - headH * 0.05f)
                close()
            }
            drawPath(fringe, hair)
            // eyes
            val eyeY = cy - headH * 0.04f; val eyeDx = headW * 0.2f; val eyeW = headW * 0.17f
            val gaze = Offset((state.lookX + saccade.x) * eyeW * 0.25f, (state.lookY + saccade.y) * eyeW * 0.15f)
            for (s in listOf(-1, 1)) {
                val ex = cx + s * eyeDx
                drawOval(Color.White, Offset(ex - eyeW / 2, eyeY - eyeW * 0.32f * eyeOpen), Size(eyeW, eyeW * 0.64f * eyeOpen))
                if (eyeOpen > 0.3f) {
                    drawCircle(iris, eyeW * 0.24f, Offset(ex, eyeY) + gaze)
                    drawCircle(Color(0xFF111111), eyeW * 0.11f, Offset(ex, eyeY) + gaze)
                    drawCircle(Color.White, eyeW * 0.05f, Offset(ex + eyeW * 0.07f, eyeY - eyeW * 0.07f) + gaze)
                }
                val by = eyeY - eyeW * 0.62f + brow * eyeW * 0.12f
                drawLine(hair, Offset(ex - eyeW * 0.5f, by + s * brow * eyeW * 0.08f), Offset(ex + eyeW * 0.5f, by - s * brow * eyeW * 0.08f), strokeWidth = eyeW * 0.1f)
            }
            // nose
            drawLine(skin.copy(red = 0.75f, green = 0.6f), Offset(cx, eyeY + headH * 0.08f), Offset(cx - headW * 0.02f, eyeY + headH * 0.17f), strokeWidth = 3f)
            // mouth: openness from visemes, curvature from emotion
            val my = cy + headH * 0.25f; val mw = headW * 0.22f
            val lips = Color(0xFFB5564F)
            if (mouth > 0.05f) {
                drawOval(Color(0xFF5A1F22), Offset(cx - mw / 2 * (1 - mouth * 0.2f), my - mw * 0.08f), Size(mw * (1 - mouth * 0.2f), mw * (0.12f + mouth * 0.5f)))
            }
            val curve = Path().apply {
                moveTo(cx - mw / 2, my - smile * mw * 0.1f)
                quadraticTo(cx, my + smile * mw * 0.25f, cx + mw / 2, my - smile * mw * 0.1f)
            }
            drawPath(curve, lips, style = Stroke(width = 5f))
            if (state.currentMode == AvatarMode.THINKING) for (i in 0..2) {
                drawCircle(accent.copy(alpha = if (((pulse * 3).toInt()) == i) 1f else 0.35f), 6f, Offset(cx + headW * 0.6f + i * 18f, cy - headH * 0.55f))
            }
        }
        Text("EVE · placeholder renderer", Modifier.align(Alignment.BottomCenter), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
