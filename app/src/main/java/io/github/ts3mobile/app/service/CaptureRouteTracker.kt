package io.github.ts3mobile.app.service

internal class CaptureRouteTracker {
    data class Change(val inputChanged: Boolean)

    private var selectedRoute: Int? = null
    private var inputDevice: Int? = null

    @Synchronized fun update(
        selectedRoute: Int,
        inputDevice: Int?,
    ): Change? {
        val changed = this.selectedRoute != selectedRoute || this.inputDevice != inputDevice
        val inputChanged = this.inputDevice != inputDevice
        this.selectedRoute = selectedRoute
        this.inputDevice = inputDevice
        return if (changed) Change(inputChanged) else null
    }
}
