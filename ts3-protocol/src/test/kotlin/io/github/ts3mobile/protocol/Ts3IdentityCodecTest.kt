package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.identity.LocalIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class Ts3IdentityCodecTest {
    @Test fun iniRoundTripPreservesUidPrivateKeyOffsetAndSecurityWithoutImprovingIt() {
        val identity = LocalIdentity.generateNew(3)
        val before = Ts3IdentityCodec.inspect(identity.export())
        val after = Ts3IdentityCodec.inspect(Ts3IdentityCodec.decode(identity.export()).export())
        assertEquals(before, after)
        assertEquals(identity.uid.toBase64(), before.uid)
        assertEquals(identity.keyOffset, before.keyOffset)
        assertEquals(identity.securityLevel, before.securityLevel)
        assertEquals(identity.privateKey, Ts3IdentityCodec.decode(identity.export()).privateKey)
        assertFalse(before.toString().contains(before.uid))
    }

    @Test fun summaryUidIsTheStableTwentyByteBase64IdentifierUsedByConnections() {
        val identity = LocalIdentity.generateNew(0)
        val before = Ts3IdentityCodec.inspect(identity.export())
        assertEquals(20, java.util.Base64.getDecoder().decode(before.uid).size)
        assertEquals(28, before.uid.length)
        assertFalse(before.uid.contains("com.github"))
        identity.keyOffset += 1
        assertEquals(before.uid, Ts3IdentityCodec.inspect(identity.export()).uid)
    }

    @Test fun mismatchedPublicAndPrivateKeysAreRejected() {
        val first = LocalIdentity.generateNew(0)
        val second = LocalIdentity.generateNew(0)
        val mismatch = LocalIdentity(first.publicKey, second.privateKey).export()
        assertThrows(InvalidIdentityMaterial::class.java) { Ts3IdentityCodec.inspect(mismatch) }
    }

    @Test fun malformedOversizedAndNegativeOffsetMaterialHasOneSanitizedInputFailure() {
        listOf("fixture-private-material", "x".repeat(65_537), "中".repeat(30_000)).forEach {
            val error = assertThrows(InvalidIdentityMaterial::class.java) { Ts3IdentityCodec.inspect(it) }
            assertEquals("Invalid identity material", error.message)
        }
        val identity = LocalIdentity.generateNew(0)
        identity.keyOffset = -1
        assertThrows(InvalidIdentityMaterial::class.java) { Ts3IdentityCodec.inspect(identity.export()) }
    }
}
