package io.github.ts3mobile.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.ts3mobile.app.MainActivity

/** The URI distinguishes PendingIntents. Only an unguessable, memory-only capability leaves the service. */
internal object PrivateMessageNotificationIntent {
    private const val ACTION = "io.github.ts3mobile.app.OPEN_PRIVATE_MESSAGES"
    private const val SCHEME = "ts3mobile-message"

    fun create(
        context: Context,
        token: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .setData(Uri.Builder().scheme(SCHEME).authority("open").appendPath(token).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun token(intent: Intent?): String? {
        if (intent?.action != ACTION) return null
        val uri = intent.data ?: return null
        if (uri.scheme != SCHEME || uri.authority != "open" || uri.query != null || uri.fragment != null) return null
        return uri.pathSegments.singleOrNull()?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
    }
}
