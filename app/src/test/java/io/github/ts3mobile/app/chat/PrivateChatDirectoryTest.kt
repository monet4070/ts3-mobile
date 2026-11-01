package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.Ts3Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateChatDirectoryTest {
    private val peer = Ts3Participant(8, 10, "Same name", false, false, false, "uid-a")

    @Test fun duplicateIdsAndDuplicateUidsNeverProduceAnAmbiguousTarget() {
        val directory = PrivateChatDirectory()
        directory.update(1, listOf(peer, peer.copy(id = 9)), 7)
        assertNull(directory.target(ChatConversationKey.User("uid-a")))
        directory.update(1, listOf(peer, peer.copy(uniqueIdentifier = "uid-b")), 7)
        assertNull(directory.keyFor(8))
        directory.update(1, listOf(peer), 7)
        assertEquals(TextTarget.Private(8, "uid-a"), directory.target(ChatConversationKey.User("uid-a")))
    }

    @Test fun fallbackKeysDoNotRebindAfterLeaveRejoinOrAttemptChange() {
        val directory = PrivateChatDirectory()
        val unknown = peer.copy(uniqueIdentifier = "")
        directory.update(1, listOf(unknown), 7)
        val first = directory.keyFor(8)!!
        directory.update(1, emptyList(), 7)
        directory.update(1, listOf(unknown), 7)
        assertNull(directory.target(first))
        assertNotEquals(first, directory.keyFor(8))
        val second = directory.keyFor(8)!!
        directory.update(2, listOf(unknown), 7)
        assertNull(directory.target(second))
    }

    @Test fun unknownIncomingOccupancyCannotBecomeSendableFromALaterSnapshot() {
        val directory = PrivateChatDirectory()
        directory.update(1, emptyList(), 7)
        val unknown = directory.fallbackKey(8)
        directory.update(1, listOf(peer), 7)
        assertNull(directory.target(unknown))
    }

    @Test fun identityIsExactAndUnboundedIdentityIsNeverTruncatedIntoAnotherKey() {
        val directory = PrivateChatDirectory()
        val longUid = "u".repeat(257)
        directory.update(1, listOf(peer.copy(uniqueIdentifier = longUid), peer.copy(id = 7)), 7)
        assertNull(directory.keyFor(7))
        assertNull(directory.target(ChatConversationKey.User(longUid.take(256))))
        assertEquals(null, "  ".chatUid())
        assertEquals(" exact ", " exact ".chatUid())
        directory.clear()
        assertNull(directory.keyFor(8))
    }
}
