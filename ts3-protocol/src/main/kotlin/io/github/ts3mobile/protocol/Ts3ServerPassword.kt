package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.util.Ts3Crypt

/** ts3j sends `client_server_password` verbatim, so it must already be the TeamSpeak Base64(SHA1) form. */
internal fun String.toTs3jServerPassword(): String? = takeIf(String::isNotBlank)?.let(Ts3Crypt::hashPassword)
