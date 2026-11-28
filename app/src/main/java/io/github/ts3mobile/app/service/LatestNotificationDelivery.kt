package io.github.ts3mobile.app.service

internal class LatestNotificationDelivery<Content : Any>(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val deliver: (Content) -> Unit,
    private val confirmed: (Content) -> Boolean,
    private val onUndelivered: () -> Unit,
) {
    private var desired: Content? = null
    private var attempts = 0
    private var scheduled: Runnable? = null

    fun offer(
        content: Content,
        alreadyPosted: Boolean = false,
    ) {
        desired = content
        attempts = if (alreadyPosted) 1 else 0
        scheduleOnce(if (alreadyPosted) RETRY_MS else COALESCE_MS)
    }

    fun clear() {
        scheduled?.let(cancel)
        scheduled = null
        desired = null
    }

    private fun reconcile() {
        val content = desired ?: return
        when {
            attempts > 0 && confirmed(content) -> desired = null
            attempts >= MAX_ATTEMPTS -> {
                desired = null
                onUndelivered()
            }
            else -> {
                attempts++
                deliver(content)
                scheduleOnce(RETRY_MS)
            }
        }
    }

    private fun scheduleOnce(delayMs: Long) {
        if (scheduled != null) return
        val task =
            object : Runnable {
                override fun run() {
                    if (scheduled !== this) return
                    scheduled = null
                    reconcile()
                }
            }
        scheduled = task
        schedule(task, delayMs)
    }

    companion object {
        const val COALESCE_MS = 350L
        const val RETRY_MS = 600L
        const val MAX_ATTEMPTS = 3
    }
}
