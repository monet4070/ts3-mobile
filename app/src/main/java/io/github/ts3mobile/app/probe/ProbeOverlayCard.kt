package io.github.ts3mobile.app.probe

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.ui.theme.tailSyncColors
import kotlin.math.roundToInt

internal data class ProbeOverlayDetails(
    val title: String,
    val localSummary: String = "",
    val localActivity: String = "",
    val membersLabel: String? = null,
    val speakers: List<String> = emptyList(),
    val moreSpeakers: Int = 0,
    val mode: MicrophoneMode? = null,
)

internal class ProbeOverlayCard(
    context: Context,
    holdLabel: Int,
    stop: () -> Unit,
    private val cancelPress: () -> Unit,
    private val lost: (ProbeOverlayCard) -> Unit,
    private val displayChanged: (ProbeOverlayCard) -> Unit,
    selectMode: ((MicrophoneMode) -> Unit)? = null,
) : LinearLayout(context) {
    private val colors =
        tailSyncColors(
            (context.applicationContext as Ts3MobileApplication).appearance.state.value.mode.isDark(
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
            ),
        )
    private val headline = text(14f, colors.onSurface.toArgb(), bold = true).apply { gravity = Gravity.CENTER }
    private val memberCount = text(11f, colors.onSurfaceVariant.toArgb())
    private val speakerPreview = text(11f, colors.onPrimaryContainer.toArgb(), bold = true)
    private val speakerPreviewIcon = icon(R.drawable.ic_lucide_audio_lines, colors.onPrimaryContainer.toArgb())
    private val chevron = icon(R.drawable.ic_lucide_chevron_down, colors.onSurfaceVariant.toArgb())
    private val speakerHeading = text(11f, colors.onSurfaceVariant.toArgb(), bold = true)
    private val speakerLines =
        listOf(text(16f, colors.onSurface.toArgb(), bold = true), text(13f, colors.onSurface.toArgb()))
    private val modeControls = selectMode?.let { ProbeModeControls(context, colors, it) }
    private var details: ProbeOverlayDetails? = null
    private var wasVisible = false

    val mode: MicrophoneMode? get() = details?.mode

    val microphone = ProbeMicButton(context, colors, holdLabel)
    val handle =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(4), 0, dp(4), 0)
            addView(icon(R.drawable.ic_ptt_grip, colors.onSurfaceVariant.toArgb()), LayoutParams(dp(12), dp(16)))
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPaddingRelative(dp(3), 0, dp(3), 0)
                    addView(headline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    addView(
                        LinearLayout(context).apply {
                            gravity = Gravity.CENTER
                            addView(memberCount)
                            addView(speakerPreviewIcon, LayoutParams(dp(10), dp(10)).apply { setMargins(dp(3), 0, dp(2), 0) })
                            addView(speakerPreview, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) },
                    )
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(chevron, LayoutParams(dp(12), dp(12)))
            background = RippleDrawable(ColorStateList.valueOf(colors.primary.copy(alpha = .10f).toArgb()), null, rounded(-1, 14))
        }
    private val speakerPanel =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(colors.secondaryContainer.toArgb(), 13)
            addView(speakerHeading)
            speakerLines.forEach { row ->
                addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(22)))
                row.gravity = Gravity.CENTER_VERTICAL
            }
        }
    private val body =
        ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(10), 0, dp(10), dp(5))
                    addView(speakerPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    addView(makeFooter(stop), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
                },
            )
        }

    var expanded = false
        private set

    init {
        orientation = VERTICAL
        elevation = dp(8).toFloat()
        clipToOutline = true
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(handle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                addView(microphone, LayoutParams(dp(96), LayoutParams.MATCH_PARENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, headerHeight()),
        )
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        expand(false)
        render(R.string.ptt_overlay_idle, false)
    }

    fun preferredWidth(): Int = dp(if (expanded) 286 else 216)

    fun headerHeight(): Int = dp(48) + 2 * dp(4)

    fun expand(value: Boolean) {
        expanded = value
        background = rounded(colors.surface.toArgb(), if (value) 20 else 22, colors.outlineVariant.toArgb())
        body.visibility = if (value) VISIBLE else GONE
        chevron.rotation = if (value) 180f else 0f
        updateHeader()
    }

    fun render(
        label: Int,
        activeTone: Boolean,
        transmitting: Boolean = false,
    ) = microphone.render(mode, label, activeTone, transmitting)

    fun renderDetails(current: ProbeOverlayDetails) {
        val previous = details
        if (current == previous) return
        details = current
        if (current.mode != previous?.mode) current.mode?.let { modeControls?.setMode(it) }
        if (headline.text != current.title) headline.text = current.title
        val count = current.membersLabel.orEmpty()
        if (memberCount.text != count) memberCount.text = count
        memberCount.visibility = if (current.membersLabel == null) GONE else VISIBLE
        val heading = if (current.membersLabel == null) R.string.ptt_overlay_local_details else R.string.ptt_overlay_speakers_heading
        if (speakerHeading.text != context.getText(heading)) speakerHeading.setText(heading)
        val lines =
            when {
                current.membersLabel == null -> listOfNotNull(current.localSummary, current.localActivity.takeIf(String::isNotEmpty))
                current.speakers.isEmpty() -> listOf(context.getString(R.string.ptt_overlay_no_speakers))
                else -> listOf(current.speakers.first(), speakerTail(current))
            }
        speakerLines.forEachIndexed { index, view ->
            val line = lines.getOrNull(index).orEmpty()
            if (view.text != line) view.text = line
            view.alpha = if (line.isEmpty()) 0f else 1f
        }
        updateHeader()
    }

    private fun speakerTail(current: ProbeOverlayDetails): String {
        val remaining = current.speakers.drop(1)
        val additional = current.moreSpeakers.takeIf { it > 0 }?.let { context.getString(R.string.ptt_overlay_more_speakers, it) }
        return (remaining + listOfNotNull(additional)).joinToString(context.getString(R.string.ptt_overlay_name_separator))
    }

    private fun updateHeader() {
        val current = details
        val preview = current?.speakers?.firstOrNull().orEmpty()
        if (speakerPreview.text != preview) speakerPreview.text = preview
        val visible = !expanded && preview.isNotEmpty()
        speakerPreview.visibility = if (visible) VISIBLE else GONE
        speakerPreviewIcon.visibility = speakerPreview.visibility
        val hint = context.getText(if (expanded) R.string.ptt_overlay_collapse else R.string.ptt_overlay_expand)
        val description = listOfNotNull(current?.title, current?.membersLabel, preview.takeIf { visible }, hint)
        handle.contentDescription = description.joinToString("\n")
    }

    private fun makeFooter(stop: () -> Unit) =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            modeControls?.let { addView(it, LayoutParams(0, dp(46), 1f)) }
            addView(
                icon(R.drawable.ic_close, colors.onSurfaceVariant.toArgb()).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                    contentDescription = context.getText(R.string.ptt_overlay_close)
                    tooltipText = contentDescription
                    setOnClickListener { stop() }
                },
                LayoutParams(dp(44), LayoutParams.MATCH_PARENT),
            )
        }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        post { displayChanged(this) }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) wasVisible = true
        if (wasVisible && visibility != VISIBLE) {
            cancelPress()
            post { lost(this) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPress()
        post { lost(this) }
    }

    private fun text(
        size: Float,
        tint: Int,
        bold: Boolean = false,
    ) = TextView(context).apply {
        textSize = size
        includeFontPadding = false
        setTextColor(tint)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun icon(
        drawable: Int,
        tint: Int,
    ) = ImageView(context).apply {
        setImageResource(drawable)
        setColorFilter(tint)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun rounded(
        fill: Int,
        radius: Int,
        stroke: Int? = null,
    ) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = context.probeDp(value)
}

internal fun Context.probeDp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
