# ADR-0022: session-scoped chat navigation

Date: 2026-11-07. Status: implemented and offline validated.

The cross-main-tab regression fails on the baseline: ChatConversationContent
owns its SaveableStateHolder inside the conditional CHAT branch. Conversation
switch/restoration tests did not exercise Channels/Users round trips. A new
manual session also retains the previous selected main tab.

Keep a session saveable provider in ConnectedContent, with the conversation
holder outside the conditional tab. A random, non-personal token generated at
ChatStore.begin identifies each manual session, including across service
instances that reuse the same numeric scope. Reconnect preserves it; clear or a
new manual connection retires it. Only numeric message sequence/offset and UI
metadata enter saved state, never bodies, drafts, UID or nicknames.

Restore scroll from a message sequence anchor rather than only an index, since
bounded retention can evict earlier messages while Chat is not composed. A
missing anchor falls forward to a retained message, or back to the last retained
message when no later one exists. Hidden Chat stays uncomposed and cannot mark
messages visible. Keep existing follow-tail behavior and service-owned selection.

ChatStore already exceeds 300 lines; add only token generation and a defaulted
trailing ChatState field. Put UI state ownership in its own small component;
do not refactor chat receipt/retention or the connection coordinator. This is an
additive source-compatible Kotlin model change; no persistence/wire migration
or claim of binary compatibility for separately compiled consumers is made.

Validation: independently fail the old implementation, then test main tabs,
private chat, saved-state restore, reconnect, manual replacement, service token
reuse, closure and retention eviction. JVM token tests verify lifecycle semantics.
See [current evidence](../testing/IMPROVEMENT_A1_Q1_2026-11-07_ZH.md);
the full app suite passes137 tests and the CI selection passes57 tests three
times locally. Hosted CI remains tied to the resulting PR commit.
