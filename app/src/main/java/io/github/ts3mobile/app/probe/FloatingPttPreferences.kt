package io.github.ts3mobile.app.probe

import android.content.Context

internal fun Context.floatingPttPreferences() = getSharedPreferences("floating_ptt_overlay", Context.MODE_PRIVATE)

internal var Context.autoShowFloatingPtt: Boolean
    get() = floatingPttPreferences().getBoolean("auto_show", false)
    set(value) = floatingPttPreferences().edit().putBoolean("auto_show", value).apply()

internal enum class FloatingPttStyle { STANDARD, MINIMAL }

internal var Context.floatingPttStyle: FloatingPttStyle
    get() = if (floatingPttPreferences().getBoolean("minimal_style", false)) FloatingPttStyle.MINIMAL else FloatingPttStyle.STANDARD
    set(value) = floatingPttPreferences().edit().putBoolean("minimal_style", value == FloatingPttStyle.MINIMAL).apply()
