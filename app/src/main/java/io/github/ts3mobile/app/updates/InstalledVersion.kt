package io.github.ts3mobile.app.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.ts3mobile.app.BuildConfig
import java.security.MessageDigest

@Suppress("DEPRECATION")
internal fun installedAppVersion(context: Context): InstalledAppVersion {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, flags)
    val signatures =
        if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
    return InstalledAppVersion(
        packageInfo.versionName ?: BuildConfig.VERSION_NAME,
        PackageInfoCompat.getLongVersionCode(packageInfo),
        context.packageName,
        Build.VERSION.SDK_INT,
        signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet(),
    )
}
