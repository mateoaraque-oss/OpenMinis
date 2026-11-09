package com.openminis.app.wakeword

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * [T-orion-wake-panel] The small floating panel that answers a wake word.
 *
 * Gemini shows a microphone/orb bar over whatever app is open instead of
 * taking over the screen; this is the same idea: a TYPE_APPLICATION_OVERLAY
 * card at the bottom with Orion's orb, the live transcript, and a close
 * button. It is display-only (FLAG_NOT_FOCUSABLE) so the app underneath keeps
 * its keyboard and focus; only the close button consumes touches.
 *
 * Requires SYSTEM_ALERT_WINDOW. Callers must check [canShow] first.
 */
class WakePanelController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var root: FrameLayout? = null
    private var statusView: TextView? = null
    private var transcriptView: TextView? = null

    val canShow: Boolean
        get() = Settings.canDrawOverlays(context)

    val isShowing: Boolean
        get() = root != null

    fun show(title: String, hint: String) {
        if (root != null || windowManager == null) return

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(0xF21A1A20.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        card.addView(
            OrbView(context),
            LinearLayout.LayoutParams(dp(42), dp(42)),
        )

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        statusView = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        transcriptView = TextView(context).apply {
            text = hint
            setTextColor(0xFFB9C2D0.toInt())
            textSize = 12.5f
            maxLines = 2
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(statusView)
        texts.addView(transcriptView)
        card.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val close = TextView(context).apply {
            text = "\u2715"
            setTextColor(0xFFCBD3E1.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Cerrar"
            setOnClickListener { onClose?.invoke() }
        }
        card.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))

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
        } catch (_: Throwable) {
            root = null
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
        statusView?.text = text
    }

    fun hide() {
        val frame = root ?: return
        root = null
        statusView = null
        transcriptView = null
        frame.animate().translationY(frame.height.toFloat() + 80f).alpha(0f)
            .setDuration(180)
            .withEndAction {
                runCatching { windowManager?.removeView(frame) }
            }
            .start()
    }

    /** Simple pulsing sphere; the real Compose orb needs a lifecycle owner. */
    private class OrbView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_300
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            pulse.start()
        }

        override fun onDetachedFromWindow() {
            pulse.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(cx, cy) * (0.62f + 0.08f * phase)
            glow.shader = RadialGradient(
                cx, cy, radius * 1.55f,
                intArrayOf(0x553FD4FF, 0x003FD4FF),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius * 1.55f, glow)
            fill.shader = RadialGradient(
                cx - radius * 0.3f, cy - radius * 0.35f, radius * 1.6f,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFF3FD4FF.toInt(), 0xFF7C4DFF.toInt()),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, fill)
        }
    }
}
