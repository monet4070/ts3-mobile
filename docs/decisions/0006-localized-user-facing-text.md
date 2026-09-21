# 0006: Resolve user-facing text at the Android boundary

**Status:** Accepted (implemented 2026-09-21)
**Date:** 2026-09-21

## Context

All user-facing text was hard-coded Simplified Chinese spread across three
modules, so the app could not present any other language. An external
contributor opened a fork branch (`polleyr:fb_localization_english`) that
replaced each literal with an English one. That patch makes the app usable for
English speakers but drops Chinese entirely and leaves the next language in the
same position.

The text was not only in Compose code. Three layers produced it:

1. **UI** (`app/ui/*.kt`) — labels, content descriptions, empty states. A
   `Context` is always in scope, so `stringResource` applies directly.
2. **Service** (`ConnectionCoordinator`, `ReconnectEngine`,
   `MicrophoneController`) — errors and reconnect progress, formatted into
   `TeamSpeakServiceState.channelError`, `microphoneError` and
   `ConnectionStatus.detail` as finished strings.
3. **Audio infrastructure** (`audio-opus`) — route names such as
   `AudioRouteOption.label` and routing failures in `AudioRoutingState.error`,
   built inside a module that owns device arbitration, not presentation.

Layers 2 and 3 are the real constraint. Formatting text there bakes in a locale
at the point where the app has the least reason to care about one, and
`AudioRoutingState.SystemRoute` is a companion-object constant with no `Context`
available at all. It also conflicts with AGENTS.md §5, which asks for errors to
be converted at the boundary rather than carried as prose.

## Decision

Make English the default (`values/`), add `values-zh/` for Chinese, and stop
producing user-facing text below the Android boundary.

### Messages become data

`UserMessage` (app `service` package) is a sealed interface with one variant per
message, carrying the arguments the wording needs:

```kotlin
sealed interface UserMessage {
    data object WaitingForNetwork : UserMessage
    data class ReconnectScheduled(val seconds: Long, val attempt: Int) : UserMessage
    data class ChannelJoinFailed(val cause: String) : UserMessage
    // …
}
```

`TeamSpeakServiceState.microphoneError` and `channelError` hold `UserMessage?`
instead of `String?`. A new `statusMessage: UserMessage?` carries the
app-generated line for the current status.

`audio-opus` gets the same treatment without depending on the app module:
`AudioRoutingError(kind, cause)` replaces the error string, and
`AudioRouteOption` drops `label` in favour of the `kind` it already had plus a
`deviceName` for detachable devices. The app combines the localized kind name
with the device name.

Two internal exceptions, `NotConnectedException` and `SessionExpiredException`,
replace `check`/`error` calls whose Chinese messages previously reached the user
through `conciseMessage(error)`. This distinguishes a business conflict from an
external failure, which is what §5 asks for.

### One resolver

`app/ui/UserMessageText.kt` holds the only `UserMessage` → text mapping, used by
both the Compose tree and `TeamSpeakService.buildNotification`, so the
notification and the status line cannot drift apart.

### Status detail stays technical

`ConnectionStatus.detail` lives in the Android-free `ts3-protocol` module and
already carried English technical text (`conciseMessage(error)`, server
disconnect reasons). It keeps that role. The UI prefers `statusMessage` and
falls back to `detail`, and variants like `ReconnectFailed(cause)` embed the
technical detail as an argument rather than pre-formatting it.

Because status and message are now two fields, `withStatus(status, message)`
replaces both together so a message cannot outlive the status that produced it.
Every app-layer status update goes through it.

## Consequences

- Adding a language is a new `values-xx/` directory; no Kotlin changes.
- `audio-opus` no longer contains user-facing text, matching its role as
  infrastructure.
- Route ordering now sorts by device name rather than by the localized label, so
  the order no longer shifts with the display language.
- `AudioRouteOption.label` is gone. It is a module-public type, but only the app
  consumed it; `resolveLabel(context)` is the replacement.
- Six locale-independent entries (brand name, format separators, the example
  hostname, the port range, the failure sentinel) are marked
  `translatable="false"` so lint's `MissingTranslation` stays enforcing.
- Unit tests can assert on message identity (`UserMessage.WaitingForNetwork`)
  instead of matching formatted prose, which is what the three new
  `ConnectionCoordinatorTest` cases do. Resolution itself is not unit-tested —
  the app module has no Robolectric — so it is covered by lint plus the existing
  device checks.

## Alternatives considered

- **Take the fork's patch as-is.** Rejected: it drops Chinese, and the service
  and audio layers keep formatting text, so the next language repeats the work.
  The English wording from that branch is reused where it fits.
- **Give each module its own string resources and a `Context`.** Rejected:
  `audio-opus` would gain a presentation responsibility, and
  `AudioRoutingState.SystemRoute` has no `Context` to use.
- **Store `@StringRes` ids in the service layer.** Rejected: simpler, but it
  puts `R` references in the state machine and makes the JVM tests assert on
  integer ids instead of on meaning.

## Re-evaluate when

- A third language lands, which would show whether the plurals split (English
  `one`/`other` vs. Chinese `other`) needs more structure.
- The app module gains Robolectric or an instrumentation test for the status
  line, at which point resolution itself should be asserted directly.
