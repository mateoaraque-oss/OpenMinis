package com.openminis.app.wakeword

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * [T-orion-wake-panel] Floating Orion panel, styled after J.A.R.V.I.S.
 *
 * Gemini answers a hotword with a compact card over whatever app is open
 * instead of taking over the screen: an orb on the left that changes state
 * (listening → thinking → speaking), a live transcript while you talk, and a
 * dismiss affordance. This is the same interaction model, drawn as a HUD:
 * a dark translucent card, an arc-reactor orb with counter-rotating rings and
 * a waveform that follows the microphone level.
 *
 * It is a classic View hierarchy (not Compose) because a
 * TYPE_APPLICATION_OVERLAY window has no Activity or ViewModelStoreOwner to
 * host a composition. FLAG_NOT_FOCUSABLE keeps the app underneath in charge
 * of the keyboard; only the close button and the card consume touches.
 *
 * Requires SYSTEM_ALERT_WINDOW. Callers must check [canShow] first.
 */
class WakePanelController(private val context: Context) {

    /** Visual state of the orb; drives the accent colour. */
    enum class State { LISTENING, PROCESSING, ERROR }

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var root: FrameLayout? = null
    private var statusView: TextView? = null
    private var transcriptView: TextView? = null
    private var orb: JarvisOrbView? = null
    private var wave: WaveformView? = null

    val canShow: Boolean
        get() = Settings.canDrawOverlays(context)

    val isShowing: Boolean
        get() = root != null

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun show(title: String, hint: String) {
        if (root != null || windowManager == null) return

        // Dark HUD card with a cyan hairline, the same visual language as the
        // JARVIS reference dashboard: near-black teal, one ice-cyan accent
        // (#7EE8FF), thin borders, uppercase wide-tracked labels.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                setColors(intArrayOf(0xF2000E14.toInt(), 0xF200161E.toInt()))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x597EE8FF)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val orbView = JarvisOrbView(context)
        orb = orbView
        row.addView(orbView, LinearLayout.LayoutParams(dp(48), dp(48)))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(
            PulseDotView(context),
            LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(7) },
        )
        statusView = TextView(context).apply {
            text = title.uppercase()
            setTextColor(ACCENT)
            textSize = 10.5f
            letterSpacing = 0.32f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            maxLines = 1
        }
        statusRow.addView(statusView)
        transcriptView = TextView(context).apply {
            text = hint
            setTextColor(0xFFDCEBF7.toInt())
            textSize = 13f
            setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
            maxLines = 2
            setPadding(0, dp(3), 0, 0)
        }
        texts.addView(statusRow)
        texts.addView(transcriptView)
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val close = TextView(context).apply {
            text = "\u2715"
            setTextColor(0xB37EE8FF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Cerrar"
            setOnClickListener { onClose?.invoke() }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))

        card.addView(row)

