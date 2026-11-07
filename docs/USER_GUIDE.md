# User guide

[简体中文](USER_GUIDE_ZH.md) · [Install and upgrade](INSTALLATION.md) · [Troubleshooting](TROUBLESHOOTING.md)

This guide describes stable 1.0.0 (2026-11-04). Check the [README](../README.md)
and GitHub Release for downloads and tested coverage.

## Connect and join

Enter a server address, voice port, 3–30 character nickname and server password
if required. The usual voice port is `9987`; use the administrator's configured
voice port, not a ServerQuery port. A local TeamSpeak identity is generated on
first use; there is no project account to register.

The encrypted form remembers edits, including its password, without connecting.
Recent servers record only successful endpoints. Deleting a recent entry does
not clear the form or bookmarks. Disconnect before switching to another server.

On Channels, tap a row to expand its members; double-tap to join. Protected
channels prompt for a password. Search in the audio controls or More options
looks through loaded channel names/topics and nicknames. User-channel joins
resolve the current target again. Channel details and administration are absent.

The development branch also shows a Join action on each other channel and a
matching accessibility action; double-tap remains available. Protected joins use
the same password dialog. A connection/channel replacement invalidates a pending
target. The public 1.0.0/code14 APK still uses its existing gestures/search entry.

## Microphone and listening

| Mode | Capture and transmission |
| --- | --- |
| Off | Stops capture and discards buffered audio |
| Hold / push-to-talk | Captures and sends while the talk button is held |
| Continuous | Captures and sends continuously |
| Volume gate | Captures continuously, including silence; sends above the threshold with short onset/tail buffers |

Capture needs microphone permission. A quiet gate is still listening; choose Off
to stop capture. More options → Microphone settings controls input gain, RNNoise,
device echo cancellation and the gate threshold. In the development branch,
these four tuning choices survive service/process restart after saving finishes.
They cannot start capture, and a new service still starts in Hold mode.

The dialog distinguishes loading, unsaved changes, saving, saved and failure.
Slider release and closing settings request the latest save; edits interrupted
before saving completes may be lost. Retry a failed load/save, or use Restore
tuning defaults to replace the four stored choices. This never clears identity,
bookmarks or connection memory. A failed reset remains visible rather than
deleting unreadable storage. Public 1.0.0/code14 still uses service-memory tuning.

Choose routes from those Android reports as available. Headset, Bluetooth and USB
behavior depends on the device. AEC status reports Android's capability/activation,
not a guarantee of acoustic quality. User actions provide per-user mute and volume.
Listening mute is separate from turning your microphone off.

Available Bluetooth communication headsets are selected at session start and when connected.
Device removal or unexpected Bluetooth fallback mutes listening. Check the selected route and
unmute manually; reconnecting a headset keeps the safety mute. This affects listening, not the
microphone mode. Android callback and hardware latency still need physical-headset testing.

## Messages, private chats and pokes

Use Chat for the current channel. Send private message in user actions opens a
private conversation; the chat header switches conversations. Drafts and unread
positions are separate. Long-press to copy a message body. Closing a private chat
clears its retained messages and draft. Switching to Channels or Users and back keeps the reading
position and the choice to follow new messages. New messages do not move you
away from older history; use the new-message button to jump to the latest.
Ordinary reconnect and Activity recreation retain the position while the same
service session exists. A new manual connection starts fresh. If an old message
has left the bounded history, the view moves to a retained message nearby.

Send outcomes distinguish server acceptance, rejection and unknown results.
Acceptance is not a read receipt; unknown messages are never automatically resent.
Text input is limited to 1,024 UTF-8 bytes. Up to 20 conversations share 500
messages / 2 MiB; older records can be evicted.

Tap the hand icon beside a channel member, or Poke in user actions. The optional
reason is limited to 100 UTF-8 bytes. Confirming keeps the current page and gives
brief result feedback; opening the dialog does not send anything.

Incoming pokes show an in-app notice. Background/locked delivery uses a dedicated
notification channel with generic content. Notification permission, channel settings
and Do Not Disturb control presentation and sound. Returning shows a retained
pending notice. Chat/poke records and drafts stay only in service memory: ordinary
reconnect keeps them; manual exit, a new manual session, terminal failure or process
death clears them. Servers and recipients may keep received content.

## Server bookmarks

Choose a saved server on the connection form, or open Server bookmarks in More
options. Each card has a Connect action. Its menu holds Fill form, Edit and Delete;
deletion requires confirmation. Fill form replaces parameters without connecting.

Add bookmark prefills the current address, port and nickname; give it a name.
More settings contains default channel ID and opt-in password storage. Form
passwords are not copied automatically. Turning password storage off removes that
bookmark's passwords without clearing independent form memory. Up to 50 entries
are stored in a separate encrypted local vault.

In the development branch, Save current connection uses the active endpoint and
nickname, even if you edited the connection form meanwhile. Its current channel
is preselected. For a bookmark matching the connected endpoint, Choose default
channel lists names with parent paths; Use current channel selects it directly.
This stores the same channel ID as before. Passwords are still explicit opt-in
and are never copied from the active session. Changing the selected channel
clears its previously entered channel password.

Connection replacement, channel deletion or an out-of-date bookmark revision
keeps your draft and prevents a stale save. Choose again, clear the default
channel, or use the numeric field in More settings for deliberate offline/manual
editing. Choosing a channel does not grant permission to join it.

## Away, background and exit

The coffee-cup/clock icon opens Away and status. The icon gains emphasis after
server confirmation; pending/failed synchronization has its own status. Away does
not stop voice. Use the microphone/listening controls for that.

Home, app switching and screen lock retain the background session. More options →
Background voice check links to Android battery settings and an optional CPU-awake
preference. It cannot override all system/vendor restrictions. Background reconnect
restores listening first; stopped capture resumes when the app is visible.

Disconnect through the app or connection notification before leaving a server.
Task swiping can kill the process before a callback; the server may need to time
out the old session. After an identity connection-limit refusal, follow the wait
and manual retry guidance rather than retrying repeatedly.

## Privacy and help

Give the project a Star opens the public repository in an external HTTPS handler;
it does not star it automatically or send session data. Diagnostics leave the app
only when you copy and share them. Check logs, screenshots and clipboard content
before sharing. See [privacy](../PRIVACY.md), [support](../SUPPORT.md) and
[compatibility](COMPATIBILITY.md).
