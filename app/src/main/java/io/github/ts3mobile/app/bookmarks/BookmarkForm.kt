package io.github.ts3mobile.app.bookmarks

import io.github.ts3mobile.app.ConnectionFormState

/** Choosing a bookmark replaces every endpoint-dependent field, even for the same host. */
internal fun ConnectionFormState.withSelectedBookmark(entry: ServerBookmark): ConnectionFormState =
    copy(
        host = entry.host,
        port = entry.port.toString(),
        nickname = entry.nickname,
        password = entry.password,
        submitted = false,
    )
