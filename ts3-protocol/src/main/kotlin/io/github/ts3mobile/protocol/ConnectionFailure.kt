package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.CommandProcessException
import java.io.IOException
import java.util.concurrent.TimeoutException

/** Closed failure facts produced by the protocol, never inferred again by the app. */
enum class ConnectionFailureCategory {
    UNCLASSIFIED,
    CLONES_LIMIT,
}

/** Synthesized locally when the server closes a connection before the connect attempt finishes. */
internal class ServerDisconnectedDuringConnectException(
    val reasonId: Int,
    val retryable: Boolean,
) : IOException("Server closed the connection ($reasonId)")

fun Throwable.connectionFailureCategory(): ConnectionFailureCategory = classifyConnectionFailure().category

fun Throwable.isRetryableConnectionFailure(): Boolean = classifyConnectionFailure().retryable

/** Matches the known upstream ts3j RSA handshake array-width exception that succeeds on retry. */
internal fun Throwable.isTs3jHandshakeWidthFailure(): Boolean {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current.isDirectTs3jHandshakeWidthFailure()) return true
        current = current.cause
    }
    return false
}

/** The first protocol error takes precedence over network wrappers and deeper causes. */
private fun Throwable.classifyConnectionFailure(): ClassifiedFailure {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    var networkFailure = false
    while (current != null && visited.add(current)) {
        if (current is CommandProcessException) {
            val rejectionCode = (current as? Ts3jHandshakeRejectedException)?.serverErrorId
            val clones =
                if (rejectionCode != null) {
                    rejectionCode == CLONES_LIMIT_CODE
                } else {
                    current.message?.contains(CLONES_LIMIT_TEXT, ignoreCase = true) == true
                }
            return ClassifiedFailure(
                if (clones) ConnectionFailureCategory.CLONES_LIMIT else ConnectionFailureCategory.UNCLASSIFIED,
                false,
            )
        }
        if (current is CommandException) return ClassifiedFailure(ConnectionFailureCategory.UNCLASSIFIED, false)
        if (current is ServerDisconnectedDuringConnectException) {
            return ClassifiedFailure(ConnectionFailureCategory.UNCLASSIFIED, current.retryable)
        }
        if (current is IOException ||
            current is TimeoutException ||
            current is InterruptedException ||
            current.isDirectTs3jHandshakeWidthFailure()
        ) {
            networkFailure = true
        }
        current = current.cause
    }
    return ClassifiedFailure(ConnectionFailureCategory.UNCLASSIFIED, networkFailure)
}

private fun Throwable.isDirectTs3jHandshakeWidthFailure(): Boolean =
    this is ArrayIndexOutOfBoundsException &&
        stackTrace.any { it.className.startsWith(TS3J_CONNECTING_HANDLER_CLASS) }

private data class ClassifiedFailure(val category: ConnectionFailureCategory, val retryable: Boolean)

private const val CLONES_LIMIT_CODE = 521
private const val CLONES_LIMIT_TEXT = "too many clones already connected"

private const val TS3J_CONNECTING_HANDLER_CLASS =
    "com.github.manevolent.ts3j.protocol.packet.handler.client.LocalClientHandlerConnecting"
