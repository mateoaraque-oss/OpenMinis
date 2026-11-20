package com.openminis.app.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The five states the AMSY orb knows. They are not cosmetic: each one is a
 * different shape, so the assistant's state is readable from across the room
 * without reading a single word.
 *
 * Ported from `ar.com.amsy.ui.OrbView` (see `amsy/orbview.txt`): the same
 * particle count, the same four shapes, the same cyan, the same constellation
 * threshold. No images, no shaders to load, no GPU feature a budget SoC can
 * refuse.
 */
enum class AmsyOrbState {
    /** Elliptical spiral, slow drift. Nobody is talking. */
    IDLE,

    /** Golden-angle sphere; the cloud shivers with the microphone. */
    LISTENING,

    /** Three spiral arms turning fast. A request is in flight. */
    THINKING,

    /** Concentric rings rippling outward. The answer is being spoken. */
    SPEAKING,

    /** Same spiral as idle, but wider and angrier. Something broke. */
    ERROR,
}

/**
 * Particle simulation behind the orb, unit-agnostic: it works in view pixels
 * and only needs [resize] once. All the constants are the ones decoded from
 * the AMSY bytecode (`OrbView.getTargets`, `OrbView.onDraw`), including the
 * 120 particles, the six-point trails and the 0.82/0.038 integrator.
 *
 * Kept separate from the view so the same cloud can be drawn by a Compose
 * Canvas later without dragging Android's Paint along.
 */
class AmsyOrbSim {

    companion object {
        const val COUNT = 120
        const val TRAIL = 6
        private const val MORPH_FRAMES = 30
    }

    private val random = java.util.Random(0x5EEDL)

    private val px = FloatArray(COUNT)
    private val py = FloatArray(COUNT)
    private val vx = FloatArray(COUNT)
    private val vy = FloatArray(COUNT)
    private val targetX = FloatArray(COUNT)
    private val targetY = FloatArray(COUNT)
    private val fromX = FloatArray(COUNT)
    private val fromY = FloatArray(COUNT)

    internal val trailX = Array(COUNT) { FloatArray(TRAIL) }
    internal val trailY = Array(COUNT) { FloatArray(TRAIL) }
    internal val trailN = IntArray(COUNT)

    private var morph = 0
    private var frame = 0f
    private var seeded = false

    var cx = 0f
        private set
    var cy = 0f
        private set
    var radius = 0f
        private set

    /** Current body language. Changing it starts a 30-frame morph. */
    var state: AmsyOrbState = AmsyOrbState.IDLE
        set(value) {
            if (value == field) return
            field = value
            for (i in 0 until COUNT) {
                fromX[i] = px[i]
                fromY[i] = py[i]
            }
            morph = MORPH_FRAMES
        }

