# ADR 0034: User-initiated GitHub update checks

- Date: 2026-11-09
- Status: implemented and locally verified; not published

## Scope and architecture

The maintainer requested an in-app update checker, with user choice over including
prereleases. The existing app has no updater. Published releases already include
`BUILDINFO.json` with the Android version code, APK name/hash and signing certificate.
The connection service, protocol and audio modules have no role in checking updates.

Add a private updates Activity, an independent state controller, a small HTTPS
adapter and a channel preference store inside `app`. Opening the page is the
explicit check action; rotation keeps the same controller. There is no startup,
background or periodic check, APK installation permission, silent download or install.
The browser handles user-requested APK downloads and Android handles installation.

MainActivity and MainScreen exceed approximately 300 lines. Keep this change to
one navigation callback and menu item; do not add HTTP or update state to them.
Further growth should extract their navigation composition separately, preserving
their existing service binding and PTT lifecycle tests before moving those duties.
Each new component stays below 300 lines and has one responsibility.

## Release selection and trust boundaries

- Stable is the default. Users may include prereleases and persist that choice in
  a new `ts3_updates` DataStore, without touching existing preferences.
- Stable checks GitHub's latest published stable release. Including prereleases
  examines up to 100 recent public releases and compares the most recently published
  non-draft candidate with GitHub's latest stable, selecting the higher versionCode.
  Future published APK version codes must increase across both
  channels; editing a release body must not act as a version update.
- Compare `BUILDINFO.json` versionCode, never lexicographic names or tag strings.
  Equal names with higher codes are updates; lower codes are never offered as upgrades.
- Require matching release assets and bounded metadata from this repository. Only
  HTTPS official repository release URLs and GitHub's asset redirect hosts are used.
  Release descriptions are native Markdown display text, never instructions or executable content.
  Markwon 4.6.2 and its tables/tasklist/strikethrough extensions render headings,
  emphasis, lists, quotations and code without an HTML/image-loading plugin or WebView.
  Links and images retain labels/alt text but cannot navigate or fetch remote content;
  official release/download actions remain the only browser entry points.
- Show installed/release compatibility separately: unsupported Android versions or
  different app IDs/signatures must not promise an in-place upgrade. Never suggest
  uninstalling or clearing user data to resolve incompatibility.
- Network, rate-limit, missing release and malformed metadata are distinct outcomes;
  failed checks never claim that the app is current. Requests and responses are
  bounded; no identity, server settings, chat content or recordings enter requests.

## Compatibility and verification plan

Existing constructors, storage formats and app version/signing configuration remain
unchanged. The only new stored setting is the user's update channel. Public GitHub
assets are read-only; this task does not publish releases or alter tags.

Use independent JVM tests for comparison, trusted URLs, request/controller races and
preference failures, plus offline Android fixtures for JSON parsing, preference
persistence, menu/UI states, large text and both languages. Any live GitHub check is
separate from deterministic tests. Run formatting, Lint, compilation, JVM tests and
applicable Android checks, then record actual results in a testing document.

The update page separates channel settings, release status/actions and note preview.
Close remains in the fixed header. Expanding notes opens a body reader with a fixed
collapse action; collapse or Back returns to the summary's retained scroll position.
The reader choice survives recreation and resets when the release result changes.
Markdown rendering and the reader live in separate small files rather than adding
parsing or scrolling duties to the update controller.

Actual results and remaining limits are recorded in the
[update-check verification](../testing/UPDATE_CHECK_2026-11-09_ZH.md).