        // Thin waveform under the text: it fills while the user talks and
        // collapses when the recognizer finishes, so the card never sits
        // visually "dead" between states.
        val waveView = WaveformView(context)
        wave = waveView
        card.addView(
            waveView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(22),
            ).apply { topMargin = dp(7) },
        )

        val frame = FrameLayout(context)
        frame.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(12), dp(8), dp(12), dp(8))
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(28)
        }

        frame.translationY = dp(90).toFloat()
        frame.alpha = 0f
        try {
            windowManager.addView(frame, params)
        } catch (t: Throwable) {
            root = null
            orb = null
            wave = null
            return
        }
        root = frame
        frame.animate().translationY(0f).alpha(1f).setDuration(220).start()
    }

    /** Optional callback when the user taps the X. */
    var onClose: (() -> Unit)? = null

    fun setTranscript(text: String) {
        transcriptView?.text = text
    }

    fun setStatus(text: String) {
        statusView?.text = text.uppercase()
    }

    fun setState(state: State) {
        val color = when (state) {
            State.LISTENING -> ACCENT
            State.PROCESSING -> 0xFFEAF6FF.toInt()
            State.ERROR -> 0xFFFF6B6B.toInt()
        }
        orb?.accent = color
        wave?.accent = color
        statusView?.setTextColor(color)
        if (state != State.LISTENING) {
            wave?.silence()
        }
    }

    /** 0..1 microphone level, fed from SpeechRecognizer.onRmsChanged. */
    fun setAmplitude(level: Float) {
        wave?.setLevel(level)
    }

    fun hide() {
        val frame = root ?: return
        root = null
        statusView = null
        transcriptView = null
        orb = null
        wave = null
        frame.animate().translationY(frame.height.toFloat() + 80f).alpha(0f)
            .setDuration(180)
            .withEndAction {
                runCatching { windowManager?.removeView(frame) }
            }
            .start()
    }

    private companion object {
        const val ACCENT = 0xFF7EE8FF.toInt()
    }

    /**
     * The little breathing dot that precedes "ORIÓN · ESCUCHANDO". In the
     * reference dashboard the identity badge carries a pulsing dot; here it
     * doubles as the "system alive" indicator for the card.
     */
    private class PulseDotView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(cx, cy) * (0.62f + 0.18f * phase)
            val accent = 0xFF7EE8FF.toInt()
            paint.color = Color.argb(
                (150 + 105 * phase).toInt().coerceIn(120, 255),
                Color.red(accent),
                Color.green(accent),
                Color.blue(accent),
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    /**
     * Arc-reactor orb: a pulsing plasma core, one long rotating arc and a
     * pair of short counter-rotating arcs. Everything is Canvas drawing, so
     * there is no drawable asset to ship and no animation dependency.
     */
    private class JarvisOrbView(context: Context) : View(context) {

        private val core = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        @Volatile
        var accent: Int = 0xFF7EE8FF.toInt()
            set(value) {
                field = value
                invalidate()
            }

        private var phase = 0f

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        private fun argb(alpha: Int, color: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(cx, cy)
            val angle = phase * 360f
            val pulse = 1f + 0.06f * sin(phase * 2f * Math.PI.toFloat() * 3f)

            // Outer glow.
            glow.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(argb(90, accent), argb(0, accent)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, glow)

            // Plasma core.
            core.shader = RadialGradient(
                cx, cy, r * 0.52f,
                intArrayOf(
                    Color.WHITE,
                    argb(235, accent),
                    argb(90, accent),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r * 0.34f * pulse, core)

            // Long arc, clockwise.
            ring.strokeWidth = r * 0.075f
            ring.color = argb(215, accent)
            val r1 = r * 0.86f
            canvas.drawArc(cx - r1, cy - r1, cx + r1, cy + r1, angle, 205f, false, ring)

            // Two short arcs, counter-clockwise.
            ring.strokeWidth = r * 0.055f
            ring.color = argb(150, accent)
            val r2 = r * 0.68f
            val start = -angle * 1.55f
            canvas.drawArc(cx - r2, cy - r2, cx + r2, cy + r2, start, 120f, false, ring)
            canvas.drawArc(cx - r2, cy - r2, cx + r2, cy + r2, start + 180f, 55f, false, ring)
        }
    }

    /**
     * 28-bar waveform. Bars rise with the reported RMS level and fall on
     * their own, so the card keeps breathing when the recognizer sends no
     * callbacks (many OEM engines only emit onRmsChanged a few times).
     */
    private class WaveformView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
        }

        @Volatile
        var accent: Int = 0xFF7EE8FF.toInt()
            set(value) {
                field = value
                invalidate()
            }

        private var level = 0f
        private var phase = 0f

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                // Decay so the bars settle when nobody is talking.
                level *= 0.90f
                invalidate()
            }
        }

        fun setLevel(value: Float) {
            level = value.coerceIn(0f, 1f)
            invalidate()
        }

        fun silence() {
            level = 0f
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val count = 28
            val h = height.toFloat()
            if (h <= 0f) return
            val slot = width.toFloat() / count
            val barWidth = slot * 0.45f
            paint.strokeWidth = barWidth
            val base = context.resources.displayMetrics.density * 1.2f

            for (i in 0 until count) {
                val t = i.toFloat() / (count - 1)
                // Centre-high envelope so the shape reads as a voice, not a
                // flat EQ.
                val envelope = 0.25f + 0.75f * sin(t * Math.PI.toFloat())
                val wobble = 0.55f + 0.45f * abs(
                    sin(phase * 2f * Math.PI.toFloat() + i * 0.65f),
                )
                val barH = base + (h - base) * level * envelope * wobble
                val x = i * slot + slot / 2f
                val alpha = (110 + 145 * level * envelope).toInt().coerceIn(90, 255)
                paint.color = Color.argb(
                    alpha,
                    Color.red(accent),
                    Color.green(accent),
                    Color.blue(accent),
                )
                canvas.drawLine(x, (h + barH) / 2f, x, (h - barH) / 2f, paint)
            }
        }
    }
}
