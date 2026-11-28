package io.github.ts3mobile.app.probe

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode

internal class ProbeModeControls(
    context: Context,
    private val colors: ColorScheme,
    selectMode: (MicrophoneMode) -> Unit,
) : LinearLayout(context) {
    private val buttons =
        MicrophoneMode.entries.associateWith { mode ->
            TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 11f
                maxLines = 2
                setText(mode.label)
                setOnClickListener { selectMode(mode) }
            }
        }

    init {
        orientation = HORIZONTAL
        buttons.values.forEachIndexed { index, button ->
            addView(
                button,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index != 0) marginStart = context.probeDp(4)
                },
            )
        }
    }

    fun setMode(mode: MicrophoneMode) {
        buttons.forEach { (option, button) ->
            val selected = option == mode
            val fill = if (selected) colors.primaryContainer else colors.surfaceContainerHigh
            val tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
            val stroke = colors.primary.toArgb()
            val shape =
                GradientDrawable().apply {
                    cornerRadius = context.probeDp(9).toFloat()
                    setColor(fill.toArgb())
                    if (selected) setStroke(context.probeDp(1), stroke)
                }
            button.isSelected = selected
            button.background = RippleDrawable(ColorStateList.valueOf(tint.copy(alpha = .12f).toArgb()), shape, null)
            button.setTextColor(tint.toArgb())
        }
    }

    private val MicrophoneMode.label: Int
        get() =
            when (this) {
                MicrophoneMode.OFF -> R.string.microphone_mode_off
                MicrophoneMode.PUSH_TO_TALK -> R.string.microphone_mode_push_to_talk
                MicrophoneMode.CONTINUOUS -> R.string.microphone_mode_continuous
                MicrophoneMode.VOLUME_GATE -> R.string.ptt_overlay_gate_short
            }
}
