package io.github.ts3mobile.app.probe

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.ts3mobile.app.R

internal class ProbeSpeakerStrip(context: Context, private val colors: ColorScheme) : LinearLayout(context) {
    private val activity =
        ImageView(context).apply {
            setImageResource(R.drawable.ic_lucide_audio_lines)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPaddingRelative(dp(5), dp(5), dp(5), dp(5))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val idle = label(colors.onSurfaceVariant).apply { setText(R.string.ptt_overlay_no_speakers) }
    private val overflow =
        label(colors.onSecondaryContainer).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            background = pill(colors.secondaryContainer)
        }
    private val nicknameViews = List(MAX_VISIBLE_SPEAKERS) { label(colors.onSurface).apply { background = pill(colors.surface) } }
    private val rowHeight = maxOf(dp(28), nicknameViews.first().lineHeight + dp(8), idle.lineHeight + dp(8))
    private val nicknameSlots =
        nicknameViews.map { nickname ->
            FrameLayout(context).apply { addView(nickname, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, rowHeight)) }
        }
    private val nicknames =
        LinearLayout(context).apply {
            isBaselineAligned = false
            nicknameSlots.forEachIndexed { index, slot ->
                addView(slot, LayoutParams(0, rowHeight, 1f).apply { if (index > 0) marginStart = dp(4) })
            }
        }

    init {
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        setPaddingRelative(dp(5), dp(4), dp(5), dp(4))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(activity, LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(4) })
        addView(idle, LayoutParams(0, rowHeight, 1f))
        addView(nicknames, LayoutParams(0, rowHeight, 1f))
        addView(overflow, LayoutParams(LayoutParams.WRAP_CONTENT, rowHeight).apply { marginStart = dp(4) })
        render(emptyList(), 0)
    }

    fun render(
        speakers: List<String>,
        moreSpeakers: Int,
    ) {
        val visible = speakers.take(MAX_VISIBLE_SPEAKERS)
        val additional = moreSpeakers + speakers.size - visible.size
        val speaking = visible.isNotEmpty()
        idle.visibility = if (speaking) GONE else VISIBLE
        nicknames.visibility = if (speaking) VISIBLE else GONE
        background = pill(if (speaking) colors.surfaceContainerHigh else colors.surfaceContainerLow, radius = 13)
        activity.background = if (speaking) pill(colors.primaryContainer) else null
        activity.setColorFilter((if (speaking) colors.onPrimaryContainer else colors.onSurfaceVariant).toArgb())
        nicknameSlots.forEachIndexed { index, slot ->
            val name = visible.getOrNull(index)
            slot.visibility = if (name == null) GONE else VISIBLE
            val nickname = nicknameViews[index]
            if (nickname.text != name.orEmpty()) nickname.text = name.orEmpty()
            val width = if (visible.size == 1) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
            if (nickname.layoutParams.width != width) nickname.layoutParams = nickname.layoutParams.apply { this.width = width }
        }
        overflow.visibility = if (additional > 0) VISIBLE else GONE
        overflow.text = if (additional > 0) context.getString(R.string.ptt_overlay_more_speakers, additional) else ""
        contentDescription =
            if (speaking) {
                val extra =
                    additional.takeIf { it > 0 }?.let {
                        resources.getQuantityString(R.plurals.ptt_overlay_more_speakers_accessible, it, it)
                    }
                (listOf(context.getString(R.string.ptt_overlay_speakers_heading)) + visible + listOfNotNull(extra)).joinToString("\n")
            } else {
                idle.text
            }
    }

    private fun label(tint: Color) =
        TextView(context).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tint.toArgb())
            setSingleLine()
            setHorizontallyScrolling(false)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPaddingRelative(dp(7), 0, dp(7), 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun pill(
        fill: Color,
        radius: Int = 8,
    ) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill.toArgb())
    }

    private fun dp(value: Int): Int = context.probeDp(value)

    companion object {
        const val MAX_VISIBLE_SPEAKERS = 3
    }
}
