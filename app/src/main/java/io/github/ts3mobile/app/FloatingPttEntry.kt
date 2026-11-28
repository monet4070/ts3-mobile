package io.github.ts3mobile.app

import android.content.Context
import android.content.Intent
import io.github.ts3mobile.app.probe.FloatingPttActivity

internal fun Context.floatingPttSettingsIntent(): Intent = Intent(this, FloatingPttActivity::class.java)

internal fun Context.floatingPttRecallIntent(): Intent =
    floatingPttSettingsIntent()
        .setAction(FloatingPttActivity.ACTION_RECALL)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
