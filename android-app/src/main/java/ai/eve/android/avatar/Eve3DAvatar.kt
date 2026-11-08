package ai.eve.android.avatar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LifecycleResumeEffect
import android.view.MotionEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Genuine GPU-rendered, articulated 3D preview. This is a procedural mannequin,
 * not a scan or a reconstruction of the approved photorealistic EVE reference.
 * No external assets, permissions, or API credentials are bundled.
 */
@Composable
fun Eve3DAvatar(state: AvatarStateHolder, modifier: Modifier = Modifier) {
    val renderer = remember { EveFigureRenderer() }
    // Update animation inputs from Compose; OpenGL reads a small immutable snapshot.
    renderer.mouth = state.mouth
    renderer.mode = state.currentMode.name
    renderer.lookX = state.lookX
    renderer.lookY = state.lookY
    val surface = remember { arrayOfNulls<GLSurfaceView>(1) }
    LifecycleResumeEffect(renderer) {
        surface[0]?.onResume()
        onPauseOrDispose { surface[0]?.onPause() }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(350.dp),
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                var previousX = 0f
                var previousDistance = 0f
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            previousX = event.x
                            true
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            if (event.pointerCount >= 2) {
                                previousDistance = kotlin.math.hypot(
                                    event.getX(1) - event.getX(0),
                                    event.getY(1) - event.getY(0),
                                )
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (event.pointerCount >= 2) {
                                val distance = kotlin.math.hypot(
                                    event.getX(1) - event.getX(0),
                                    event.getY(1) - event.getY(0),
                                )
                                if (previousDistance > 0f) {
                                    renderer.distance = (renderer.distance * previousDistance /
                                        distance.coerceAtLeast(1f)).coerceIn(5.5f, 11f)
                                }
                                previousDistance = distance
                            } else {
                                renderer.rotation = (renderer.rotation +
                                    (event.x - previousX) * 0.35f) % 360f
                                previousX = event.x
                            }
                            true
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            previousDistance = 0f
                            previousX = event.getX(0)
                            true
                        }
                        else -> true
                    }
                }
                surface[0] = this
            }
        },
    )
}

