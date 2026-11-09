# ADR 0032: Participant playback invalidation

Date: 2026-11-08. Status: accepted; offline/emulator evidence in B1.2.

## Context

B1.1 repeats a 4,320-sample (90 ms) loss of valid, unmixed B media when A is
muted. The real player currently clears every receive lease and decoder.
Mixed PCM and samples already submitted to the output are separate losses.
`AndroidPlaybackOutput.discard()` permanently silences that output, so reusing
it requires a different route/reference contract and is outside this change.

## Decision and migration

Keep one worker, the existing receive bounds, algorithms and public API.
`OpusAudioPlayer` is about 300 lines and owns Android focus/routing/lifecycle.
Do not add invalidation policy there: extract JVM-only `PlaybackAdmission`
(tickets around packet preparation) and `PlaybackInvalidation` (coalesced worker
commands and the write revision) into independent small files with independent
tests. Keep Android ownership in the player. A later split of Android control is
only justified by a distinct responsibility, not a line-count target.

Global mute, focus interruption, route protection, route changes and stop retain
global invalidation. Crossing zero for a participant revokes only that client's
in-flight tickets and queued leases. Tickets live only for packet preparation;
no permanent per-client admission history accumulates. A mute/unmute pair must
not revive an old ticket; another client's ticket remains valid.

The worker coalesces participant commands; a pending global command supersedes
them and retains the focus-interruption bit. Revision checks still block the
next old mixed write. The worker removes affected revoked talkers and mixer gains;
already-ingested post-unmute generations have reset their decoder and are kept.
It never deletes newly admitted post-unmute input. B's input, encoded pending,
PCM leases, decoder and gain envelope remain. Reset only the shared limiter at
the mixed-output discontinuity, without changing its algorithm.

Share the output retirement path with global invalidation: discard pending mix
and render reference, close output, abandon focus, create a new timeline with a
new head origin. Failed close must block replacement. Participant commands do
not shorten an existing focus/output retry deadline. No media cache or initial
delay limit grows. Production has no measurement hooks or test branches.

## Validation and limits

Use independent JVM ticket/command/session/mixer tests, existing scheduling,
budget and ten-minute simulations, and the same real-player/native-Opus probe
from B1.1. Check exact valid B sample retention and decoder identity, partial,
zero and blocked writes, failed retirement, global supersession and reference
reset. Keep the existing Android old-write/close assertion unchanged.

Output retirement still discards the mixed block/HAL tail and may create a gap.
Report this separately from retained unmixed samples. No-flush 50% gap reduction
and physical/OEM listening/routing are not claimed. This round is offline and
emulator only. Rollback is the B1.1 conservative global invalidation path; no
stored data, settings, dependency or public API migration is needed.
