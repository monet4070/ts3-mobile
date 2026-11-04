# 0012: Advance feature work while physical-device acceptance is deferred

**Status:** Accepted for development sequencing; individual feature designs remain proposals

**Date:** 2026-10-23

## Context

Background connectivity, network-switch recovery, duplex voice and recent
endpoint history are implemented. ADR-0002 and the older continuation plan
sequenced new features after completion of the physical-device matrix and signed
beta preparation.

The user cannot currently provide convenient device/server/audio test conditions
and explicitly requested a next phase organized around new application features.
Treating the outstanding device rows as the next development milestone would
prevent useful progress and misrepresent existing capabilities as missing work.

## Decision

- Track feature development separately from release-readiness evidence. Physical
  acceptance remains deferred and accurately reported; it is not a prerequisite
  for feature design or implementation.
- Keep one active server, existing session-generation guards, the current module
  boundaries, encrypted identity and established background/microphone policies.
- Propose channel text chat first, followed by private chat, named server
  favorites and voice activation. Small appearance/data-management features can
  ship independently. This ordering is a recommendation, not approval of every
  proposed feature or a claim that implementation has begun.
- Use pinned ts3j text APIs and existing RNNoise probabilities where applicable.
  Prefer bounded in-memory chat state before adding persistent message storage.
- New favorites use a separate versioned encrypted store with an explicit
  migration and deletion policy; existing identity, history and form formats
  remain intact. Record concrete storage/API decisions with each implementation.
- Verify deterministically with JVM fixtures, fake sockets, controllable time and
  offline Compose fixtures; run emulator checks when the environment supports them.
  Report live-server, real-device and subjective audio validation separately.
- Keep production signing, identity continuity and the outstanding physical
  matrix as release tasks. Do not label an unverified build as fully accepted
  merely because feature tests pass.

This changes ADR-0002's sequencing restriction for the newly requested feature
work. It does not introduce multi-server sessions, silently change stored data,
or mark any historical release checkbox passed.

## Consequences

Development can continue without asking the user to supply hardware now. The
first concrete feature slice is channel text chat with bounded messages, explicit
send outcomes and no automatic resend. Private chat, favorites and voice
activation remain separate slices with their own regressions and compatibility
notes.

Long-running physical behavior and real-server interoperability remain known
validation gaps. Feature implementation and release qualification must be
reported as different outcomes.

Detailed scope and sequencing:
[next-phase feature proposal](../plans/NEXT_PHASE_2026-10-23_ZH.md).
