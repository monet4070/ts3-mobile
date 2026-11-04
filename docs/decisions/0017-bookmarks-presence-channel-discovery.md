# ADR-0017: bookmarks, own presence and channel discovery

Date: 2026-10-23. Status: implemented and offline validated; live-server acceptance open.

## Scope

The user selects recommendations 1, 3 and 4 only: named server bookmarks/quick
connect, away and server-visible mute state, and channel information plus channel/
user search with join actions. Identity import/export, friends/block lists,
moderation, connection-quality dashboards and file transfer are out of scope.
Earlier roadmap candidates do not expand this authorization.

## Architecture and compatibility

Bookmarks use an independent versioned AES-GCM vault/Keystore alias with explicit
save failures, bounded to 50 entries. Existing history/form/identity data formats
and keys remain unchanged; old installs begin with no bookmarks and no migration.
Each entry has a stable UUID and revision; updates/deletes reject stale revisions.
Passwords are opt-in, excluded from backup/transfer along with the whole vault,
and removed from a bookmark when remembering is disabled. This does not erase
independent form memory or identity. Loading/editing never connects automatically.
Quick connect is available while disconnected; switching servers requires ending
the existing session first using its existing Disconnect action. This preserves
bounded exit and prevents hidden interruption. Optional default channel IDs and
channel passwords use the existing channel-restore mechanism on initial connect.

Presence is a typed protocol command and separate service-owned controller. One
bounded worker submits clientupdate for away/message and input/output mute flags.
Input mute means explicitly OFF or unavailable capture, not PTT release or quiet
volume-gate listening. Output mute follows local playback mute. Local stop/mute
always takes effect even if server synchronization fails. Desired vs confirmed
state and pending/failure status are visible; latest desired state is coalesced,
old attempt callbacks are rejected, and reconnect applies current desired state.
Away text stays in service memory and is not logged/persisted. No automatic text,
poke or audio is sent by tests. State-setting commands only replay current desired
state on a fresh connection; uncertain submissions are not claimed as confirmed.

Channel snapshots add optional topic/description/limits and participant away fields
with defaults for source compatibility. Channel details use bounded channelinfo on
demand, scoped to the captured attempt and channel, with explicit unavailable/error
states. No HTML/BBCode execution or remote image loading. Search is local to the
current server snapshot, bounded, case-insensitive and covers channel names/topics
and user nicknames. Joining a found user's channel resolves their current channel
again; password-protected targets use the existing password dialog.

The existing bounded command submission runner will be extracted from
Ts3jTextCommands and shared with presence/channelinfo without changing its FIFO,
timeout, interrupt, claim or source-generation rules. Public protocol additions
have unsupported defaults so existing fakes remain source-compatible.

## Decomposition assessment before changes

MainActivity, TeamSpeakService, ConnectionCoordinator and Ts3jSessionClient already
exceed 300 lines. Add only narrow callbacks/state fields and extract new bookmark,
presence and discovery responsibilities into dedicated files. MainViewModel and
MainScreen are near 300 lines; independent bookmark controller/UI prevents them
owning storage or editor logic. ChannelPanel keeps existing channel gestures;
new discovery/detail dialogs are separate. Protocol parsing remains pure JVM.

## Validation

Deterministic vault codec/policy/controller tests, Android Keystore roundtrip,
pinned command encoding/timeout/stale-context tests, presence lifecycle tests and
Compose workflows with no real server. Run formatting, JVM tests, lint, Debug and
minified Release, identity probe, privacy and document-link checks. Real server
presence interoperability and channel permissions remain acceptance work.

## Results

See docs/testing/FEATURES_134_2026-10-23.md for 388 JVM, 73 full emulator and
28 narrow-screen checks, unchanged lint warning counts and the minified identity
probe. InputDialog consumes system/IME insets outside the card once, retaining
its title/actions while constraining the scrollable body; search has an IME Search
action. No production dependency, permission or release-version change is required.
