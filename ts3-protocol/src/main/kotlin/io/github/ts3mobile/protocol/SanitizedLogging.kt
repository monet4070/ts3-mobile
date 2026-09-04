package io.github.ts3mobile.protocol

/**
 * Failure-type summary safe for logging: class names only, never exception
 * messages, which may carry server endpoints or credentials.
 */
public fun Throwable.sanitizedFailureTypes(): String {
    val types = mutableListOf<String>()
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current) && types.size < 4) {
        types += current::class.java.simpleName.ifBlank { "Throwable" }
        current = current.cause
    }
    return types.joinToString(" <- ")
}
