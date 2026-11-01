package io.github.ts3mobile.app.bookmarks

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID

/** Scratch DataStores only; no production bookmark/form/history file or key is deleted. */
class BookmarkVaultTest {
    @Test fun keystoreCiphertextRoundtripSurvivesReopenAndPasswordOptOut() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "bookmark-${UUID.randomUUID()}.preferences_pb")
            var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                var store = PreferenceDataStoreFactory.create(scope = scope) { file }
                var vault = BookmarkVault(context, store)
                val requested =
                    ServerBookmark(
                        "fixture",
                        name = "Fixture",
                        host = "voice.example.com",
                        rememberPasswords = true,
                        password = "fixture-password-secret",
                        channelPassword = "fixture-channel-secret",
                    )
                val saved = vault.save(requested).single()
                assertFalse(file.readText().contains("fixture-password-secret"))
                assertFalse(file.readText().contains("voice.example.com"))
                scope.coroutineContext.job.cancelAndJoin()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                store = PreferenceDataStoreFactory.create(scope = scope) { file }
                vault = BookmarkVault(context, store)
                assertEquals(saved, vault.read().single())
                val cleared = vault.save(saved.copy(rememberPasswords = false)).single()
                assertEquals("", vault.read().single().password)
                assertEquals("", vault.read().single().channelPassword)
                vault.remove(cleared)
                assertTrue(vault.read().isEmpty())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                file.delete()
            }
        }

    @Test fun corruptCiphertextIsVisibleAndCannotBeOverwrittenBySaving() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "bookmark-corrupt-${UUID.randomUUID()}.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope) { file }
                val key = stringPreferencesKey("encrypted_bookmarks")
                store.edit { it[key] = "v1:invalid:invalid" }
                val vault = BookmarkVault(context, store)
                var failed = false
                try {
                    vault.save(
                        ServerBookmark(
                            "fixture",
                            name = "Fixture",
                            host = "voice.example.com",
                        ),
                    )
                } catch (_: Exception) {
                    failed = true
                }
                assertTrue(failed)
                assertEquals("v1:invalid:invalid", store.data.first()[key])
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                file.delete()
            }
        }

    @Test fun bookmarkCiphertextIsExcludedFromBackupAndTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        fun exclusions(id: Int): Int {
            val parser = context.resources.getXml(id)
            var count = 0
            parser.use {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG &&
                        parser.name == "exclude" &&
                        parser.getAttributeValue(
                            null,
                            "path",
                        ) == "datastore/ts3_bookmarks.preferences_pb"
                    ) {
                        count++
                    }
                    parser.next()
                }
            }
            return count
        }
        assertEquals(1, exclusions(R.xml.backup_rules))
        assertEquals(2, exclusions(R.xml.data_extraction_rules))
    }
}
