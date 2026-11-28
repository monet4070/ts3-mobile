package io.github.ts3mobile.app.probe

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode

internal class ProbeMicButton(
    context: Context,
    private val colors: ColorScheme,
    private val holdLabel: Int,
) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label =
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    private var lastRender: State? = null
    private var iconOnly = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPaddingRelative(context.probeDp(7), 0, context.probeDp(7), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams(context.probeDp(18), context.probeDp(18)))
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.probeDp(4) })
    }

    fun setIconOnly(iconOnly: Boolean) {
        if (this.iconOnly == iconOnly) return
        this.iconOnly = iconOnly
        label.visibility = if (iconOnly) GONE else VISIBLE
        val state = lastRender ?: return
        lastRender = null
        render(state.mode, state.status, state.recording, state.transmitting)
    }

    fun render(
        mode: MicrophoneMode?,
        status: Int,
        recording: Boolean,
        transmitting: Boolean,
    ) {
        val state = State(mode, status, recording, transmitting)
        if (lastRender == state) return
        lastRender = state
        val hold = mode == null || mode == MicrophoneMode.PUSH_TO_TALK
        val action =
            when {
                mode == MicrophoneMode.OFF && !recording -> R.string.ptt_overlay_mic_disabled
                mode == null && status == R.string.ptt_overlay_idle -> R.string.ptt_overlay_hold_test
                hold && status == R.string.ptt_overlay_idle -> R.string.ptt_overlay_hold_action
                recording && status == R.string.ptt_connected_listening -> R.string.ptt_overlay_gate_waiting
                else -> status
            }
        val fill =
            when {
                !hold && iconOnly && transmitting -> colors.errorContainer
                !hold -> colors.surfaceContainerLow
                transmitting -> colors.error
                recording -> colors.primary
                else -> colors.primaryContainer
            }
        val tint =
            when {
                !hold && iconOnly && transmitting -> colors.onErrorContainer
                !hold && transmitting -> colors.error
                !hold && recording -> colors.primary
                !hold -> colors.onSurfaceVariant
                transmitting -> colors.onError
                recording -> colors.onPrimary
                else -> colors.onPrimaryContainer
            }
        isEnabled = mode != MicrophoneMode.OFF
        updateIcon(state)
        icon.setColorFilter(tint.toArgb())
        label.setText(action)
        label.setTextColor(tint.toArgb())
        background =
            GradientDrawable().apply {
                cornerRadius = context.probeDp(13).toFloat()
                setColor(fill.toArgb())
            }
        contentDescription = context.getText(if (hold) holdLabel else R.string.microphone_label)
        ViewCompat.setStateDescription(this, context.getText(status))
    }

    private fun updateIcon(state: State) {
        val drawable =
            when {
                state.transmitting && !iconOnly -> R.drawable.ic_lucide_audio_lines
                state.recording || state.transmitting -> R.drawable.ic_lucide_mic
                else -> R.drawable.ic_lucide_mic_off
            }
        icon.setImageResource(drawable)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val mode = lastRender?.mode
        info.className = if (mode == null || mode == MicrophoneMode.PUSH_TO_TALK) "android.widget.Button" else "android.widget.TextView"
    }

    private data class State(val mode: MicrophoneMode?, val status: Int, val recording: Boolean, val transmitting: Boolean)
}
