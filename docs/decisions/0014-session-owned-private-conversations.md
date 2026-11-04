# ADR-0014: Private conversations within the manual session

Date: 2026-10-23. Status: accepted, following the user's request to add private messaging.

Extend the existing text port with targetmode=1/client ID, retaining the bounded
single-attempt command path, error categories and no automatic resend. Channel
and private targets stay explicit. A private target carries its expected UID
when available; the protocol facade rechecks its snapshot before queue ownership.
App callback checks additionally capture the actual session and attempt.

Conversation state has one source of truth in ChatState: an always-present channel
conversation and at most 19 private conversations, with independent messages,
drafts and read positions. One global pending send and the existing 500-message /
2 MiB message-text budget cover all conversations. Drafts remain separately
bounded at 16 KiB each. A full conversation list shows a fixed warning; closing
private conversations frees capacity. Closing a nonempty conversation asks the
user explicitly because it discards that conversation's in-memory content.

Private conversations use exact nonblank UIDs without truncation or nickname
matching. Missing UID keys are limited to the attempt and the observed client-ID
occupancy; they close at attempt end. Snapshot leave/rejoin or identity changes
advance occupancy. A stable conversation is sendable only when exactly one
current participant maps to that UID. Offline or ambiguous targets retain content
and drafts read-only. An incoming private message must target our own client;
self echoes cannot confirm or duplicate outbound rows. Event UIDs determine the
conversation even if the current snapshot has already reused the client ID.
Messages lacking a usable sender identity/target are classified, never guessed.

## Decomposition and compatibility

The F1a ChatStore had 181 lines; adding directory resolution, shared eviction
and all conversation operations inline would exceed its responsibility and size
boundary. Extract PrivateChatDirectory and ChatRetentionPolicy first, then keep
ChatStore responsible for synchronized state publication. ChatState exposes the
active conversation as derived properties to preserve the channel UI/test seam.
The existing channel-specific binder method remains channel-only; a new generic
submit entry point handles selected conversations. Existing session and audio
lifecycle rules, identity/form/history formats and persistent storage do not change.
The already-large coordinator/service gain only snapshot and binder forwarding.

ChatConversationPicker owns switching and closing UI; participant actions only
open the selected user's conversation. Scroll positions use numeric in-memory
conversation IDs as saveable keys; positions, last handled sequence and follow
intent are scroll metadata. UID, name, bodies and drafts are never saved.
Closed conversation scroll buckets are removed to keep UI memory bounded.
The numeric bucket inventory also survives state recreation so closed buckets
can be removed after restoration. Online private titles include the current client
ID to distinguish identical display names without exposing UIDs.

An ID-only sender cannot prove identity across an unobserved leave/rejoin with an
identical snapshot. Missing-UID conversations therefore carry only the observed
occupancy guarantee within one attempt; no nickname-based identity claim is made.

## Verification

Use pinned command encoding and synthetic responses for Private, deterministic
UID/client-ID reuse and reconnect tests, all-conversation budgeting, unknown/self
event cases, and offline Compose user-menu/switching/unread/offline/close tests.
Real channel interoperability was separately verified on the connected Android
16 phone (F1A_DEVICE_2026-10-23.md). That two-message authorization is consumed;
additional live private-message testing requires separate explicit authorization.
