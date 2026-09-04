# 0001: Keep Android, protocol, and audio in separate modules

**Status:** Accepted  
**Date:** 2026-08-15

## Context

The client needs Android lifecycle and permissions, a JVM TeamSpeak protocol
adapter, and native low-latency audio. Mixing these concerns makes protocol
regression tests depend on Android and makes audio changes risky to review.

## Decision

Keep three Gradle modules:

- `app` owns Android UI, foreground-service lifecycle, permissions and use-case
  orchestration.
- `ts3-protocol` owns immutable models, protocol ports and the ts3j adapter.
- `audio-opus` owns JNI codec/denoising and Android audio I/O.

Cross-module calls use small interfaces (`Ts3SessionClient`,
`EncodedVoiceSource`) and immutable data classes.

## Consequences

JVM tests can validate channel ordering, configuration and failure mapping without
an online server. Native audio tests remain isolated to the audio module. The
Android service is still an orchestration boundary and should be decomposed
incrementally rather than through a risky rewrite.

## Re-evaluate when

Multi-server sessions, background account synchronization, or a persistent chat
store are introduced. Those features may justify an application/use-case module
and a database, but not before their data ownership is documented.
