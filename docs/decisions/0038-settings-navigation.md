# ADR 0038: Separate settings from the session menu

- Status: Accepted
- Date: 2026-11-20

## Decision

- Keep session actions, bookmarks and one Settings entry in More options. Use
  one Share invite entry for the existing server and current-channel share actions.
- Keep Settings and its microphone subpage inside `MainActivity`, using the app's
  existing theme and session binding. Rows are whole touch targets; grouped cards
  and the app-bar back control remain usable with large text.
- Preserve the main screen's saveable UI state while Settings is visible. Leave
  settings for a private-message notification destination so chat navigation still
  handles that request. Background voice and diagnostics are full-screen child
  pages. Appearance previews use the same palettes as the app. Identity and
  update activities use the shared back arrow while retaining their own state.
  In full release notes, the arrow and system Back return to the update summary.
  Clear action labels stand alone; show an explanation only when needed, such as
  for an unavailable control or a safety warning.
- Offer the local test in the microphone subpage, including offline. Navigating
  away disposes the trial UI; entering settings never starts the test or enables
  any microphone mode. Connection and capture guards are specified in ADR 0037.
- Use bordered, filled, icon or full-row controls in settings and dialogs. Keep
  the compact, borderless audio-route selector and Join action on the connected
  channel screen. The button-style check allows only these two exceptions.

## Consequences

There is no new navigation dependency, persisted settings schema, manifest entry
or change to connection, audio or notification policy. Tests use a local session
fixture to cover navigation independently of external servers.
