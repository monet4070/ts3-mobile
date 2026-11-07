package io.github.ts3mobile.app.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Requires external runner to kill the process at an acknowledged durable boundary. */
class IdentityProcessCrashTest {
    @Test fun externalProcessBoundaryRecoversOrPreservesTheOriginalFailureEvidence() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val stage = arguments.getString("identityCrashStage")
            assumeTrue("Requires the explicit disposable-emulator process-crash runner", stage != null)
            val point = checkNotNull(arguments.getString("identityCrashPoint"))
            require(point in POINTS)
            val fixture = IdentityCrashFixture(checkNotNull(arguments.getString("identityCrashId")))
            var verified = false
            try {
                when (stage) {
                    "seed" -> fixture.seed()
                    "crash" -> {
                        val revision = fixture.storage.snapshot().revision
                        val candidate = fixture.replacement()
                        IdentityStore(CrashBoundaryStore(fixture, point), fixture.cipher) { fixture.file.exists() }
                            .replace(candidate, revision)
                        error("The selected crash boundary was not reached")
                    }
                    "verify" -> {
                        fixture.verify(point)
                        verified = true
                    }
                    else -> error("Unknown external fixture stage")
                }
            } finally {
                fixture.close(remove = verified)
            }
        }

    private class CrashBoundaryStore(private val fixture: IdentityCrashFixture, private val point: String) : DataStore<Preferences> {
        override val data = fixture.delegate.data
        private var calls = 0

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val call = ++calls
            if (call == 1 && point == "before-transaction") park()
            if (call == 2 && point == "before-cleanup") park()
            val result =
                fixture.delegate.updateData {
                    if (call == 1 && point == "during-transaction") park()
                    transform(it)
                }
            if (call == 1 && point in setOf("after-transaction", "damaged-active", "damaged-both", "missing-key")) {
                if (point.startsWith("damaged-")) fixture.damage(both = point == "damaged-both")
                if (point == "missing-key") fixture.deleteKey()
                park()
            }
            if (call == 2 && point == "after-cleanup") park()
            return result
        }

        private suspend fun park(): Nothing {
            // No material or exception details. External runner kills this exact target PID.
            fixture.ready.writeText("IDENTITY_CRASH_BOUNDARY_READY\n")
            CompletableDeferred<Unit>().await()
            error("External crash fixture unexpectedly resumed")
        }
    }

    private companion object {
        val POINTS =
            setOf(
                "before-transaction",
                "during-transaction",
                "after-transaction",
                "before-cleanup",
                "after-cleanup",
                "damaged-active",
                "damaged-both",
                "missing-key",
            )
    }
}
