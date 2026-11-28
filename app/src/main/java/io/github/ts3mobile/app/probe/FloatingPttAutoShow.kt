package io.github.ts3mobile.app.probe

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import io.github.ts3mobile.app.service.ConnectedPttPreparation
import io.github.ts3mobile.app.service.TeamSpeakService

internal class FloatingPttAutoShow(
    private val owner: ComponentActivity,
    private val session: () -> TeamSpeakService.SessionBinder?,
) {
    private val rootBack =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val preparation = owner.prepareFloatingPttDeparture(session(), leavingMainActivity = true)
                if (preparation != null) {
                    if (owner.moveTaskToBack(false)) return
                    preparation.disarm()
                }
                isEnabled = false
                try {
                    owner.onBackPressedDispatcher.onBackPressed()
                } finally {
                    refreshRootBack()
                }
            }
        }

    init {
        owner.onBackPressedDispatcher.addCallback(owner, rootBack)
    }

    fun refreshRootBack() {
        rootBack.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && owner.autoShowFloatingPtt && owner.isTaskRoot
    }
}

internal fun ComponentActivity.prepareFloatingPttDeparture(
    binder: TeamSpeakService.SessionBinder?,
    leavingMainActivity: Boolean = false,
): ConnectedPttPreparation? {
    if (!autoShowFloatingPtt || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
    val preparation = binder?.connectedPttPreparation ?: return null
    return preparation.takeIf {
        ConnectedPttProbeHost(this, it, binder.state).arm(leavingMainActivity, userDeparture = true)
    }
}
