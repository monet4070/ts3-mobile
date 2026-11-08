# ADR 0033: App-owned appearance preference

Date: 2026-11-08. Status: accepted; offline/emulator validation complete.

## Context and boundary

The existing warm light/dark tokens and `Ts3MobileTheme(darkTheme = false)`
already support both palettes. The unset default must remain light. System
darkness is consulted only for an explicit System selection.

MainActivity and MainScreen exceed 300 lines. Keep this change to one optional
menu callback, a private Activity launch and a shared theme wrapper. Do not move
connection/capture responsibilities during a visual preference change. A new
small appearance screen owns only the three choices and save/error feedback.
Broader activity/screen decomposition remains a separate migration by UI
responsibility; it must preserve service binding and retained form/chat state.

## Decision

Application owns one appearance controller and Preferences DataStore, separate
from service-owned microphone settings and all identity/form/bookmark files.
Store schema 1 contains one string mode: light, dark, system. Missing storage
means Light; unknown version/value or physical corruption is reported and never
silently overwritten. Explicit reset can replace an unsupported logical schema;
physical corruption is retained if DataStore cannot edit it. No backup or cloud
sync is enabled. No dependency, permission or existing format changes.

Serialize one read/write at a time and disable choices while loading/saving.
Selection applies visually while saving; failure remains visible with retry.
Read failure keeps Light until explicit retry/reset, rather than auto-writing
defaults. The controller has no service/audio port. Switching choices updates
Compose and system-bar icon contrast without recreating the Activity or service.
Android may recreate an Activity on a system configuration change as usual.

`ApplicationTheme` is used at Main, IdentityManagement and Appearance Activity
roots; previews and isolated component tests keep the explicit raw theme API.
The initial Android launch surface remains the existing light default until the
asynchronous preference read completes; no no-flash startup claim is made.

## Validation and rollback

Independent controller/store JVM tests cover persistence, defaults, schema/value
errors, corruption preservation, delayed reads, write failure/retry and busy
selection. Compose/Activity tests cover three modes, configuration/recreation,
all roots, bars, localized large text, menu/dialog/IME and no service/capture
side effects. Offline/emulator only. Rollback removes the optional UI/wrapper;
the separate appearance file can remain unused without touching user identity.

Final settled-keyboard checks found an existing InputDialog bug: the native
visible frame ended at 1019 px while actions extended to 1102 px. Native window
offsets differ from Activity/display insets; merely adding a navigation inset or
changing decor fitting did not resolve it. Keep the existing dialog API/behavior
and add `DialogViewport`, a small Android-to-Compose adapter that observes this
view's actual visible display frame, unions its bottom occlusion with safeDrawing
and removes its layout listener on disposal. Already-resized windows receive no
extra occlusion. A real-IME regression waits for stable insets, checks both action
bounds against the visible frame and keeps the focused field visible. Fixed
actions end at 914 px. No SDK guess, global window flag or test-only branch.