private class EveFigureRenderer : GLSurfaceView.Renderer {
    @Volatile var mouth = 0f
    @Volatile var lookX = 0f
    @Volatile var lookY = 0f
    @Volatile var mode = "IDLE"
    @Volatile var rotation = 0f
    @Volatile var distance = 7.7f
    private var program = 0
    private var vertexAttr = 0
    private var matrixUniform = 0
    private var colorUniform = 0
    private var lightUniform = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private val sphere = sphereGeometry()
    private val buffer = ByteBuffer.allocateDirect(sphere.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(sphere); position(0) }
    private var start = System.nanoTime()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.035f, 0.047f, 0.085f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vs = shader(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            varying vec3 vNormal;
            void main() {
                vNormal = normalize(aPosition);
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """.trimIndent())
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform vec4 uColor;
            uniform vec3 uLight;
            varying vec3 vNormal;
            void main() {
                float d = max(0.0, dot(normalize(vNormal), normalize(uLight)));
                vec3 shaded = uColor.rgb * (0.42 + 0.58 * d);
                gl_FragColor = vec4(shaded, uColor.a);
            }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        vertexAttr = GLES20.glGetAttribLocation(program, "aPosition")
        matrixUniform = GLES20.glGetUniformLocation(program, "uMvp")
        colorUniform = GLES20.glGetUniformLocation(program, "uColor")
        lightUniform = GLES20.glGetUniformLocation(program, "uLight")
        start = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 37f, width.toFloat() / height.coerceAtLeast(1), 0.1f, 30f)

    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(view, 0, 0f, 0.1f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(lightUniform, -0.45f, 0.8f, 1f)
        GLES20.glEnableVertexAttribArray(vertexAttr)
        GLES20.glVertexAttribPointer(vertexAttr, 3, GLES20.GL_FLOAT, false, 0, buffer)
        val t = (System.nanoTime() - start) / 1_000_000_000f
        val breathe = sin(t * 1.5f) * 0.025f
        val sway = sin(t * 0.7f) * 1.5f
        val blinking = (t % 4.3f) < 0.14f
        val white = floatArrayOf(0.84f, 0.89f, 0.97f, 1f)
        val pearl = floatArrayOf(0.58f, 0.72f, 0.91f, 1f)
        val skin = floatArrayOf(0.94f, 0.73f, 0.62f, 1f)
        val blonde = floatArrayOf(0.82f, 0.60f, 0.24f, 1f)
        val dark = floatArrayOf(0.12f, 0.19f, 0.26f, 1f)
        val iris = floatArrayOf(0.24f, 0.52f, 0.37f, 1f)
        fun part(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, tilt: Float = 0f) {
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, rotation + sway, 0f, 1f, 0f)
            Matrix.translateM(model, 0, x, y, z)
            Matrix.rotateM(model, 0, tilt, 0f, 0f, 1f)
            Matrix.scaleM(model, 0, sx, sy, sz)
            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            GLES20.glUniformMatrix4fv(matrixUniform, 1, false, mvp, 0)
            GLES20.glUniform4fv(colorUniform, 1, color, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sphere.size / 3)
        }
        // Full-body humanoid assembled from 3D meshes; proportions are a starting point.
        part(0f, -0.08f, 0f, 0.39f + breathe, 0.62f, 0.23f, white)
        part(0f, -0.53f, 0f, 0.30f, 0.35f, 0.22f, pearl)
        part(0f, 0.67f, 0f, 0.12f, 0.18f, 0.12f, skin)
        part(0f, 1.10f, 0f, 0.28f, 0.37f, 0.27f, skin)
        part(0f, 1.37f, -0.10f, 0.32f, 0.24f, 0.27f, blonde)
        for (side in listOf(-1f, 1f)) {
            part(side * 0.29f, 0.99f, -0.08f, 0.13f, 0.46f, 0.15f, blonde, side * 9f)
            part(side * 0.12f, 1.13f, 0.249f, 0.075f, if (blinking) 0.004f else 0.046f, 0.027f, white)
            if (!blinking) part(side * 0.12f + lookX * 0.015f, 1.13f + lookY * 0.012f, 0.272f, 0.034f, 0.036f, 0.018f, iris)
            part(side * 0.47f, 0.15f, 0f, 0.15f, 0.42f, 0.16f, white, side * 15f)
            part(side * 0.59f, -0.48f, 0f, 0.115f, 0.39f, 0.12f, pearl, side * 6f)
            part(side * 0.62f, -0.91f, 0f, 0.10f, 0.13f, 0.11f, skin)
            part(side * 0.18f, -1.08f, 0f, 0.19f, 0.51f, 0.20f, white, side * 3f)
            part(side * 0.18f, -1.90f, 0f, 0.14f, 0.49f, 0.16f, pearl)
            part(side * 0.18f, -2.40f, 0.10f, 0.17f, 0.13f, 0.30f, dark)
        }
        part(0f, 0.94f, 0.257f, 0.044f, 0.065f, 0.055f, skin)
        part(0f, 0.83f, 0.258f, 0.092f, 0.012f + mouth * 0.045f, 0.019f, dark)
        if (mode == "LISTENING" || mode == "THINKING") {
            part(0f, 0.34f, 0.25f, 0.075f, 0.075f, 0.045f, iris)
        }
        GLES20.glDisableVertexAttribArray(vertexAttr)
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        return id
    }

    companion object {
        private fun sphereGeometry(): FloatArray {
            val out = ArrayList<Float>()
            val lat = 18
            val lon = 24
            fun point(a: Double, b: Double) {
                out.add((sin(a) * cos(b)).toFloat())
                out.add(cos(a).toFloat())
                out.add((sin(a) * sin(b)).toFloat())
            }
            for (i in 0 until lat) for (j in 0 until lon) {
                val a = PI * i / lat
                val b = 2 * PI * j / lon
                val aa = PI * (i + 1) / lat
                val bb = 2 * PI * (j + 1) / lon
                point(a, b); point(aa, b); point(a, bb)
                point(a, bb); point(aa, b); point(aa, bb)
            }
            return out.toFloatArray()
        }
    }
}
