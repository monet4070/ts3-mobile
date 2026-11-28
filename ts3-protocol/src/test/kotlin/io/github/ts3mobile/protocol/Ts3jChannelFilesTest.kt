package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.MultiCommand
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Ts3jChannelFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val wildcardIpv4 = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)).hostAddress
    private val differentLoopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)).hostAddress

    @Test fun consumesRealTs3jNotificationParsingOnlyForConfirmedDirectoryAndChannel() {
        StateAwareTeamspeakClientSocket().use { socket ->
            LocalTeamspeakClientSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }
                .set(socket, DatagramSocket(null))
            val files =
                client(socket.channelFileReplies) { request ->
                    assertEquals("ftgetfilelist", request.name)
                    assertEquals("/folder with spaces", request.get("path").value)
                    assertTrue(request.build().contains("path=\\/folder\\swith\\sspaces"))
                    socket.inject("notifyfilelist cid=8 path=\\/folder\\swith\\sspaces name=ignored size=0 type=1")
                    socket.inject("notifyfilelistfinished cid=7 path=\\/other")
                    socket.inject("notifyfilelist cid=7 path=\\/folder\\swith\\sspaces name=first size=3 type=1|name=sub size=0 type=0")
                    socket.inject("notifyfilelistfinished cid=7 path=\\/folder\\swith\\sspaces")
                    TextSendOutcome.Accepted
                }
            assertEquals(
                ChannelFileResult.Success(listOf(ChannelFileEntry("first", false, 3), ChannelFileEntry("sub", true, 0))),
                files.list(7, "/folder with spaces") { true },
            )
        }
    }

    @Test fun commandResultAloneDoesNotCompleteListingAndLateReplyCannotPopulateANewRequest() {
        val replies = Ts3jChannelFileReplies()
        var submitted = 0
        val files =
            client(replies, timeout = 20) {
                submitted++
                TextSendOutcome.Accepted
            }
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.TIMED_OUT), files.list(7, "/") { true })
        assertTrue(replies.accept("notifyfilelistfinished", mapOf("cid" to "7", "path" to "/")))
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.RECONNECT_REQUIRED), files.list(7, "/") { true })
        assertEquals(1, submitted)
    }

    @Test fun distinguishesEmptyDirectoryPermissionsAndInvalidPaths() {
        val replies = Ts3jChannelFileReplies()
        var submitted = 0
        val files =
            client(replies) {
                submitted++
                TextSendOutcome.Rejected(TextRejection.SERVER, if (submitted == 1) 1281 else 2_568)
            }
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_PATH), files.list(7, "/folder/../other") { true })
        assertEquals(ChannelFileResult.Success(emptyList<ChannelFileEntry>()), files.list(7, "/") { true })
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.REJECTED), files.list(7, "/restricted") { true })
        assertEquals(2, submitted)
    }

    @Test fun staleTransferIdIsIgnoredAndVerifiedTcpResponseIsStaged() {
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 3_000
            val key = "synthetic-transfer"
            val payload = byteArrayOf(12, 19, 32)
            val received = CompletableFuture<String>()
            val sender =
                Thread {
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 3_000
                            received.complete(String(socket.getInputStream().readNBytes(key.length), Charsets.US_ASCII))
                            socket.getOutputStream().write(payload)
                        }
                    }.onFailure(received::completeExceptionally)
                }.apply { start() }
            try {
                val replies = Ts3jChannelFileReplies()
                val files =
                    client(replies) { request ->
                        assertEquals("ftinitdownload", request.name)
                        assertEquals("/first", request.get("name").value)
                        val id = request.get("clientftfid").value.toInt()
                        replies.accept("notifystartdownload", offer(id + 1, 1, 0, key))
                        replies.accept("notifystartdownload", offer(id, server.localPort, payload.size, key))
                        TextSendOutcome.Accepted
                    }
                val result = files.download(7, "/first", temporary.newFolder().toPath()) { true } as ChannelFileResult.Success
                assertArrayEquals(payload, result.value.toFile().readBytes())
                assertEquals(key, received.get(3, TimeUnit.SECONDS))
            } finally {
                server.close()
                sender.join(3_000)
            }
        }
    }

    @Test fun redirectAndOversizedOfferAreRejectedBeforeConnecting() {
        val replies = Ts3jChannelFileReplies()
        var attempts = 0
        val files =
            client(replies) { request ->
                val id = request.get("clientftfid").value.toInt()
                val fields = if (attempts++ == 0) offer(id, 1, 0, "key") + ("ip" to differentLoopback) else offer(id, 1, 30_000_000, "key")
                replies.accept("notifystartdownload", fields)
                TextSendOutcome.Accepted
            }
        val directory = temporary.newFolder()
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_RESPONSE), files.download(7, "/file", directory.toPath()) { true })
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.TOO_LARGE), files.download(7, "/file", directory.toPath()) { true })
        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }

    @Test fun wildcardEmptyAndMatchingMultiAddressOffersDownloadViaResolvedPeerWhileMismatchesReject() {
        val payload = byteArrayOf(5, 8, 13)
        val matchingIps = listOf("", wildcardIpv4, "::", "$wildcardIpv4, ::", "::, ${loopback.hostAddress}")
        for (ipField in matchingIps) {
            ServerSocket(0, 1, loopback).use { server ->
                server.soTimeout = 3_000
                val sender =
                    Thread {
                        runCatching {
                            server.accept().use { socket ->
                                socket.soTimeout = 3_000
                                socket.getInputStream().readNBytes(3)
                                socket.getOutputStream().write(payload)
                            }
                        }
                    }.apply { start() }
                try {
                    val replies = Ts3jChannelFileReplies()
                    val files =
                        client(replies) { request ->
                            val id = request.get("clientftfid").value.toInt()
                            replies.accept("notifystartdownload", offer(id, server.localPort, payload.size, "key") + ("ip" to ipField))
                            TextSendOutcome.Accepted
                        }
                    val result = files.download(7, "/wild", temporary.newFolder().toPath()) { true } as ChannelFileResult.Success
                    assertArrayEquals("offer ip='$ipField' failed", payload, result.value.toFile().readBytes())
                } finally {
                    server.close()
                    sender.join(3_000)
                }
            }
        }

        val replies = Ts3jChannelFileReplies()
        val rejectedIps = ArrayDeque(listOf("$differentLoopback, ::", "redirect.example.invalid"))
        val files =
            client(replies) { request ->
                val id = request.get("clientftfid").value.toInt()
                replies.accept("notifystartdownload", offer(id, 1, 0, "key") + ("ip" to rejectedIps.removeFirst()))
                TextSendOutcome.Accepted
            }
        val directory = temporary.newFolder()
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_RESPONSE), files.download(7, "/bad1", directory.toPath()) { true })
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_RESPONSE), files.download(7, "/bad2", directory.toPath()) { true })
    }

    @Test fun matchesTransferPeerNormalizesNumericIpv6AndRejectsHostnamesOffline() {
        val ipv6Loopback = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        assertTrue(matchesTransferPeer("[0:0:0:0:0:0:0:1]", ipv6Loopback))
        assertTrue(matchesTransferPeer("$wildcardIpv4, ::", ipv6Loopback))
        assertTrue(matchesTransferPeer("[::ffff:${loopback.hostAddress}]", loopback))
        assertFalse(matchesTransferPeer("0:0:0:0:0:0:0:2", ipv6Loopback))
        assertFalse(matchesTransferPeer("localhost", loopback))
        assertFalse(matchesTransferPeer("$wildcardIpv4, not-an-ip.invalid", loopback))
    }

    @Test fun cancellationUnwindsStalledSocketAndDiscardsPartialFile() =
        runBlocking {
            val directory = temporary.newFolder()
            ServerSocket(0, 1, loopback).use { server ->
                server.soTimeout = 3_000
                val firstByteSent = CountDownLatch(1)
                val released = CountDownLatch(1)
                val sender =
                    Thread {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 3_000
                                socket.getInputStream().readNBytes(3)
                                socket.getOutputStream().write(42)
                                firstByteSent.countDown()
                                if (socket.getInputStream().read() == -1) released.countDown()
                            }
                        } catch (_: java.io.IOException) {
                            Unit
                        }
                    }.apply { start() }
                val replies = Ts3jChannelFileReplies()
                val files =
                    client(replies) { request ->
                        replies.accept("notifystartdownload", offer(request.get("clientftfid").value.toInt(), server.localPort, 3, "key"))
                        TextSendOutcome.Accepted
                    }
                val handle = BoundChannelFiles(files, 7) { true }
                val completed = CompletableFuture<ChannelFileResult<java.nio.file.Path>>()
                val job = launch(Dispatchers.Default) { completed.complete(handle.stageDownload("/first", directory.toPath())) }
                try {
                    assertTrue(firstByteSent.await(3, TimeUnit.SECONDS))
                    job.cancelAndJoin()
                    assertTrue(released.await(3, TimeUnit.SECONDS))
                    assertFalse(completed.isDone)
                    assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
                } finally {
                    job.cancelAndJoin()
                    files.close()
                    server.close()
                    sender.join(3_000)
                }
            }
        }

    private fun client(
        replies: Ts3jChannelFileReplies,
        timeout: Long = 1_000,
        run: (SingleCommand) -> TextSendOutcome,
    ) = Ts3jChannelFiles(replies, { loopback }, { command, _, _ -> run(command) }, commandTimeoutMs = timeout)

    private fun offer(
        id: Int,
        port: Int,
        size: Int,
        key: String,
    ): Map<String, String> =
        mapOf("clientftfid" to id.toString(), "port" to port.toString(), "size" to size.toString(), "ftkey" to key, "proto" to "1")

    private fun StateAwareTeamspeakClientSocket.inject(message: String) = process(this, MultiCommand.parse(ProtocolRole.SERVER, message))
}
