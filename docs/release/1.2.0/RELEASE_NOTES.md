# TS3 Mobile 1.2.0

**2026-11-20 · versionCode 16 · Android 8.0+**

## Voice and Bluetooth

- **Bluetooth first-utterance protection:** When a confirmed Bluetooth output is still waking up, TS3 gives it more time to start instead of prematurely rebuilding the track and discarding the opening audio. Ready outputs are not delayed. Headset loss still triggers safety muting.
- **Switch playback profiles live:** In **Settings → Voice playback**, choose **Balanced (default)** or **Stability first**. Stability first buffers a little longer to tolerate late voice packets. Saving resets incoming playback immediately without leaving the server or channel or restarting the microphone.
- **Voice alongside media:** TS3 no longer actively takes audio focus or puts Android into call mode. Incoming voice uses media volume and can play alongside music and video. Bluetooth listening prefers the phone microphone.

## Everyday use

- **Microphone controls:** Tap the microphone icon in the voice controls to adjust input gain without enabling capture. Microphone settings also offers a local input test that does not transmit to the channel.
- **Settings, notifications and chat:** Common options live on a dedicated Settings page. Chat switching is simpler, and the connection notification can mute, resume listening or disconnect. Reviewing a safety-muted route still requires returning to the app.
- **Invites:** Open, paste and share TeamSpeak server and channel invites. An invite shows its destination for confirmation before connecting.
- **Diagnostics and updates:** Load audio diagnostic history without blocking the service main thread, and distinguish update-server failures from invalid release metadata.

## Upgrade and validation

- On 1.1.0, open **Check for updates → Download APK**, then confirm the installation from your browser. The signed APK, `SHA256SUMS.txt` and `BUILDINFO.json` are attached to this release. The package and signing identity are unchanged. Disconnect first; do not uninstall or clear data.
- Bluetooth cold and warm startup were compared on a Xiaomi 14 running Android 16, and the candidate was listening-tested on that phone. This does not guarantee uninterrupted audio on every headset or network. Stability first can tolerate late packets but cannot recover packets that never arrive. Mark any audible interruptions in Diagnostics and export the JSON when reporting them.
