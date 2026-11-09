package io.github.ts3mobile.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal const val PROJECT_REPOSITORY = "monet4070/ts3-mobile"
internal const val PROJECT_REPOSITORY_URL = "https://github.com/$PROJECT_REPOSITORY"

internal fun openProjectRepository(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_REPOSITORY_URL)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.repository_open_failed, Toast.LENGTH_SHORT).show()
    }
}
