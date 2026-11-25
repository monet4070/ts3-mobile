# TS3j voice timing development patch

For an independent source build, use `python3 tools/build-ts3j-from-source.py --fork <checkout> --out <local-maven-directory>`.
The tool reads the exact pinned commit, checks all four compile JARs against
SHA-256, and can use `--offline` with the verified Gradle cache. Use
`-Pts3jSourceRepository=<local-maven-directory>` to test this baseline without
JitPack. `--voice-timing` instead applies the patch to the same full sources and
runs the UDP loopback probe; put that output in a **separate** directory and use
`-Pts3jVoiceTimingRepository=<local-maven-directory>` for QA. Neither path publishes
an artifact or changes the default release coordinate. A reviewed fork release
must use a new tag rather than publishing either directory under `ts3mobile-1`.

Apply `0001-voice-udp-timing.patch` to the pinned fork's `ts3mobile-1` source
commit `db57d60c989e399626aa16d921390f5033e6cdeb`, in a disposable checkout.
It timestamps normal and whisper voice immediately after successful UDP receive,
propagates that stamp to the decoded body, and timestamps normal voice immediately
after successful UDP send. The peer receiving audio is not implied by this stamp.
Control packets get no stamp. This patch changes no wire data, retry, scheduling,
or transport behavior; the production dependency is still unchanged.

`VoiceUdpBoundaryProbe.java` is a standalone Java 8 loopback check, not shipped in
any APK. Compile the patched classes and probe against the pinned jar and its
runtime dependencies; put compiled classes before the original jar:

```sh
# Set TS3J_SOURCE to the disposable pinned checkout, TS3J_CP to the pinned jar plus
# dependencies (BouncyCastle, ini4j, Commons Lang and dnsjava), and PATCH_DIR here.
git -C "$TS3J_SOURCE" apply --check "$PATCH_DIR/0001-voice-udp-timing.patch"
git -C "$TS3J_SOURCE" apply "$PATCH_DIR/0001-voice-udp-timing.patch"
out=$(mktemp -d)
source="$TS3J_SOURCE/src/main/java/com/github/manevolent/ts3j/protocol"
javac --release 8 -cp "$TS3J_CP" -d "$out" \
  "$source/NetworkPacket.java" "$source/packet/PacketBody.java" \
  "$source/socket/client/AbstractTeamspeakClientSocket.java" \
  "$source/socket/client/LocalTeamspeakClientSocket.java" \
  "$PATCH_DIR/VoiceUdpBoundaryProbe.java"
java -cp "$out:$TS3J_CP" VoiceUdpBoundaryProbe
```

The probe expects `TS3J_VOICE_UDP_LOOPBACK_OK`. To prepare the same artifact for
the Kotlin adapter without modifying the checkout or publishing a new tag:

```sh
python3 tools/prepare-ts3j-voice-timing.py --fork /path/to/ts3j
./gradlew -Pts3jVoiceTimingRepository="$PWD/build/ts3j-voice-timing-maven" :ts3-protocol:test :app:assembleRelease
```

The script verifies the cached locked baseline JAR and POM, applies only the
timestamp patch to exact source, runs the loopback smoke and writes the artifact
hash. Without the explicit property, Gradle uses the original locked dependency
and the app reports its UDP stamps as unavailable. The smoke does not imply
network arrival at a real peer or acoustic latency. Do not publish the local
overlay under the existing immutable tag.

## Handshake-width fix candidate

For TS3 Mobile 1.3.0, keep `0002` as an opt-in candidate and ship the original
`ts3mobile-1`. The encoder probe is not a live handshake check. Promote only
after maintained-fork review and repeated real-server interoperability on a
separately tagged dependency; never replace `ts3mobile-1` in place.

`0002-handshake-unsigned-width.patch` adjusts only the fixed-width unsigned value
encoding in the existing handshake. The companion Java probe checks the numeric
encoder without running a network handshake. It is not packaged in the artifact.

```sh
python3 tools/build-ts3j-from-source.py --fork /path/to/ts3j \
  --out build/ts3j-width-candidate-maven --offline --handshake-width-fix
./gradlew --offline --no-build-cache \
  -Pts3jSourceRepository="$PWD/build/ts3j-width-candidate-maven" \
  :ts3-protocol:test :app:lintRelease :app:assembleRelease
```

Use a new or empty output directory. The build records the candidate patch and
JAR SHA-256 and verifies the same locked inputs. It does not change the default
artifact, rewrite the fork checkout, or publish anything. Do not combine it with
the voice timing variant or replace the immutable tag. Omit the Gradle property
to return to the default dependency.
