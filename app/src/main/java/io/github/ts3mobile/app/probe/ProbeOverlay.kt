package io.github.ts3mobile.app.probe

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ProbeOverlay(
    private val context: Context,
    private val press: () -> Long?,
    private val release: (Long) -> Unit,
    private val stop: () -> Unit,
    private val details: () -> ProbeOverlayDetails,
    private val windowLost: () -> Unit = stop,
    private val holdLabel: Int,
    private val selectMode: ((MicrophoneMode) -> Unit)? = null,
    private val minimalStyle: Boolean = false,
) {
    private val windowContext by lazy {
        if (Build.VERSION.SDK_INT >= 30) {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
    }
    private val manager by lazy { windowContext.getSystemService(WindowManager::class.java) }
    private val preferences by lazy { context.floatingPttPreferences() }
    private var dock: OverlayDock? = null
    private var card: ProbeOverlayCard? = null
    private var layout: WindowManager.LayoutParams? = null
    private var active: Long? = null
    private var statusLabel = R.string.ptt_overlay_idle
    private var statusActive = false
    private var statusTransmitting = false

    fun show(): Boolean {
        if (card != null) return true
        return try {
            val view =
                ProbeOverlayCard(
                    localizedViewContext(),
                    holdLabel,
                    stop,
                    ::cancel,
                    ::lost,
                    ::displayChanged,
                    selectMode?.let { ::changeMode },
                    minimalStyle,
                )
            val params = buildLayout()
            attachGestures(view, params)
            card = view
            layout = params
            view.renderDetails(details())
            view.render(statusLabel, statusActive, statusTransmitting)
            placeAtDock(view, params)
            manager.addView(view, params)
            true
        } catch (_: RuntimeException) {
            card = null
            layout = null
            Log.w("TS3_PTT", "Overlay unavailable")
            false
        }
    }

    private fun localizedViewContext(): Context {
        val appLocales = context.resources.configuration.locales
        if (windowContext.resources.configuration.locales == appLocales) return windowContext
        return windowContext.createConfigurationContext(Configuration().apply { setLocales(appLocales) })
    }

    private fun buildLayout() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            @SuppressLint("RtlHardcoded")
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(
        view: ProbeOverlayCard,
        params: WindowManager.LayoutParams,
    ) {
        view.microphone.setOnTouchListener { target, event ->
            if (view.mode != null && view.mode != MicrophoneMode.PUSH_TO_TALK) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (event.pointerCount == 1 && target.isShown && active == null) active = press()
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP, MotionEvent.ACTION_OUTSIDE -> cancel()
                MotionEvent.ACTION_MOVE -> if (event.x !in 0f..target.width.toFloat() || event.y !in 0f..target.height.toFloat()) cancel()
            }
            true
        }
        view.handle.setOnClickListener {
            if (active == null) {
                view.expand(!view.expanded)
                placeAtDock(view, params)
                update(view, params)
            }
        }
        val slop = ViewConfiguration.get(windowContext).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var initial = OverlayPosition(0, 0)
        var dragging = false
        var singleTouch = false
        view.handle.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    downX = event.rawX
                    downY = event.rawY
                    initial = OverlayPosition(params.x, params.y)
                    dragging = false
                    singleTouch = event.pointerCount == 1
                }
                MotionEvent.ACTION_MOVE ->
                    if (singleTouch) {
                        val deltaX = event.rawX - downX
                        val deltaY = event.rawY - downY
                        dragging = dragging || maxOf(abs(deltaX), abs(deltaY)) > slop
                        if (dragging) {
                            val position = OverlayPosition(initial.left + deltaX.roundToInt(), initial.top + deltaY.roundToInt())
                            val clamped = limits(view, safeBounds()).clamp(position)
                            params.x = clamped.left
                            params.y = clamped.top
                            update(view, params)
                        }
                    }
                MotionEvent.ACTION_UP ->
                    if (singleTouch) {
                        if (dragging) settle(view, params) else target.performClick()
                        singleTouch = false
                    }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && singleTouch) settle(view, params)
                    singleTouch = false
                }
            }
            true
        }
    }

    private fun changeMode(mode: MicrophoneMode) {
        if (card?.mode == mode) return
        cancel()
        selectMode?.invoke(mode)
    }

    private fun settle(
        view: ProbeOverlayCard,
        params: WindowManager.LayoutParams,
    ) {
        val anchor = limits(view, safeBounds()).snap(OverlayPosition(params.x, params.y))
        dock = anchor
        preferences.edit().putBoolean("right", anchor.right).putFloat("height", anchor.height).apply()
        placeAtDock(view, params)
        update(view, params)
    }

    private fun placeAtDock(
        view: ProbeOverlayCard,
        params: WindowManager.LayoutParams,
    ) {
        val bounds = safeBounds()
        params.width = minOf(view.preferredWidth(), bounds.width().coerceAtLeast(1))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height().coerceAtLeast(1), View.MeasureSpec.AT_MOST),
        )
        params.height = view.measuredHeight
        val anchor = dock ?: OverlayDock(preferences.getBoolean("right", true), preferences.getFloat("height", .5f))
        val position = limits(view, bounds).place(anchor)
        params.x = position.left
        params.y = position.top
    }

    private fun limits(
        view: ProbeOverlayCard,
        bounds: Rect,
    ): OverlayLimits =
        OverlayLimits(bounds.left, bounds.top, bounds.right, bounds.bottom, view.measuredWidth, view.measuredHeight, view.headerHeight())

    @Suppress("DEPRECATION")
    private fun safeBounds(): Rect {
        val margin = windowContext.probeDp(10)
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val unsafeInsets = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(unsafeInsets)
            return Rect(
                insets.left + margin,
                insets.top + margin,
                metrics.bounds.width() - insets.right - margin,
                metrics.bounds.height() - insets.bottom - margin,
            )
        }
        val size = Point().also { manager.defaultDisplay.getSize(it) }
        return Rect(margin, margin + windowContext.probeDp(24), size.x - margin, size.y - margin)
    }

    private fun displayChanged(view: ProbeOverlayCard) {
        if (card === view) {
            val params = layout ?: return
            cancel()
            placeAtDock(view, params)
            update(view, params)
        }
    }

    private fun update(
        view: ProbeOverlayCard,
        params: WindowManager.LayoutParams,
    ) {
        if (card !== view) return
        try {
            manager.updateViewLayout(view, params)
        } catch (_: RuntimeException) {
            lost(view)
        }
    }

    private fun lost(view: ProbeOverlayCard) {
        if (card === view) {
            hide()
            windowLost()
        }
    }

    fun renderLabel(
        label: Int,
        activeTone: Boolean = false,
        transmitting: Boolean = false,
    ) {
        statusLabel = label
        statusActive = activeTone
        statusTransmitting = transmitting
        card?.let {
            it.renderDetails(details())
            it.render(label, activeTone, transmitting)
        }
    }

    fun hide() {
        val view = card ?: return
        card = null
        layout = null
        cancel()
        try {
            manager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            Log.d("TS3_PTT", "Overlay already removed")
        }
    }

    private fun cancel() {
        val token = active ?: return
        active = null
        release(token)
    }
}
