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
    private val minimalStyle: Boolean = false,
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
    private val minimalHandleIcon = icon(R.drawable.ic_ptt_grip, colors.onSurfaceVariant.toArgb())
    private val chevron = icon(R.drawable.ic_lucide_chevron_down, colors.onSurfaceVariant.toArgb())
    private val speakerStrip = ProbeSpeakerStrip(context, colors)
    private val localLines =
        listOf(text(16f, colors.onSurface.toArgb(), bold = true), text(13f, colors.onSurface.toArgb()))
    private val modeControls = selectMode?.let { ProbeModeControls(context, colors, it) }
    private var details: ProbeOverlayDetails? = null
    private var wasVisible = false

    val mode: MicrophoneMode? get() = details?.mode

    val microphone = ProbeMicButton(context, colors, holdLabel)
    private val detailedHandle =
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
        }
    val handle =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(detailedHandle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(minimalHandleIcon, LayoutParams(dp(20), dp(20)))
            background = RippleDrawable(ColorStateList.valueOf(colors.primary.copy(alpha = .10f).toArgb()), null, rounded(-1, 14))
        }
    private val localPanel =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(colors.secondaryContainer.toArgb(), 13)
            addView(text(11f, colors.onSurfaceVariant.toArgb(), bold = true).apply { setText(R.string.ptt_overlay_local_details) })
            localLines.forEach { row ->
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
                    addView(speakerStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    addView(localPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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

    private val iconOnly: Boolean get() = minimalStyle && !expanded

    fun preferredWidth(): Int =
        when {
            expanded -> dp(286)
            iconOnly -> 2 * (dp(48) + dp(4))
            else -> dp(216)
        }

    fun headerHeight(): Int = dp(48) + 2 * dp(4)

    fun expand(value: Boolean) {
        expanded = value
        background = rounded(colors.surface.toArgb(), if (value) 20 else 22, colors.outlineVariant.toArgb())
        body.visibility = if (value) VISIBLE else GONE
        detailedHandle.visibility = if (iconOnly) GONE else VISIBLE
        minimalHandleIcon.visibility = if (iconOnly) VISIBLE else GONE
        microphone.setIconOnly(iconOnly)
        microphone.layoutParams = microphone.layoutParams.apply { width = dp(if (iconOnly) 48 else 96) }
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
        val connected = current.membersLabel != null
        speakerStrip.visibility = if (connected) VISIBLE else GONE
        localPanel.visibility = if (connected) GONE else VISIBLE
        if (connected) {
            if (current.speakers != previous?.speakers || current.moreSpeakers != previous.moreSpeakers) {
                speakerStrip.render(current.speakers, current.moreSpeakers)
            }
        } else {
            localLines[0].text = current.localSummary
            localLines[1].text = current.localActivity
        }
        updateHeader()
    }

    private fun updateHeader() {
        val current = details
        val preview = current?.speakers?.firstOrNull().orEmpty()
        if (speakerPreview.text != preview) speakerPreview.text = preview
        val visible = !expanded && preview.isNotEmpty()
        speakerPreview.visibility = if (visible) VISIBLE else GONE
        speakerPreviewIcon.visibility = speakerPreview.visibility
        if (iconOnly) {
            minimalHandleIcon.setImageResource(if (visible) R.drawable.ic_lucide_audio_lines else R.drawable.ic_ptt_grip)
            minimalHandleIcon.setColorFilter(if (visible) colors.primary.toArgb() else colors.onSurfaceVariant.toArgb())
        }
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
