package io.github.ts3mobile.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R

internal enum class UiIcon(
    @DrawableRes val resourceId: Int,
) {
    Server(R.drawable.ic_lucide_server),
    Channel(R.drawable.ic_lucide_hash),
    User(R.drawable.ic_lucide_user_round),
    Lock(R.drawable.ic_lucide_lock_keyhole),
    Eye(R.drawable.ic_lucide_eye),
    EyeOff(R.drawable.ic_lucide_eye_off),
    Connect(R.drawable.ic_lucide_plug),
    Power(R.drawable.ic_lucide_power),
    Volume(R.drawable.ic_lucide_volume_2),
    VolumeOff(R.drawable.ic_lucide_volume_x),
    Headphones(R.drawable.ic_lucide_headphones),
    Audio(R.drawable.ic_lucide_audio_lines),
    Mic(R.drawable.ic_lucide_mic),
    MicOff(R.drawable.ic_lucide_mic_off),
    Sliders(R.drawable.ic_lucide_sliders_horizontal),
    Users(R.drawable.ic_lucide_users_round),
    Activity(R.drawable.ic_lucide_activity),
    Check(R.drawable.ic_lucide_check),
    Current(R.drawable.ic_lucide_circle_check),
    ChevronDown(R.drawable.ic_lucide_chevron_down),
    ChevronRight(R.drawable.ic_lucide_chevron_right),
    Copy(R.drawable.ic_lucide_copy),
    Shield(R.drawable.ic_lucide_shield_check),
    Radio(R.drawable.ic_lucide_radio),
    ArrowRight(R.drawable.ic_lucide_arrow_right),
    Info(R.drawable.ic_lucide_info),
}

@Composable
internal fun AppIcon(
    icon: UiIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(20.dp),
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon.resourceId), contentDescription, modifier, tint)
}
