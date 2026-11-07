package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.MAX_POKE_INPUT_BYTES
import io.github.ts3mobile.protocol.MAX_TEXT_EVENT_BYTES
import io.github.ts3mobile.protocol.TextEventIssue
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import io.github.ts3mobile.protocol.Ts3TextEvent
import io.github.ts3mobile.protocol.boundedText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Service memory only; directory and retention have separate, deterministic responsibilities. */
class ChatStore(
    maxMessages: Int = 500,
    maxBytes: Int = 2 * 1_024 * 1_024,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val nextId: () -> Long = generateSequence(1L) { it + 1 }.iterator().let { ids -> { ids.next() } },
    private val maxConversations: Int = 20,
) {
    init {
        require(maxMessages > 1 && maxBytes >= MAX_TEXT_EVENT_BYTES + 256 && maxConversations > 1)
    }

    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private val retention = ChatRetentionPolicy(maxMessages, maxBytes)
    private val directory = PrivateChatDirectory()
    private var sequence = 0L
    private var activeAttempt: Long? = null
    private var latestAttempt = Long.MIN_VALUE

    @Synchronized fun begin(scope: Long) {
        directory.clear()
        activeAttempt = null
        latestAttempt = Long.MIN_VALUE
        sequence = 0
        mutable.value = ChatState(scope = scope, sessionToken = UUID.randomUUID().toString())
    }

    @Synchronized fun clear(scope: Long? = mutable.value.scope) {
        if (mutable.value.scope != scope) return
        directory.clear()
        activeAttempt = null
        mutable.value = ChatState()
    }

    @Synchronized fun bind(
        scope: Long,
        attempt: Long,
        binding: ChatBinding?,
        switching: Boolean = false,
        participants: List<Ts3Participant> = emptyList(),
    ): Boolean {
        var previous = mutable.value
        if (previous.scope != scope || attempt < latestAttempt) return false
        val ownClientId = binding?.ownClientId ?: previous.ownClientId.takeIf { activeAttempt == attempt }
        if (activeAttempt != attempt) {
            previous = previous.withoutAttemptTargets()
            if (activeAttempt != null) previous = previous.invalidateMessageNotifications()
        }
        latestAttempt = attempt
        activeAttempt = attempt
        directory.update(attempt, participants, ownClientId)
        val changedChannel = binding?.channelId != null && previous.binding?.channelId != binding.channelId
        var updated =
            previous.copy(
                binding = binding,
                connected = true,
                established = true,
                switching = switching,
                ownClientId = ownClientId,
                conversations =
                    previous.conversations.map {
                        it.copy(target = directory.target(it.key), title = directory.title(it.key) ?: it.title)
                    },
            )
        if (changedChannel) {
            updated =
                retention.append(
                    updated, ChatConversationKey.Channel,
                    message(ChatMessageKind.CHANNEL, binding?.channelId, null, ""),
                )
        }
        mutable.value = updated
        return true
    }

    @Synchronized fun pause(
        scope: Long,
        attempt: Long,
    ): Boolean {
        if (mutable.value.scope != scope || activeAttempt != attempt) return false
        activeAttempt = null
        directory.clear()
        mutable.value = mutable.value.withoutAttemptTargets().invalidateMessageNotifications().copy(connected = false, switching = false)
        return true
    }

    @Synchronized fun openPrivate(clientId: Int): Boolean {
        if (!mutable.value.connected || clientId == mutable.value.ownClientId) return false
        val key = directory.keyFor(clientId) ?: return false
        if (!ensureConversation(key, directory.title(key))) return false
        mutable.value = mutable.value.copy(selected = key)
        return true
    }

    @Synchronized fun select(key: ChatConversationKey) {
        if (mutable.value.conversations.any { it.key == key }) mutable.value = mutable.value.copy(selected = key)
    }

    @Synchronized fun close(key: ChatConversationKey): Boolean {
        if (key == ChatConversationKey.Channel) return false
        val current = mutable.value
        if (current.conversations.firstOrNull { it.key == key }?.messages?.any { it.isSending } == true) return false
        mutable.value =
            current.copy(
                conversations = current.conversations.filterNot { it.key == key },
                selected = if (current.selected == key) ChatConversationKey.Channel else current.selected,
                conversationLimitReached = false,
            )
        return true
    }

    @Synchronized fun updateDraft(body: String) {
        if (mutable.value.scope == null) return
        val bounded = body.boundedText(MAX_TEXT_EVENT_BYTES)
        updateConversation(mutable.value.selected) { it.copy(draft = bounded, draftOverflow = bounded.length != body.length) }
    }

    @Synchronized fun markVisibleThrough(sequence: Long) = markVisibleThrough(mutable.value.selected, sequence)

    @Synchronized fun markVisibleThrough(
        key: ChatConversationKey,
        sequence: Long,
    ) {
        updateConversation(key) { conversation ->
            val last = conversation.messages.lastOrNull()?.sequence ?: return@updateConversation conversation
            conversation.copy(readThrough = maxOf(conversation.readThrough, sequence.coerceAtMost(last)))
        }
    }

    @Synchronized fun beginSend(binding: ChatBinding): ChatMessage? = claimSend(binding)?.message

    @Synchronized fun claimSend(binding: ChatBinding): ChatSubmission? {
        val current = mutable.value
        if (activeAttempt != binding.attempt || current.binding != binding || !current.canSend) return null
        val target = current.target ?: return null
        updateConversation(current.selected) { it.copy(draft = "", draftOverflow = false) }
        return appendOutgoing(binding, current.selected, target, current.draft, ChatMessageType.TEXT)
    }

    @Synchronized fun claimPoke(
        binding: ChatBinding,
        recipient: PokeRecipient,
        reason: String,
    ): ChatSubmission? {
        val current = mutable.value
        val uid = recipient.target.expectedUid.chatUid() ?: return null
        val key = ChatConversationKey.User(uid)
        if (!current.connected || current.sending || current.binding != binding || activeAttempt != binding.attempt ||
            recipient.binding?.scope != binding.scope || recipient.binding?.attempt != binding.attempt ||
            recipient.target.clientId == current.ownClientId || directory.target(key) != recipient.target ||
            reason.toByteArray(Charsets.UTF_8).size > MAX_POKE_INPUT_BYTES
        ) {
            return null
        }
        if (!ensureConversation(key, recipient.nickname)) return null
        return appendOutgoing(binding, key, recipient.target, reason, ChatMessageType.POKE)
    }

    private fun appendOutgoing(
        binding: ChatBinding,
        key: ChatConversationKey,
        target: TextTarget,
        body: String,
        type: ChatMessageType,
    ): ChatSubmission {
        val outgoing = message(ChatMessageKind.OUTGOING, (target as? TextTarget.Channel)?.channelId, null, body).copy(type = type)
        mutable.value = retention.append(mutable.value, key, outgoing)
        return ChatSubmission(binding, key, target, outgoing)
    }

    @Synchronized fun canSubmit(request: ChatSubmission): Boolean {
        val current = mutable.value
        if (current.scope != request.binding.scope || activeAttempt != request.binding.attempt || !current.connected) return false
        return when (val target = request.target) {
            is TextTarget.Channel -> !current.switching && current.binding?.channelId == target.channelId
            is TextTarget.Private -> directory.target(request.conversation) == target
        }
    }

    @Synchronized fun complete(
        binding: ChatBinding,
        id: Long,
        outcome: TextSendOutcome,
    ) {
        val current = mutable.value
        if (current.scope != binding.scope || activeAttempt != binding.attempt) return
        mutable.value =
            current.copy(
                conversations =
                    current.conversations.map { conversation ->
                        conversation.copy(
                            messages =
                                conversation.messages.map {
                                    if (it.id == id && it.isSending) it.copy(outcome = outcome) else it
                                },
                        )
                    },
            )
    }

    @Synchronized fun receive(
        scope: Long,
        attempt: Long,
        event: Ts3TextEvent,
        type: ChatMessageType = ChatMessageType.TEXT,
    ) {
        val current = mutable.value
        if (current.scope != scope || activeAttempt != attempt || !current.connected) return
        if (event.mode == TextTargetMode.UNKNOWN || event.issue == TextEventIssue.MISSING_BODY) {
            mutable.value = current.copy(invalidEvent = true)
            return
        }
        if (event.invokerId != null && event.invokerId == current.ownClientId) return
        val key =
            when (event.mode) {
                TextTargetMode.CHANNEL -> ChatConversationKey.Channel
                TextTargetMode.PRIVATE -> {
                    if (current.ownClientId == null || event.targetId != current.ownClientId || event.invokerId == null) {
                        mutable.value = current.copy(invalidEvent = true)
                        return
                    }
                    val privateKey =
                        event.invokerUid.chatUid()?.let(ChatConversationKey::User)
                            ?: directory.fallbackKey(checkNotNull(event.invokerId))
                    if (!ensureConversation(privateKey, event.invokerName?.boundedText(256))) return
                    privateKey
                }
                else -> return
            }
        val bounded = event.body.boundedText(MAX_TEXT_EVENT_BYTES)
        val incoming =
            message(
                ChatMessageKind.INCOMING,
                if (event.mode == TextTargetMode.CHANNEL) event.targetId else null,
                event.invokerName?.boundedText(256),
                bounded,
            )
                .copy(truncated = event.issue == TextEventIssue.TRUNCATED || bounded.length != event.body.length, type = type)
        val appended = retention.append(mutable.value, key, incoming)
        mutable.value =
            when {
                type == ChatMessageType.POKE -> appended.copy(pokeNoticeId = incoming.id)
                event.mode == TextTargetMode.PRIVATE -> appended.withPrivateMessageActivity(key)
                else -> appended
            }
    }

    @Synchronized fun receivePoke(
        scope: Long,
        attempt: Long,
        event: Ts3PokeEvent,
    ) = receive(
        scope,
        attempt,
        Ts3TextEvent(
            TextTargetMode.PRIVATE,
            mutable.value.ownClientId,
            event.invokerId,
            event.invokerUid,
            event.invokerName,
            event.body,
            event.issue,
        ),
        ChatMessageType.POKE,
    )

    @Synchronized fun dismissPoke(id: Long) {
        if (mutable.value.pokeNoticeId == id) mutable.value = mutable.value.copy(pokeNoticeId = null)
    }

    private fun ensureConversation(
        key: ChatConversationKey,
        title: String?,
    ): Boolean {
        mutable.value =
            mutable.value.ensureConversation(key, directory.title(key) ?: title, directory.target(key), nextId, maxConversations)
        return mutable.value.conversations.any { it.key == key }
    }

    private fun updateConversation(
        key: ChatConversationKey,
        transform: (ChatConversation) -> ChatConversation,
    ) {
        mutable.value = mutable.value.copy(conversations = mutable.value.conversations.map { if (it.key == key) transform(it) else it })
    }

    private fun message(
        kind: ChatMessageKind,
        channelId: Int?,
        sender: String?,
        body: String,
    ) = ChatMessage(
        id = nextId(),
        sequence = ++sequence,
        receivedAtMs = clockMs(),
        kind = kind,
        channelId = channelId,
        sender = sender,
        body = body,
    )
}
