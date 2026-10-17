# ADR 0010: TailSync warm cream visual language

## Decision

Rebuild the Android presentation layer using the warm cream appearance of the
user's running TailSync: paper canvas, ivory surfaces, terracotta accents,
restrained corners and fine Lucide outline icons. Reference:
https://github.com/monet4070/TailSync and its Canvas palette
(`themes/canvas-atelier/theme.json`). Headings use Android's serif family;
controls use the system sans-serif family. Android text sizes and 48 dp touch
targets take precedence over desktop density.

The app defaults to the requested light cream palette even when Android uses
dark mode. Coordinated warm dark tokens remain available through the explicit
theme parameter for previews. No wallpaper-driven colors are applied.

Keep the service state, protocol/audio APIs, connection validation, channel
gestures, participant controls and microphone transmission callbacks compatible.
Successful host/port history is managed by the imported history store. The
presentation never persists passwords, nicknames or identity material.

## Decomposition assessment

The existing UI is already split by responsibility. Connection layout and field
definitions will be separated before adding the new presentation. Status
surfaces and shared visual primitives get focused files. Channel password entry
will be extracted if channel layout would cross the approximately 300-line
review boundary. No service or protocol decomposition is needed for this work.

## Integration with current remote behavior

Merge remote revision `a6ae68e`, including ADRs 0007-0009, while keeping their
service, history-store and protocol implementations unchanged. The UI passes
the new history and background-runtime callbacks through to the existing
application boundary. Server-history rows get a focused component; the resumed
microphone hint is preserved in the warm microphone surface. Opening the
background dialog releases push-to-talk before presenting the modal.

The remote coordinator and existing service/protocol files still exceed the
preferred review boundary. This merge does not add responsibilities to those
files. Keep their concurrency-sensitive ownership and teardown logic intact;
any later listener/transport decomposition should be a separate change with
the imported race tests retained.

## Verification after integration

Use offline Compose fixtures for connection validation/cancellation, channel
expansion and password joining, participant controls and push-to-talk release.
Render connection and session screens in both palettes, plus Chinese and large
text. Run Kotlin formatting, unit tests, Android Lint, build and the repository
privacy scan. Identify Lucide 0.468.0 and ship its license with the vector assets.
