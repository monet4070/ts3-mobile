package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.command.CommandProcessException
import com.github.manevolent.ts3j.event.ClientJoinEvent
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.identity.LocalIdentity
import com.github.manevolent.ts3j.protocol.PacketKind
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.github.manevolent.ts3j.protocol.packet.statistics.PacketStatistics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Ts3jSessionClientTest {
    @Test fun readinessTimeoutClosesTheSocketWithoutPublishingConnected() {
        val socket = FakeSocket().apply { beforeSubscription = { throw SocketTimeoutException("fixture readiness deadline") } }
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

        assertThrows(SocketTimeoutException::class.java) { client.connect(testConfig(), Ts3IdentityCodec.generate(), listener) }

        assertTrue(listener.statuses.none { it.phase == ConnectionPhase.CONNECTED })
        assertEquals(ConnectionPhase.ERROR, listener.statuses.last().phase)
        assertTrue(listener.statuses.last().retryable)
        assertEquals(1, socket.closeCalls)
        client.close()
    }

    @Test
    fun anActiveTalkspurtRemainsReadyWhileItsQueueIsTemporarilyEmpty() {
        var active = true
        val frames = ArrayDeque(listOf(byteArrayOf(1, 2, 3, 4)))
        val source =
            object : EncodedVoiceSource {
                override val isTransmitting get() = active

                override fun isReady() = frames.isNotEmpty()

                override fun pollEncodedFrame() = frames.removeFirstOrNull()
            }
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        try {
            client.setVoiceSource(source)
            client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())
            val microphone = checkNotNull(socket.lastMicrophone)
            assertEquals(4, microphone.provide().size)
            assertTrue("a transient queue gap ended the talkspurt", microphone.isReady)
            assertEquals(0, microphone.provide().size)
            active = false
            assertFalse(microphone.isReady)
        } finally {
            client.close()
        }
    }

    @Test
    fun theFacadeCarriesTheObservedCloneCategoryAndReleasesTheFailedSocket() {
        val failure = IllegalStateException("fixture wrapper", CommandProcessException("TOO MANY CLONES ALREADY CONNECTED"))
        val socket = FakeSocket(failureOnConnect = failure)
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        val thrown = runCatching { client.connect(testConfig(), Ts3IdentityCodec.generate(), listener) }.exceptionOrNull()
        assertEquals(failure, thrown)
        val status = listener.statuses.single { it.phase == ConnectionPhase.ERROR }
        assertEquals(ConnectionFailureCategory.CLONES_LIMIT, status.category)
        assertFalse(status.retryable)
        assertTrue(listener.statuses.none { it.phase == ConnectionPhase.CONNECTED })
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun aStaleJoinCannotMutateTheReplacementSessionSnapshot() {
        val joinEntered = CountDownLatch(1)
        val releaseJoin = CountDownLatch(1)
        val firstSocket = FakeSocket(joinEntered = joinEntered, releaseJoin = releaseJoin)
        val secondSocket = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(firstSocket, secondSocket))
        val firstListener = RecordingListener()
        val secondListener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })
        val identity = Ts3IdentityCodec.generate()

        client.connect(testConfig(), identity, firstListener)
        firstSocket.fireClientJoin(clientId = 7, channelId = 10, nickname = "Alice")

        val joinFailure = AtomicReference<Throwable?>()
        val joinThread =
            Thread {
                try {
                    client.joinChannel(20)
                } catch (error: Throwable) {
                    joinFailure.set(error)
                }
            }
        joinThread.start()
        assertTrue(joinEntered.await(1, SECONDS))

        client.connect(testConfig(), identity, secondListener)
        secondSocket.fireClientJoin(clientId = 7, channelId = 30, nickname = "Bob")
        releaseJoin.countDown()
        joinThread.join(1_000)

        assertFalse(joinThread.isAlive)
        assertNull(joinFailure.get())
        assertEquals(20, firstSocket.joinedChannel)
        assertTrue(secondListener.snapshots.isNotEmpty())
        assertEquals(30, secondListener.snapshots.last().participants.single().channelId)
        assertTrue(secondListener.snapshots.last().channels.isEmpty())
    }

    @Test
    fun aStaleDisconnectCallbackCannotClearTheReplacementSession() {
        val firstSocket = FakeSocket()
        val secondSocket = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(firstSocket, secondSocket))
        val firstListener = RecordingListener()
        val secondListener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })
        val identity = Ts3IdentityCodec.generate()

        client.connect(testConfig(), identity, firstListener)
        client.connect(testConfig(), identity, secondListener)
        firstSocket.fireDisconnected(reasonId = 4)

        assertTrue(secondSocket.isConnected)
        assertTrue(secondListener.statuses.none { it.phase == ConnectionPhase.DISCONNECTED })
        assertTrue(secondListener.snapshots.last().participants.isEmpty())
    }

    @Test
    fun failureDuringConnectedDeliveryIsReportedAfterConnected() {
        val socket = FakeSocket()
        val listener =
            RecordingListener { status ->
                if (status.phase == ConnectionPhase.CONNECTED) {
                    socket.emitException(IllegalStateException("background failure"))
                }
            }
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)

        assertEquals(
            listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED, ConnectionPhase.ERROR),
            listener.statuses.map(ConnectionStatus::phase),
        )
    }

    @Test
    fun failureBeforeConnectedDeliveryDoesNotPublishConnected() {
        val failure = IllegalStateException("handshake failed")
        val socket = FakeSocket(failureOnConnect = failure)
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

        var thrown: Throwable? = null
        try {
            client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)
        } catch (error: Throwable) {
            thrown = error
        }

        assertNotNull(thrown)
        assertEquals(failure, thrown)
        assertTrue(listener.statuses.none { it.phase == ConnectionPhase.CONNECTED })
        assertEquals(1, listener.statuses.count { it.phase == ConnectionPhase.ERROR })
    }

    @Test
    fun verifyLivenessReportsAliveWhenTheReadOnlyRoundTripAnswers() {
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())

        assertTrue(runBlocking { client.verifyLiveness(100L) })
        assertEquals(1, socket.livenessProbes)
    }

    @Test
    fun verifyLivenessReportsDeadWhenTheRoundTripTimesOut() {
        val socket = FakeSocket()
        socket.livenessResult = false
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())

        assertFalse(runBlocking { client.verifyLiveness(100L) })
    }

    @Test
    fun verifyLivenessAfterCloseDoesNotProbeAStaleSocket() {
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())
        client.close()

        assertFalse(runBlocking { client.verifyLiveness(100L) })
        assertEquals(0, socket.livenessProbes)
    }

    @Test
    fun transportConnectedTracksTheLiveSocketWithoutProbing() {
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())

        assertEquals(true, client.transportConnected)
        assertEquals(0, socket.livenessProbes)
    }

    @Test
    fun transportConnectedReportsASilentSocketLossWithoutCallbacksOrProbes() {
        val socket = FakeSocket()
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)

        // ts3j's idle watchdog clears its own connection state without emitting a
        // listener callback or an exception, so only this read-only property can
        // observe the silent loss.
        socket.markTransportDown()

        assertEquals(false, client.transportConnected)
        assertEquals(
            listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED),
            listener.statuses.map(ConnectionStatus::phase),
        )
        assertEquals(0, socket.livenessProbes)
    }

    @Test
    fun transportConnectedIsFalseWithoutASocketOrAfterClose() {
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

        assertEquals(false, client.transportConnected)

        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())
        assertEquals(true, client.transportConnected)

        client.close()
        assertEquals(false, client.transportConnected)
    }

    @Test
    fun transportConnectedIsNullForImplementationsThatDoNotExposeIt() {
        assertNull(MinimalSessionClient().transportConnected)
    }

    @Test
    fun transportConnectedIsNullWhileAServerDisconnectNotificationIsPending() {
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())

        // The transport already accepted the server's disconnect command while the
        // matching listener notification is still being dispatched.
        socket.pendingDisconnectNotification = true

        assertNull(client.transportConnected)
    }

    @Test
    fun transportConnectedIsNullAfterAReportedServerDisconnect() {
        val socket = FakeSocket()
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)

        socket.fireDisconnected(reasonId = 4)

        assertTrue(listener.statuses.any { it.phase == ConnectionPhase.DISCONNECTED })
        assertNull(client.transportConnected)
    }

    @Test
    fun transportConnectedIsNullAfterAReportedProtocolFailure() {
        val socket = FakeSocket()
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)

        socket.emitException(IllegalStateException("background failure"))

        assertEquals(ConnectionPhase.ERROR, listener.statuses.last().phase)
        assertNull(client.transportConnected)
    }

    @Test
    fun aNewConnectionAttemptClearsTheReportedFailure() {
        val firstSocket = FakeSocket()
        val secondSocket = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(firstSocket, secondSocket))
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })
        val identity = Ts3IdentityCodec.generate()

        client.connect(testConfig(), identity, RecordingListener())
        firstSocket.fireDisconnected(reasonId = 4)
        assertNull(client.transportConnected)

        client.connect(testConfig(), identity, RecordingListener())

        assertEquals(true, client.transportConnected)
    }

    @Test
    fun boundedDisconnectWithoutASessionCreatesNoSocket() {
        val created = AtomicInteger()
        val client =
            Ts3jSessionClient(
                Ts3jClientSocketFactory {
                    created.incrementAndGet()
                    FakeSocket()
                },
            )

        val outcome = runBlocking { client.disconnectBounded(BUDGET_MS) }

        assertEquals(DisconnectOutcome.NO_ACTIVE_SESSION, outcome)
        assertEquals(0, created.get())
    }

    @Test
    fun boundedDisconnectUsesTheCapturedSocketAndReportsItsOutcome() {
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket }, clockNanos = { 0L })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())
        socket.boundedDisconnectOutcome = DisconnectOutcome.SERVER_CONFIRMED

        val outcome = runBlocking { client.disconnectBounded(BUDGET_MS, REASON) }

        assertEquals(DisconnectOutcome.SERVER_CONFIRMED, outcome)
        assertEquals(1, socket.boundedDisconnectCalls)
        assertEquals(BUDGET_MS, socket.boundedDisconnectBudget)
        assertEquals(REASON, socket.boundedDisconnectReason)
        // The old socket's microphone stops before the exit, so no further voice
        // frame can be sent while the server is still being asked to release us.
        assertNull(socket.lastMicrophone)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun boundedDisconnectDeductsPreparationTimeFromTheSocketBudget() {
        val socket = FakeSocket()
        val now = java.util.concurrent.atomic.AtomicLong(5_000_000L)
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket }, now::get)
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())
        socket.beforeMicrophoneUpdate = { now.addAndGet(2_000_000L) }

        val outcome = runBlocking { client.disconnectBounded(BUDGET_MS, REASON) }

        assertEquals(DisconnectOutcome.SERVER_CONFIRMED, outcome)
        assertEquals(BUDGET_MS - 2, socket.boundedDisconnectBudget)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun boundedDisconnectDoesNotSubmitAfterPreparationExhaustsTheBudget() {
        val socket = FakeSocket()
        val now = java.util.concurrent.atomic.AtomicLong()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket }, now::get)
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())
        socket.beforeMicrophoneUpdate = { now.addAndGet(BUDGET_MS * 1_000_000L) }

        val outcome = runBlocking { client.disconnectBounded(BUDGET_MS, REASON) }

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertEquals(0, socket.boundedDisconnectCalls)
        assertNull(socket.lastMicrophone)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun boundedDisconnectReportsFailedWhenTheSocketFails() {
        val socket = FakeSocket(boundedDisconnectFailure = IllegalStateException("transport is gone"))
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())

        val outcome = runBlocking { client.disconnectBounded(BUDGET_MS) }

        assertEquals(DisconnectOutcome.FAILED, outcome)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun boundedDisconnectDropsTheOldListenerAndGeneration() {
        val socket = FakeSocket()
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)
        socket.fireClientJoin(clientId = 7, channelId = 10, nickname = "Alice")
        val statusesBefore = listener.statuses.size
        val snapshotsBefore = listener.snapshots.size

        runBlocking { client.disconnectBounded(BUDGET_MS) }

        // Events from the replaced session must not reach the listener, and the
        // old failure handler must not report a second time.
        socket.fireClientJoin(clientId = 7, channelId = 99, nickname = "Late")
        socket.fireDisconnected(reasonId = 4)
        socket.emitException(IllegalStateException("late failure"))

        assertEquals(statusesBefore, listener.statuses.size)
        assertEquals(snapshotsBefore, listener.snapshots.size)
        assertEquals(false, client.transportConnected)
    }

    @Test
    fun repeatedBoundedDisconnectOnlyLeavesOnce() {
        val socket = FakeSocket()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())

        val first = runBlocking { client.disconnectBounded(BUDGET_MS) }
        val second = runBlocking { client.disconnectBounded(BUDGET_MS) }

        assertEquals(DisconnectOutcome.SERVER_CONFIRMED, first)
        assertEquals(DisconnectOutcome.NO_ACTIVE_SESSION, second)
        assertEquals(1, socket.boundedDisconnectCalls)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun boundedDisconnectDoesNotCloseTheReplacementSession() {
        val exitEntered = CountDownLatch(1)
        val releaseExit = CountDownLatch(1)
        val firstSocket =
            FakeSocket(
                onBoundedDisconnect = {
                    exitEntered.countDown()
                    releaseExit.await(2, SECONDS)
                },
            )
        val secondSocket = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(firstSocket, secondSocket))
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })
        val identity = Ts3IdentityCodec.generate()
        client.connect(testConfig(), identity, RecordingListener())

        val exitOutcome = AtomicReference<DisconnectOutcome?>()
        val exitThread =
            Thread {
                exitOutcome.set(runBlocking { client.disconnectBounded(BUDGET_MS) })
            }
        exitThread.start()
        assertTrue(exitEntered.await(1, SECONDS))

        client.connect(testConfig(), identity, RecordingListener())
        releaseExit.countDown()
        exitThread.join(1_000)

        assertFalse(exitThread.isAlive)
        assertEquals(DisconnectOutcome.SERVER_CONFIRMED, exitOutcome.get())
        assertEquals(1, firstSocket.closeCalls)
        assertEquals(0, secondSocket.closeCalls)
        assertEquals(true, secondSocket.isConnected)
    }

    @Test
    fun aSocketWithoutBoundedSupportIsReportedAsUnsupported() {
        val socket = FakeSocket(boundedDisconnectSupported = false)
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
        client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())

        val outcome = runBlocking { client.disconnectBounded(BUDGET_MS) }

        assertEquals(DisconnectOutcome.UNSUPPORTED, outcome)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun theDefaultBoundedDisconnectIsUnsupported() {
        val outcome = runBlocking { MinimalSessionClient().disconnectBounded(BUDGET_MS) }

        assertEquals(DisconnectOutcome.UNSUPPORTED, outcome)
    }

    private fun testConfig() =
        ServerConfig(
            host = "localhost",
            nickname = "Test User",
        )

    @Test
    fun staleTextAdapterCannotDeliverIntoReplacementListener() {
        val first = FakeSocket()
        val second = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(first, second))
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })
        val oldListener = RecordingListener()
        val currentListener = RecordingListener()
        val identity = Ts3IdentityCodec.generate()
        client.connect(testConfig(), identity, oldListener)
        client.connect(testConfig(), identity, currentListener)
        first.fireText("stale")
        second.fireText("current")
        assertTrue(oldListener.texts.isEmpty())
        assertEquals(listOf("current"), currentListener.texts.map { it.body })
        client.close()
    }

    @Test
    fun unsupportedFacadeAndDisconnectedSendNeverClaimSuccess() =
        runBlocking {
            assertEquals(
                TextSendOutcome.Unsupported,
                MinimalSessionClient().sendTextMessage(TextTarget.Channel(1), "hello", 100),
            )
            assertEquals(
                TextSendOutcome.NotSent(TextNotSentReason.NOT_CONNECTED),
                Ts3jSessionClient().sendTextMessage(TextTarget.Channel(1), "hello", 100),
            )
        }

    @Test
    fun privateSendChecksExpectedIdentityBeforeAndAtQueueOwnership() =
        runBlocking {
            val socket = FakeSocket()
            val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })
            client.connect(testConfig(), Ts3IdentityCodec.generate(), RecordingListener())
            socket.fireClientJoin(8, 10, "Fixture", "first-uid")
            assertEquals(TextSendOutcome.Accepted, client.sendTextMessage(TextTarget.Private(8, "first-uid"), "private", 1_000))
            assertEquals(1, socket.textCalls)
            socket.beforeTextSubmit = { socket.fireClientJoin(8, 10, "Fixture", "replacement-uid") }
            assertEquals(
                TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED),
                client.sendTextMessage(TextTarget.Private(8, "first-uid"), "must not send", 1_000),
            )
            assertEquals(1, socket.textCalls)
            client.close()
        }

    @Test fun pokeUsesDistinctPortRechecksIdentityAndRejectsStaleIncomingAdapter() =
        runBlocking {
            val sockets = listOf(FakeSocket(), FakeSocket())
            var next = 0
            val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets[next++] })
            val old = RecordingListener()
            client.connect(testConfig(), Ts3IdentityCodec.generate(), old)
            sockets[0].fireClientJoin(8, 10, "Fixture", "first-uid")
            assertEquals(TextSendOutcome.Accepted, client.pokeClient(TextTarget.Private(8, "first-uid"), "", 1_000))
            assertEquals(1, sockets[0].pokeCalls)
            assertEquals(0, sockets[0].textCalls)
            sockets[0].beforeTextSubmit = { sockets[0].fireClientJoin(8, 10, "Fixture", "replacement") }
            assertEquals(
                TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED),
                client.pokeClient(TextTarget.Private(8, "first-uid"), "", 1_000),
            )
            sockets[0].firePoke("current")
            assertEquals(1, old.pokes.size)
            val replacement = RecordingListener()
            client.connect(testConfig(), Ts3IdentityCodec.generate(), replacement)
            sockets[0].firePoke("stale")
            sockets[1].firePoke("replacement")
            assertEquals(1, old.pokes.size)
            assertEquals(listOf("replacement"), replacement.pokes.map { it.body })
            client.close()
        }

    @Test fun serverDisconnectDuringSubscribeRejectsAttemptWithoutPublishingConnected() {
        for (reasonId in listOf(3, 4)) {
            val socket = FakeSocket()
            socket.beforeSubscription = { socket.fireDisconnected(reasonId) }
            val listener = RecordingListener()
            val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

            val thrown =
                assertThrows(ServerDisconnectedDuringConnectException::class.java) {
                    client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)
                }

            assertEquals(reasonId != 4, thrown.isRetryableConnectionFailure())
            assertTrue(listener.statuses.none { it.phase == ConnectionPhase.CONNECTED })
            assertTrue(listener.statuses.none { it.phase == ConnectionPhase.ERROR })
            val disconnected = listener.statuses.last()
            assertEquals(ConnectionPhase.DISCONNECTED, disconnected.phase)
            assertEquals(reasonId != 4, disconnected.retryable)
            assertEquals(1, socket.closeCalls)
            assertNull(client.transportConnected)
            client.close()
        }
    }

    @Test fun serverDisconnectDuringConnectedDeliveryIsReportedAfterConnected() {
        val socket = FakeSocket()
        val listener =
            RecordingListener { status ->
                if (status.phase == ConnectionPhase.CONNECTED) {
                    socket.fireDisconnected(reasonId = 3)
                }
            }
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)

        assertEquals(
            listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED, ConnectionPhase.DISCONNECTED),
            listener.statuses.map(ConnectionStatus::phase),
        )
        assertEquals(1, socket.closeCalls)
        assertNull(client.transportConnected)
        client.close()
    }

    @Test fun transientTs3jHandshakeWidthFailuresRetryUpToThreeAttemptsAndEventuallySucceed() {
        val first = FakeSocket(failureOnConnect = ts3jHandshakeWidthError())
        val second = FakeSocket(failureOnConnect = ts3jHandshakeWidthError())
        val third = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(first, second, third))
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })

        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)

        assertTrue(sockets.isEmpty())
        assertEquals(1, first.closeCalls)
        assertEquals(1, second.closeCalls)
        assertEquals(0, third.closeCalls)
        assertEquals(listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED), listener.statuses.map(ConnectionStatus::phase))
        assertEquals(true, client.transportConnected)
        client.close()
    }

    @Test fun exhaustedTs3jHandshakeWidthRetriesCloseAllSocketsAndEmitSingleRetryableError() {
        val created = mutableListOf<FakeSocket>()
        val listener = RecordingListener()
        val client =
            Ts3jSessionClient(
                Ts3jClientSocketFactory {
                    FakeSocket(failureOnConnect = ts3jHandshakeWidthError()).also { created += it }
                },
            )

        val thrown =
            assertThrows(ArrayIndexOutOfBoundsException::class.java) {
                client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)
            }

        assertTrue(thrown.isTs3jHandshakeWidthFailure())
        assertEquals(3, created.size)
        assertTrue(created.all { it.closeCalls == 1 })
        assertTrue(listener.statuses.none { it.phase == ConnectionPhase.CONNECTED })
        assertEquals(1, listener.statuses.count { it.phase == ConnectionPhase.ERROR })
        assertTrue(listener.statuses.last().retryable)
        client.close()
    }

    @Test fun lateEventsVoiceAndDisconnectFromRetriedSocketCannotPolluteReplacementAttempt() {
        val first = FakeSocket(failureOnConnect = ts3jHandshakeWidthError())
        val second = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(first, second))
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })

        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)
        second.fireClientJoin(clientId = 20, channelId = 1, nickname = "Active")

        // Retried first socket emits late snapshot/text/poke/voice/disconnect/exception events.
        first.fireClientJoin(clientId = 99, channelId = 2, nickname = "Ghost")
        first.fireText("stale text")
        first.firePoke("stale poke")
        first.fireVoice(clientId = 99, packetId = 1)
        first.fireWhisper(clientId = 99, packetId = 2)
        first.fireDisconnected(reasonId = 4)
        first.emitException(IllegalStateException("stale exception"))

        second.fireClientJoin(clientId = 21, channelId = 1, nickname = "Active2")
        second.fireVoice(clientId = 20, packetId = 3)
        second.fireWhisper(clientId = 20, packetId = 4)

        assertEquals(listOf(20, 21), listener.snapshots.last().participants.map(Ts3Participant::id))
        assertTrue(listener.texts.isEmpty())
        assertTrue(listener.pokes.isEmpty())
        assertEquals(listOf(3 to false, 4 to true), listener.voiceFrames.map { it.packetId to it.isWhisper })
        assertEquals(listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED), listener.statuses.map(ConnectionStatus::phase))
        assertEquals(true, client.transportConnected)
        client.close()
    }

    private fun ts3jHandshakeWidthError(): ArrayIndexOutOfBoundsException =
        ArrayIndexOutOfBoundsException("Index 128 out of bounds for length 128").apply {
            stackTrace =
                arrayOf(
                    StackTraceElement(
                        "com.github.manevolent.ts3j.protocol.packet.handler.client.LocalClientHandlerConnecting",
                        "handlePacket",
                        "LocalClientHandlerConnecting.java",
                        184,
                    ),
                )
        }

    private class RecordingListener(
        private val onStatus: ((ConnectionStatus) -> Unit)? = null,
    ) : Ts3SessionListener {
        val statuses = mutableListOf<ConnectionStatus>()
        val snapshots = mutableListOf<SessionSnapshot>()
        val texts = mutableListOf<Ts3TextEvent>()
        val pokes = mutableListOf<Ts3PokeEvent>()
        val voiceFrames = mutableListOf<VoiceFrame>()

        override fun onPoke(event: Ts3PokeEvent) {
            pokes += event
        }

        override fun onTextMessage(event: Ts3TextEvent) {
            texts += event
        }

        override fun onStatusChanged(status: ConnectionStatus) {
            statuses += status
            onStatus?.invoke(status)
        }

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            snapshots += snapshot
        }

        override fun onVoiceFrame(frame: VoiceFrame) {
            voiceFrames += frame
        }
    }

    private class MinimalSessionClient : Ts3SessionClient {
        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) = Unit

        override fun setVoiceSource(source: EncodedVoiceSource?) = Unit

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = Unit

        override fun disconnect(reason: String) = Unit

        override fun close() = Unit
    }

    private class FakeSocket(
        private val joinEntered: CountDownLatch? = null,
        private val releaseJoin: CountDownLatch? = null,
        private val failureOnConnect: Throwable? = null,
        private val boundedDisconnectSupported: Boolean = true,
        private val boundedDisconnectFailure: Throwable? = null,
        private val onBoundedDisconnect: (() -> Unit)? = null,
    ) : Ts3jClientSocket {
        private var exceptionHandler: ((Throwable) -> Unit)? = null
        private var voiceHandler: ((PacketBody0Voice) -> Unit)? = null
        private var whisperHandler: ((PacketBody1VoiceWhisper) -> Unit)? = null
        private var connected = false
        private var eventListener: TS3Listener? = null
        var textCalls = 0
        var pokeCalls = 0
        var beforeTextSubmit: () -> Unit = {}
        var beforeMicrophoneUpdate: () -> Unit = {}
        var beforeSubscription: () -> Unit = {}

        override fun pokeClient(
            target: TextTarget.Private,
            reason: String,
            timeoutMs: Long,
            canSubmit: () -> Boolean,
        ): TextSendOutcome {
            beforeTextSubmit()
            if (!canSubmit()) return TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED)
            pokeCalls++
            return TextSendOutcome.Accepted
        }

        override fun sendTextMessage(
            target: TextTarget,
            body: String,
            timeoutMs: Long,
            canSubmit: () -> Boolean,
        ): TextSendOutcome {
            beforeTextSubmit()
            if (!canSubmit()) return TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED)
            textCalls++
            return TextSendOutcome.Accepted
        }

        override val clientId: Int = 7
        var joinedChannel: Int? = null
            private set
        var closeCalls = 0
            private set
        var boundedDisconnectCalls = 0
            private set
        var boundedDisconnectBudget = 0L
            private set
        var boundedDisconnectReason: String? = null
            private set
        var boundedDisconnectOutcome: DisconnectOutcome = DisconnectOutcome.SERVER_CONFIRMED
        var lastMicrophone: Microphone? = null
            private set

        override fun setIdentity(identity: LocalIdentity) = Unit

        override fun setNickname(nickname: String) = Unit

        override fun setHWID(hwid: String) = Unit

        override fun setMicrophone(microphone: Microphone?) {
            beforeMicrophoneUpdate()
            lastMicrophone = microphone
        }

        override fun setExceptionHandler(handler: (Throwable) -> Unit) {
            exceptionHandler = handler
        }

        override fun setVoiceHandler(handler: (PacketBody0Voice) -> Unit) {
            voiceHandler = handler
        }

        override fun setWhisperHandler(handler: (PacketBody1VoiceWhisper) -> Unit) {
            whisperHandler = handler
        }

        override fun addListener(listener: TS3Listener) {
            eventListener = listener
        }

        override fun connect(
            address: InetSocketAddress,
            password: String?,
            timeout: Long,
        ) {
            connected = true
            failureOnConnect?.let { exceptionHandler?.invoke(it) }
        }

        override fun subscribeAll(canSubmit: () -> Boolean) = beforeSubscription()

        override val isConnected: Boolean
            get() = connected

        override fun joinChannel(
            channelId: Int,
            password: String,
            canSubmit: () -> Boolean,
        ) {
            if (!canSubmit()) throw ChannelMoveNotSubmittedException()
            joinEntered?.countDown()
            releaseJoin?.await(2, SECONDS)
            joinedChannel = channelId
        }

        override fun disconnect(reason: String) {
            connected = false
        }

        override fun disconnectBounded(
            timeoutMs: Long,
            reason: String,
            onRequestSent: () -> Unit,
        ): DisconnectOutcome {
            if (!boundedDisconnectSupported) return super.disconnectBounded(timeoutMs, reason, onRequestSent)
            boundedDisconnectCalls++
            boundedDisconnectBudget = timeoutMs
            boundedDisconnectReason = reason
            onBoundedDisconnect?.invoke()
            boundedDisconnectFailure?.let { throw it }
            return boundedDisconnectOutcome
        }

        override fun close() {
            closeCalls++
            connected = false
        }

        var livenessResult = true
        var livenessProbes = 0

        override fun probeLiveness(timeoutMs: Long): Boolean {
            livenessProbes++
            return livenessResult
        }

        override fun getStatistics(packetKind: PacketKind): PacketStatistics = PacketStatistics()

        var pendingDisconnectNotification = false

        override val disconnectNotificationPending: Boolean
            get() = pendingDisconnectNotification

        fun markTransportDown() {
            connected = false
        }

        fun emitException(error: Throwable) {
            exceptionHandler?.invoke(error)
        }

        fun fireClientJoin(
            clientId: Int,
            channelId: Int,
            nickname: String,
            uid: String = "test-user",
        ) {
            eventListener?.onClientJoin(
                ClientJoinEvent(
                    mapOf(
                        "clid" to clientId.toString(),
                        "ctid" to channelId.toString(),
                        "client_type" to "0",
                        "client_nickname" to nickname,
                        "client_unique_identifier" to uid,
                        "client_input_muted" to "0",
                        "client_output_muted" to "0",
                        "client_flag_talking" to "0",
                    ),
                ),
            )
        }

        fun fireText(body: String) {
            eventListener?.onTextMessage(
                com.github.manevolent.ts3j.event.TextMessageEvent(
                    mapOf("targetmode" to "2", "msg" to body, "invokerid" to "8"),
                ),
            )
        }

        fun firePoke(body: String) {
            eventListener?.onClientPoke(
                com.github.manevolent.ts3j.event.ClientPokeEvent(
                    mapOf("msg" to body, "invokerid" to "8", "invokeruid" to "fixture-uid", "invokername" to "Fixture"),
                ),
            )
        }

        fun fireDisconnected(reasonId: Int) {
            eventListener?.onDisconnected(
                DisconnectedEvent(mapOf("reasonid" to reasonId.toString())),
            )
        }

        fun fireVoice(
            clientId: Int,
            packetId: Int,
        ) {
            voiceHandler?.invoke(
                PacketBody0Voice(com.github.manevolent.ts3j.protocol.ProtocolRole.SERVER).apply {
                    this.clientId = clientId
                    this.packetId = packetId
                    codecType = com.github.manevolent.ts3j.enums.CodecType.OPUS_VOICE
                    codecData = byteArrayOf(1, 2, 3)
                },
            )
        }

        fun fireWhisper(
            clientId: Int,
            packetId: Int,
        ) {
            whisperHandler?.invoke(
                PacketBody1VoiceWhisper(com.github.manevolent.ts3j.protocol.ProtocolRole.SERVER).apply {
                    this.clientId = clientId
                    this.packetId = packetId
                    codecType = com.github.manevolent.ts3j.enums.CodecType.OPUS_VOICE
                    codecData = byteArrayOf(4, 5, 6)
                },
            )
        }
    }

    private companion object {
        const val BUDGET_MS = 1_000L
        const val REASON = "task-removed"
    }
}
