package io.github.ts3mobile.app.service

data class BackgroundRuntimeState(
    val keepCpuAwake: Boolean = false,
    /**
     * The app currently holds an active wake-lock request. It is not a promise that
     * the CPU stays awake: the system or a vendor battery policy can disable the
     * lock for this app, and Android exposes no public API that reports that state,
     * so a requested lock must never be presented as a granted one.
     */
    val cpuLockHeld: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val powerSaveMode: Boolean = false,
    val deviceIdle: Boolean = false,
)
