package io.github.ts3mobile.app.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkDraftTest {
    private val original =
        ServerBookmark(
            id = "fixture",
            revision = 1,
            name = "Saved server",
            host = "voice.example.com",
            defaultChannelId = 42,
            rememberPasswords = true,
            password = "old-server",
            channelPassword = "old-channel",
        )
    private val selection = BookmarkChannelSelection("old-session", 42)
    private val draft = BookmarkDraft(original, selection = selection)

    @Test fun changingEitherPartOfTheEndpointClearsBothSecretsAndTheOldChannel() {
        val edits =
            listOf(draft.withEndpoint(host = "other.example.com"), draft.withEndpoint(port = "9988"), draft.withEndpoint(host = " "))
        edits.forEach { edited ->
            val saved = edited.toBookmark()
            assertEquals("", saved.password)
            assertEquals("", saved.channelPassword)
            assertNull(saved.defaultChannelId)
            assertNull(edited.selection)
            assertTrue(saved.rememberPasswords)
            assertTrue(edited.clearedPasswords)
        }
    }

    @Test fun returningFromAnInvalidPortOrADifferentHostDoesNotRestoreTheSecrets() {
        val portEdits = listOf("", "0", "65536", "not a port").map { draft.withEndpoint(port = it) }
        portEdits.forEach { assertTrue(it.toBookmark().toConfig().validationError() != null) }
        val restored =
            portEdits.map { it.withEndpoint(port = "9987") } +
                draft.withEndpoint(host = "other.example.com").withEndpoint(host = original.host).withChannel("42")
        restored.forEach { edited ->
            assertNull(edited.toBookmark().toConfig().validationError())
            assertEquals("", edited.entry.password)
            assertEquals("", edited.entry.channelPassword)
            assertNull(edited.selection)
        }
        assertEquals(original, draft.toBookmark())
    }

    @Test fun equivalentTargetsAndUnrelatedEditsRetainTheSecretsAndSelection() {
        val renamed = draft.copy(entry = original.copy(name = "Renamed", nickname = "New nickname"))
        val edited = renamed.withEndpoint(host = " VOICE.EXAMPLE.COM ", port = "09987").withChannel("00042")
        assertEquals(original.port, edited.toBookmark().port)
        assertEquals(original.defaultChannelId, edited.toBookmark().defaultChannelId)
        assertEquals(original.password, edited.entry.password)
        assertEquals(original.channelPassword, edited.entry.channelPassword)
        assertEquals(selection, edited.selection)
        assertFalse(edited.clearedPasswords)
    }

    @Test fun editingTheChannelClearsOnlyItsSecretAndKeepsOnlyANewMatchingSelection() {
        val newSelection = BookmarkChannelSelection("new-session", 43)
        val changed = draft.withChannel("43", newSelection)
        assertEquals(newSelection, changed.selection)
        val manualEdits = listOf("43", "", "0", "2147483648").map { draft.withChannel(it) }
        (manualEdits + changed).forEach { edited ->
            assertEquals(original.password, edited.entry.password)
            assertEquals("", edited.entry.channelPassword)
            assertTrue(edited.clearedPasswords)
        }
        manualEdits.forEach { assertNull(it.selection) }
        assertEquals("", changed.withChannel("42").entry.channelPassword)
        assertNull(changed.withChannel("", selection = null).toBookmark().defaultChannelId)
    }

    @Test fun explicitlyClearingAnEmptyChannelDropsTheConnectionTokenAndChannelPassword() {
        val noChannel = BookmarkDraft(original.copy(defaultChannelId = null), selection = BookmarkChannelSelection("old-session", null))
        val cleared = noChannel.withChannel("", selection = null)
        assertNull(cleared.selection)
        assertEquals("", cleared.entry.channelPassword)
        assertEquals(original.password, cleared.entry.password)
    }

    @Test fun turningOffStorageDoesNotReviveSecretsWhenReenabled() {
        val disabled = draft.withPasswordStorage(false)
        assertFalse(disabled.entry.rememberPasswords)
        val reenabled = disabled.withPasswordStorage(true)
        assertTrue(reenabled.entry.rememberPasswords)
        assertEquals("", reenabled.entry.password)
        assertEquals("", reenabled.entry.channelPassword)
        assertEquals(selection, reenabled.selection)
    }

    @Test fun newlyEnteredSecretsSurviveSavingForTheNewEndpointEvenWithTheSameChannelId() {
        val edited = draft.withEndpoint(host = "other.example.com").withChannel("42")
        val withNewPasswords = edited.copy(entry = edited.entry.copy(password = "new-server", channelPassword = "new-channel"))
        val saved = BookmarkCodec.decode(BookmarkCodec.encode(BookmarkCodec.save(listOf(original), withNewPasswords.toBookmark()))).single()
        assertEquals(original.revision + 1, saved.revision)
        assertEquals("other.example.com", saved.host)
        assertEquals(original.defaultChannelId, saved.defaultChannelId)
        assertEquals("new-server", saved.password)
        assertEquals("new-channel", saved.channelPassword)
    }
}
