# ADR 0034: Session-bound connection monitoring

Date: 2026-11-08. Status: accepted; independent/behavior/Android wiring gates passed.

## Context and scope

ConnectionCoordinator exceeds 1,000 lines and mixes observing network changes,
transport activity and watch execution intervals with session ownership and
recovery decisions. Its tests exceed 1,500 lines and already share a private
fake host/session. First add behavior regressions there on the original code;
do not duplicate/move those fixtures just to reduce line counts. Independently
test the new small observer with a minimal protocol fake.

## Decision

Extract a JVM-only ConnectionMonitor. It owns default-network transition
classification, one bounded post-switch liveness observation and the watch loop.
Each watch/probe result carries the captured protocol session, listener and epoch;
probe results also carry network generation. It has no Service, state-mutating
host or reconnect/exit port. Its only external collaborators are the monotonic
clock, protocol session and a current-binding predicate/result consumer.

The coordinator keeps all jobs and launchIdentityWork, preserving registration
with IdentityConnectionLease and cancellation/actual completion ownership. It
validates observations before acting, retaining listener atomic claim, terminal
classification, reconnect policy and exit request ownership. A monitor reports
facts; it never disconnects, reconnects, changes microphone/playback or launches
an independent scope. A replaced listener/network cannot act on a replacement.

Keep watch 1,000 ms, execution-gap threshold 10,000 ms, integer-second conversion
1,000 ms, liveness timeout 5,000 ms, stable-session reset 30,000 ms and exit budget
2,000 ms. Voice silence and unknown transport remain healthy/unknown, with no
extra keepalive. Pending network capabilities are not loss; blocked pending is.
Negative clock gaps do not imply a failure. Each watch starts a fresh baseline.

No public protocol/binder API, stored format, setting or audio policy changes.
The original coordinator behavior suites remain the migration gate, especially
A→B→C, noncooperative late probes, offline waiting, transport closure/terminal
callback competition and task removal during observation. Rollback restores the
original inline observer code; no data migration is required.
