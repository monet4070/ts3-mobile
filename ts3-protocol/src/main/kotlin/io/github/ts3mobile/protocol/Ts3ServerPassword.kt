package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.util.Ts3Crypt

internal fun String.toTs3jServerPassword(): String? =
    takeIf(String::isNotBlank)?.let(Ts3Crypt::hashPassword)
