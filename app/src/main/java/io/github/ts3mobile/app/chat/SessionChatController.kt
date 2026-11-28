package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.InvalidIdentityMaterial
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3TextEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Captures a connection before scheduling, never looks up a newer session from IO. */
class SessionChatController internal constructor(
    private val scope: CoroutineScope,
    private val store: ChatStore = ChatStore(),
    private val timeoutMs: Long = 5_000,
    private val history: ChatHistoryController?,
) {
    constructor(scope: CoroutineScope, store: ChatStore = ChatStore(), timeoutMs: Long = 5_000) : this(scope, store, timeoutMs, null)

    private val lock = Any()
    private var connection: Connection? = null
    private var sendJob: Job? = null
    val state = store.state

    fun begin(epoch: Long) =
        synchronized(lock) {
            connection = null
            sendJob?.cancel()
            sendJob = null
            store.begin(epoch)
        }

    fun bind(
        binding: ChatBinding,
        client: Ts3SessionClient,
        switching: Boolean,
        participants: List<Ts3Participant> = emptyList(),
        isCurrent: () -> Boolean,
    ) = bindWithHistory(binding, client, switching, participants, null, isCurrent)

    internal fun bindWithHistory(
        binding: ChatBinding,
        client: Ts3SessionClient,
        switching: Boolean,
        participants: List<Ts3Participant>,
        account: ChatHistoryAccount?,
        isCurrent: () -> Boolean,
    ) = synchronized(lock) {
        if (store.bind(binding.scope, binding.attempt, binding, switching, participants)) {
            connection = Connection(binding, client, account, isCurrent)
        }
    }

    internal fun historyAccount(
        config: ServerConfig,
        identity: String,
    ): ChatHistoryAccount? {
        if (history == null) return null
        return try {
            ChatHistoryAccount.parse(config.host, config.port, Ts3IdentityCodec.inspect(identity).uid)
        } catch (_: InvalidIdentityMaterial) {
            null
        }
    }

    fun unbound(
        epoch: Long,
        attempt: Long,
    ) = synchronized(lock) {
        if (!store.bind(epoch, attempt, null)) return@synchronized
        connection = null
        sendJob?.cancel()
        sendJob = null
    }

    fun pause(
        epoch: Long,
        attempt: Long,
    ) = synchronized(lock) {
        if (!store.pause(epoch, attempt)) return@synchronized
        connection = null
        sendJob?.cancel()
        sendJob = null
    }

    fun clear(epoch: Long? = state.value.scope) =
        synchronized(lock) {
            if (state.value.scope != epoch) return@synchronized
            connection = null
            store.clear(epoch)
            sendJob?.cancel()
            sendJob = null
        }

    fun receive(
        epoch: Long,
        attempt: Long,
        event: Ts3TextEvent,
    ) {
        synchronized(lock) {
            val ticket = history?.ticket()
            val receipt = store.acceptIncoming(epoch, attempt, event) ?: return
            val captured = connection ?: return
            if (ticket != null && captured.binding.scope == epoch && captured.binding.attempt == attempt && captured.isCurrent()) {
                val channel = captured.binding.channelId.takeUnless { state.value.switching }
                archive(captured, ticket, receipt.first, receipt.second, channel)
            }
        }
    }

    fun updateDraft(body: String) = store.updateDraft(body)

    fun receivePoke(
        epoch: Long,
        attempt: Long,
        event: Ts3PokeEvent,
    ) = synchronized(lock) { store.receivePoke(epoch, attempt, event) }

    fun dismissPoke(id: Long) = store.dismissPoke(id)

    fun poke(
        recipient: PokeRecipient,
        reason: String,
    ): Boolean = submitClaim { store.claimPoke(it, recipient, reason) }

    fun markVisibleThrough(sequence: Long) = store.markVisibleThrough(sequence)

    fun markVisibleThrough(
        key: ChatConversationKey,
        sequence: Long,
    ) = store.markVisibleThrough(key, sequence)

    fun openPrivate(clientId: Int): Boolean = synchronized(lock) { store.openPrivate(clientId) }

    fun select(key: ChatConversationKey) = synchronized(lock) { store.select(key) }

    fun closeConversation(key: ChatConversationKey) = synchronized(lock) { store.close(key) }

    fun submitChannel() =
        synchronized(lock) {
            if (state.value.selected == ChatConversationKey.Channel) submit()
        }

    fun submit() {
        submitClaim { store.claimSend(it) }
    }

    private fun submitClaim(claim: (ChatBinding) -> ChatSubmission?): Boolean {
        val job =
            synchronized(lock) {
                val captured = connection ?: return false
                if (!captured.isCurrent()) return false
                val request = claim(captured.binding) ?: return false
                val message = request.message
                val historyTicket = history?.ticket()
                scope.launch(start = CoroutineStart.LAZY) {
                    val valid = {
                        synchronized(lock) {
                            connection?.client === captured.client && store.canSubmit(request) && captured.isCurrent()
                        }
                    }
                    val outcome =
                        try {
                            withTimeout(timeoutMs) {
                                if (message.type == ChatMessageType.POKE) {
                                    captured.client.pokeClient(request.target as TextTarget.Private, message.body, timeoutMs, valid)
                                } else {
                                    captured.client.sendTextMessage(request.target, message.body, timeoutMs, valid)
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            TextSendOutcome.Unknown
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            TextSendOutcome.Unknown
                        }
                    synchronized(lock) {
                        if (historyTicket != null && outcome == TextSendOutcome.Accepted && valid()) {
                            archive(
                                captured,
                                historyTicket,
                                request.conversation,
                                message.copy(outcome = outcome),
                                captured.binding.channelId,
                            )
                        }
                        store.complete(captured.binding, message.id, outcome)
                    }
                }.also { pending ->
                    sendJob = pending
                    pending.invokeOnCompletion { cause ->
                        if (cause != null) store.complete(captured.binding, message.id, TextSendOutcome.Unknown)
                        synchronized(lock) { if (sendJob === pending) sendJob = null }
                    }
                }
            }
        job.start()
        return true
    }

    private fun archive(
        connection: Connection,
        ticket: Long,
        key: ChatConversationKey,
        message: ChatMessage,
        channelId: Int?,
    ) {
        val account = connection.account ?: return
        ChatHistoryRecord.from(account, key, message, channelId)?.let { history?.offer(ticket, it) }
    }

    private class Connection(
        val binding: ChatBinding,
        val client: Ts3SessionClient,
        val account: ChatHistoryAccount?,
        val isCurrent: () -> Boolean,
    )
}
