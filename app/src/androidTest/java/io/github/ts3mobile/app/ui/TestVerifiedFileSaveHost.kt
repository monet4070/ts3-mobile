package io.github.ts3mobile.app.ui

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import io.github.ts3mobile.app.documents.VerifiedFileSaves
import io.github.ts3mobile.app.documents.copyVerifiedDocument
import io.github.ts3mobile.app.documents.removeVerifiedStaging
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

@Composable
internal fun TestVerifiedFileSaveHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saves =
        remember(context, scope) {
            VerifiedFileSaves<Uri>(
                scope,
                copy = { source, target ->
                    runInterruptible(Dispatchers.IO) {
                        copyVerifiedDocument(source) { context.contentResolver.openOutputStream(target, "wt") }
                    }
                },
                discard = ::removeVerifiedStaging,
            )
        }
    DisposableEffect(saves) { onDispose { saves.close() } }
    val owner = LocalActivityResultRegistryOwner.current ?: remember { NoPickerOwner() }
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        VerifiedFileSaveHost(saves, context.cacheDir.resolve("fixture-verified-files"), content)
    }
}

private class NoPickerOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) = error("This fixture requires a document picker result owner")
        }
}

@Composable
internal fun TestVerifiedFileTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    Ts3MobileTheme(darkTheme = darkTheme) { TestVerifiedFileSaveHost(content) }
}
