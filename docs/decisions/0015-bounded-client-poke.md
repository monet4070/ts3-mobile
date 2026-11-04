# ADR-0015: Bounded TeamSpeak poke within the chat session

Date: 2026-10-23. Status: accepted and implemented for the user's request for “戳一下”.

The pinned ts3j revision exposes `clientPoke(clid, msg)` and `onClientPoke`.
Use a separate typed poke port/event, with the existing response outcome model,
captured socket, identity recheck and bounded command queue. Poke is encoded as
`clientpoke clid=... msg=...`, never as a normal private text command. A reason
may be empty; the application conservatively limits outgoing reasons to 100 UTF-8
bytes. Incoming reasons retain the existing 16 KiB bound. No automatic retry.

User actions open a non-saveable reason dialog. Its recipient captures the manual
scope, attempt, client ID and exact stable UID. Missing/ambiguous identity, self,
changed attempt or reused ID prevents submission. One pending send is shared with
text. Locally claimed outgoing attempts and received pokes have a distinct message type in
the corresponding private conversation, preserving drafts and existing shared
conversation/message budgets. The latest retained incoming poke also presents an
in-app dialog; its dismiss action captures the message ID and cannot dismiss a
newer event. No sound, vibration, Android notification or extra permission is added.
Old callbacks, reconnect pause, terminal failure and manual exit retain the chat
ownership rules. Logs and saveable state exclude recipients and poke text.

## Decomposition before edits

ChatStore has 282 lines and already owns synchronized state publication. Adding
poke claims and reception inline would cross the preferred size boundary. Move
the pure conversation-capacity and attempt-invalidation operations to ChatConversation.kt, then share the
existing inbound routing and submission path with a typed message kind. Keep the
directory and retention policies separate. SessionChatController shares its one
submission worker rather than adding a competing poke worker. Ts3jTextCommands
shares its bounded execution primitive between text and poke, retaining the same
submission lock as channel moves. The formatted ChatStore is about 310 lines and
still has one synchronization/state-publication responsibility. If inbound event
families grow again, extract their normalization/routing next, leaving mutation
under the existing store monitor. Avoid adding a new layer solely to remove ten
lines; the extracted conversation transformations have independent tests.

The already-large coordinator/service/protocol facade receive only typed forwarding
and a shared captured-socket helper. Their previously recorded extraction order
remains unchanged; this feature does not rewrite connection or audio orchestration.
PokeSendDialog and PokeNoticeDialog own presentation separately from MainScreen.
Default interface implementations preserve existing adapter and fake source calls;
all modules rebuild together, with no precompiled ABI promise.

Verification uses pinned encoding/FIFO fixtures, fake server errors, timeout and
identity/attempt races, bounded state, PTT release and offline Compose dialogs.
The user chose offline validation for the current work; no live poke or extra test
message is authorized by this feature request.