    /** 0..1 microphone or speech level; drives jitter, core and rings. */
    var level: Float = 0f

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // AMSY: centre at (w/2, h*0.48), radius = min(w, h) * 0.36.
        cx = width / 2f
        cy = height * 0.48f
        radius = min(width, height) * 0.36f
        if (!seeded) {
            seeded = true
            for (i in 0 until COUNT) {
                val angle = random.nextFloat() * 2f * PI.toFloat()
                val dist = random.nextFloat() * radius * 0.08f
                px[i] = cx + cos(angle) * dist
                py[i] = cy + sin(angle) * dist
                vx[i] = 0f
                vy[i] = 0f
                trailN[i] = 0
            }
            for (i in 0 until COUNT) {
                targetX[i] = px[i]
                targetY[i] = py[i]
            }
        }
    }

    /** Advance one frame. Called once per draw, exactly like the original. */
    fun step() {
        if (radius <= 0f) return
        frame += 1f
        computeTargets()

        val amplitude = amplitude()
        // The original jitters in raw pixels; scale that with the cloud so a
        // 48 dp orb shakes like a 48 dp orb and not like a full screen one.
        val jitter = 0.6f * amplitude * (radius / 220f).coerceIn(0.35f, 1.6f)
        if (morph > 0) morph--
        val progress = if (morph > 0) 1f - morph / MORPH_FRAMES.toFloat() else 1f
        val ease = (3f - 2f * progress) * progress * progress

        for (i in 0 until COUNT) {
            val tx = if (morph > 0) fromX[i] + (targetX[i] - fromX[i]) * ease else targetX[i]
            val ty = if (morph > 0) fromY[i] + (targetY[i] - fromY[i]) * ease else targetY[i]

            vx[i] = vx[i] * 0.82f + (tx - px[i]) * 0.038f +
                (random.nextFloat() - 0.5f) * jitter
            vy[i] = vy[i] * 0.82f + (ty - py[i]) * 0.038f +
                (random.nextFloat() - 0.5f) * jitter
            px[i] += vx[i]
            py[i] += vy[i]

            pushTrail(i)
        }
    }

    private fun pushTrail(i: Int) {
        val n = trailN[i]
        val tx = trailX[i]
        val ty = trailY[i]
        if (n < TRAIL) {
            tx[n] = px[i]
            ty[n] = py[i]
            trailN[i] = n + 1
        } else {
            System.arraycopy(tx, 1, tx, 0, TRAIL - 1)
            System.arraycopy(ty, 1, ty, 0, TRAIL - 1)
            tx[TRAIL - 1] = px[i]
            ty[TRAIL - 1] = py[i]
        }
    }

    private fun amplitude(): Float = when (state) {
        AmsyOrbState.IDLE -> 0.6f
        AmsyOrbState.LISTENING -> 1.8f
        AmsyOrbState.THINKING -> 1.4f
        AmsyOrbState.SPEAKING -> 1.2f + 1.4f * level.coerceIn(0f, 1f)
        AmsyOrbState.ERROR -> 2.2f
    }

    internal fun particleX(i: Int) = px[i]

    internal fun particleY(i: Int) = py[i]

    private fun computeTargets() {
        val t = frame
        val r = radius
        when (state) {
            AmsyOrbState.IDLE, AmsyOrbState.ERROR -> {
                // Elliptical spiral, radius breathing between 0.1 and 0.9.
                val rotation = -t * 0.018f
                for (i in 0 until COUNT) {
                    val u = i / COUNT.toFloat()
                    val phase = rotation - u * 1.8f
                    val spread = 0.4f * cos(u * PI.toFloat()) + 0.5f
                    targetX[i] = cx + r * spread * cos(phase)
                    targetY[i] = cy + r * spread * sin(phase) * 0.55f
                }
            }

            AmsyOrbState.LISTENING -> {
                // Golden-angle scatter over a sphere profile.
                val step = (sqrt(5f) - 3f) * PI.toFloat()
                for (i in 0 until COUNT) {
                    val u = i / (COUNT - 1f)
                    val y = 1f - 2f * u
                    val profile = sqrt(max(0f, 1f - y * y))
                    val angle = u * step
                    val depth = (sin(angle) * profile + PI.toFloat() / 2f) / 2.65f
                    targetX[i] = cx + r * cos(angle) * profile * depth
                    targetY[i] = cy + r * y * 0.88f * depth
                }
            }

            AmsyOrbState.THINKING -> {
                // Three spiral arms, 10*pi of twist, spinning.
                val rotation = t * 0.03f
                for (i in 0 until COUNT) {
                    val u = i / COUNT.toFloat()
                    val angle = rotation + u * 10f * PI.toFloat() + (i % 3) * 2.0944f
                    val armRadius = r * (0.05f + 0.92f * sqrt(u)) * 0.88f
                    targetX[i] = cx + cos(angle) * armRadius
                    targetY[i] = cy + sin(angle) * armRadius * 0.92f
                }
            }

            AmsyOrbState.SPEAKING -> {
                // Seven concentric rings of seventeen particles, rippling.
                val wave = sin(t * 0.05f) * 0.12f
                val levelNow = level.coerceIn(0f, 1f)
                val perRing = 17
                for (i in 0 until COUNT) {
                    val ring = min(i / perRing, 6)
                    val slot = i % perRing
                    val angle = (slot / perRing.toFloat()) * 2f * PI.toFloat() +
                        ring * 0.6f + t * 0.008f
                    val ringRadius = r * (ring + 1f) / 7f *
                        (0.65f + 0.38f * levelNow + wave)
                    targetX[i] = cx + cos(angle) * ringRadius
                    targetY[i] = cy + sin(angle) * ringRadius * 0.78f
                }
            }
        }
    }
}

/**
 * The orb itself, as a plain [View] so the overlay card (which has no
 * composition) and the app (which does) can share one implementation.
 *
 * Layers, back to front, exactly like AMSY: dot grid, radial core, particle
 * constellation, six-point trails, blurred glow dots.
 */
class AmsyOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val sim = AmsyOrbSim()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var state: AmsyOrbState
        get() = sim.state
        set(value) {
            if (sim.state != value) {
                sim.state = value
                invalidate()
            }
        }

    var level: Float
        get() = sim.level
        set(value) {
            sim.level = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        // The glow dots use a BlurMaskFilter, which the hardware canvas
        // quietly drops; a software layer brings it back.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sim.resize(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun cyan(alpha: Float): Int {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        return Color.argb(a, 0, 212, 255)
    }

    override fun onDraw(canvas: Canvas) {
        if (sim.radius <= 0f) return
        sim.step()

        val s = (sim.radius / 220f).coerceIn(0.55f, 1.6f)
        val effective = if (sim.state == AmsyOrbState.IDLE) max(sim.level, 0.22f) else sim.level

        drawGrid(canvas, s)
        drawCore(canvas, effective)
        drawConstellation(canvas, s)
        drawTrails(canvas, s)
        drawGlow(canvas, s, effective)
    }

    /** The 2x2 dot grid AMSY paints behind everything (alpha 26, #00D4FF). */
    private fun drawGrid(canvas: Canvas, s: Float) {
        val spacing = max(12f, sim.radius * 0.34f)
        val reach = sim.radius * 1.08f
        gridPaint.color = cyan(26f / 255f)
        var y = 0f
        while (y <= height) {
            var x = 0f
            while (x <= width) {
                val dx = x - sim.cx
                val dy = y - sim.cy
                if (dx * dx + dy * dy <= reach * reach) {
                    canvas.drawRect(x - 0.8f, y - 0.8f, x + 0.8f, y + 0.8f, gridPaint)
                }
                x += spacing
            }
            y += spacing
        }
    }

    private fun drawCore(canvas: Canvas, level: Float) {
        val coreRadius = sim.radius * 0.30f * level
        if (coreRadius < 0.6f) return
        val alpha = (38f * level + 22f) / 255f
        corePaint.shader = RadialGradient(
            sim.cx,
            sim.cy,
            coreRadius,
            intArrayOf(cyan(alpha), cyan(alpha * 0.32f), cyan(0f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(sim.cx, sim.cy, coreRadius, corePaint)
    }

    private fun drawConstellation(canvas: Canvas, s: Float) {
        val threshold = (if (sim.state == AmsyOrbState.IDLE) 82f else 55f) *
            (sim.radius / 220f).coerceIn(0.45f, 1.9f)
        val stride = if (sim.radius < 70f) 2 else 1
        linePaint.maskFilter = null
        var i = 0
        while (i < AmsyOrbSim.COUNT) {
            val ax = sim.particleX(i)
            val ay = sim.particleY(i)
            val last = min(i + 12, AmsyOrbSim.COUNT)
            var j = i + 1
            while (j < last) {
                val dx = ax - sim.particleX(j)
                val dy = ay - sim.particleY(j)
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < threshold) {
                    val t = 1f - dist / threshold
                    val alpha = (t.pow(1.5f) * 72f / 255f).coerceIn(0f, 1f)
                    linePaint.color = cyan(alpha)
                    linePaint.strokeWidth = (t * 0.9f + 0.22f) * s
                    canvas.drawLine(ax, ay, sim.particleX(j), sim.particleY(j), linePaint)
                }
                j++
            }
            i += stride
        }
    }

    /** Each particle drags a comet tail of at most six points. */
    private fun drawTrails(canvas: Canvas, s: Float) {
        linePaint.maskFilter = null
        for (i in 0 until AmsyOrbSim.COUNT) {
            val n = sim.trailN[i]
            if (n < 2) continue
            val tx = sim.trailX[i]
            val ty = sim.trailY[i]
            for (k in 1 until n) {
                val f = k / n.toFloat()
                val alpha = (f.pow(1.4f) * 100f / 255f).coerceIn(0f, 1f)
                linePaint.color = cyan(alpha)
                linePaint.strokeWidth = (f * 1.5f + 0.22f) * s
                canvas.drawLine(tx[k - 1], ty[k - 1], tx[k], ty[k], linePaint)
            }
        }
    }

    /** Blurred head: the thing that actually reads as a particle. */
    private fun drawGlow(canvas: Canvas, s: Float, level: Float) {
        glowPaint.maskFilter = BlurMaskFilter(2f * s, BlurMaskFilter.Blur.NORMAL)
        glowPaint.color = cyan(0.45f * level + 0.55f)
        val dot = (0.9f + 0.9f * level) * s
        for (i in 0 until AmsyOrbSim.COUNT) {
            canvas.drawCircle(sim.particleX(i), sim.particleY(i), dot, glowPaint)
        }
        glowPaint.maskFilter = null
    }
}

/** Compose entry point: the same orb, embedded with zero duplicated drawing. */
@Composable
fun AmsyOrb(
    modifier: Modifier = Modifier,
    diameter: Dp = 112.dp,
    state: AmsyOrbState = AmsyOrbState.IDLE,
    level: Float = 0f,
) {
    AndroidView(
        modifier = modifier.size(diameter),
        factory = { context -> AmsyOrbView(context) },
        update = { view ->
            view.state = state
            view.level = level
        },
    )
}
