# 0002: Ship a single-server pre-release first

**Status:** Accepted  
**Date:** 2026-08-15

## Context

The core risks are protocol compatibility, duplex audio latency, Android
foreground-service behavior, and reconnect correctness. Multi-server tabs,
bookmarks and chat would multiply lifecycle and persistence paths before those
risks are measured.

## Decision

The beta supports one active TeamSpeak 3 server, channel browsing/joining,
voice transmit modes, playback controls, and automatic reconnect. Connection
details remain in memory except for the encrypted TeamSpeak identity.

## Consequences

The state model is smaller and the service can enforce one session epoch. Users
must keep another client available while testing important servers. New
multi-server or persistence work requires a new decision record and migration
plan.

## Re-evaluate when

M9 diagnostics, M10 long-running audio/reconnect tests, and M11 signed beta
release checks pass on the supported Android device matrix.
