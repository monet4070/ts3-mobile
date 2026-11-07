# ADR-0025: private message alerts and notification navigation

Date: 2026-11-07. Status: implemented and offline/emulator validated.

Only accepted ordinary incoming private text in the current connected session
can request a background alert. Keep a global revision and the latest revision
per retained conversation, independent of message retention and unread state.
This bounded summary survives StateFlow conflation without retaining content.
Disconnect invalidates a notification generation and resets these revisions.
An observer starts at the current high water mark, uses a fixed two-second
window, and clears pending work synchronously on foreground entry. Subsequent
updates to the same background summary are silent. Foreground tabs keep their
existing unread indicators.

Use an independent Android channel and a generic public lock-screen message.
Explicit immutable PendingIntents carry only a random token as their data URI;
each token has a distinct PendingIntent identity. A service-owned registry is
bounded to 16 entries with five-minute expiry. Resolution is one-shot and checks
the manual session token, disconnect generation and all captured conversation
IDs. Invalid tokens only open the application. Never connect or mark read as a
side effect of resolution. Android permission, channel and DND policy remain
authoritative; provide a system channel settings entry without another prompt.

Activity onCreate/onNewIntent forward to a small entry controller retained by
the ViewModel. It keeps at most one request before binding; newer intents and
explicit user navigation replace/cancel it. Compose validates again, selects a
single conversation or opens the picker for a multi-conversation summary, and
consumes the request. Only visible message geometry advances readThrough.

Large-file assessment: ChatStore (>300), MainActivity (>300), TeamSpeakService
(>600), MainViewModel (>300) and MainScreen (near300) receive only fields and
narrow lifecycle/callback wiring. Revision helpers, alert timing, registry,
Android notification construction and navigation ownership live in separate
files. Existing defaulted model/UI parameters preserve source usage; no wire,
vault, dependency or configuration schema changes. A broader decomposition is
deferred until its own behavior-preserving migration and regression plan.

Validation uses synthetic events, fake clocks and emulator UI/system entry
points. Physical lock-screen sound, OEM background policy and real-server
delivery are outside this round at the maintainer's request.

See [A4 evidence](../testing/IMPROVEMENT_A4_2026-11-07_ZH.md): 282 application JVM
checks, a 168-case full Android suite (167 pass, one external policy fixture
skipped), final targeted checks and both real disabled-policy fixtures. OS policy
revocation is prepared outside instrumentation because it terminates the app.
