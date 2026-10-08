package io.github.ts3mobile.app.service

data class BackgroundRuntimeState(
    val keepCpuAwake: Boolean = false,
    val cpuLockHeld: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val powerSaveMode: Boolean = false,
    val deviceIdle: Boolean = false,
)
