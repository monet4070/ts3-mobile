package io.github.ts3mobile.app.appearance

internal enum class ThemeMode(val stored: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system"),
    ;

    fun isDark(systemDark: Boolean): Boolean = this == DARK || (this == SYSTEM && systemDark)
}

internal enum class ThemePreferenceStatus { LOADING, SAVING, SAVED, READ_FAILED, WRITE_FAILED }

internal data class ThemePreferenceState(
    val mode: ThemeMode = ThemeMode.LIGHT,
    val status: ThemePreferenceStatus = ThemePreferenceStatus.LOADING,
) {
    val busy: Boolean get() = status == ThemePreferenceStatus.LOADING || status == ThemePreferenceStatus.SAVING
}

internal interface ThemePreferenceStore {
    suspend fun read(): ThemeMode

    suspend fun write(
        mode: ThemeMode,
        reset: Boolean = false,
    )
}
