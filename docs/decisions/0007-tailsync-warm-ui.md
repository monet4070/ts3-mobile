# ADR 0007: TailSync warm cream visual language

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
No connection settings, credentials or identities are persisted by the UI.

## Decomposition assessment

The existing UI is already split by responsibility. Connection layout and field
definitions will be separated before adding the new presentation. Status
surfaces and shared visual primitives get focused files. Channel password entry
will be extracted if channel layout would cross the approximately 300-line
review boundary. No service or protocol decomposition is needed for this work.

## Verification

Use offline Compose fixtures for connection validation/cancellation, channel
expansion and password joining, participant controls and push-to-talk release.
Render connection and session screens in both palettes, plus Chinese and large
text. Run Kotlin formatting, unit tests, Android Lint, build and the repository
privacy scan. Identify Lucide 0.468.0 and ship its license with the vector assets.
