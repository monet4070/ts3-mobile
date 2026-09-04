# 0004: Take ownership of the ts3j dependency before a stable release

**Status:** Partially implemented (Step 1 pending; Steps 2–4 done)
**Date:** 2026-08-26
**Last updated:** 2026-08-26

> **Implementation note (2026-08-26).** Steps 2, 3 and 4 are complete and validated:
> all three Gradle modules have `dependencyLocking` in `LockMode.STRICT` with committed
> `gradle.lockfile`s, the four ts3j transitives are capped by explicit constraints, and
> the expanded ProGuard keep rules pass a release build (`./gradlew build
> :app:assembleRelease` is green with R8 `isMinifyEnabled = true` active, no new R8
> warnings beyond the pre-existing default-config constructor notices). A negative test
> confirmed STRICT enforcement: raising the bcprov constraint to 1.70 fails
> `:ts3-protocol:compileKotlin` with a version-constraint conflict. Step 1 (forking ts3j)
> remains open and requires maintainer action on a repository we control; it is the only
> remaining blocker for this ADR.

## Context

`ts3-protocol` depends on `com.github.Manevolent:ts3j` through JitPack, pinned to a single
upstream commit hash in [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml):

```
ts3j = "db57d60c989e399626aa16d921390f5033e6cdeb"
ts3j = { module = "com.github.Manevolent:ts3j", version.ref = "ts3j" }
```

The pin is commit-addressed, so JitPack cannot silently replace the artifact, and the current
`Ts3jSessionClient` facade isolates most protocol churn behind `Ts3SessionListener`. But three
release-relevant gaps remain:

1. **Upstream availability is outside our control.** The JitPack build is triggered from the
   `Manevolent/ts3j` GitHub repository. If that repository is renamed, archived, force-pushed,
   or if JitPack's build for it disappears, a clean clone of this repository can no longer
   resolve its only protocol dependency. `README.md` already states "A maintained fork and
   dependency locking are still required before a stable product release."
2. **Transitive dependencies are not pinned or audited.** The cached POM pulls
   `org.bouncycastle:bcprov-jdk15on:1.67`, `commons-lang:commons-lang:2.6`,
   `dnsjava:dnsjava:2.1.8`, and `org.ini4j:ini4j:0.5.1` with no Gradle dependency-locking or
   verification metadata in the repository. A different consumer can override any of these to
   a higher version, and a supply-chain compromise of any one of them would ship inside the APK.
3. **R8 only knows the classes we remembered to keep.** `app/proguard-rules.pro` keeps
   `com.github.manevolent.ts3j.**` and silences BouncyCastle warnings. The transitive set is
   small today, but any future ts3j version that adds reflection on a class outside the keep
   rule would silently break the minified release build.

## Decision

Before declaring the M11 release candidate, take ownership of the ts3j artifact and lock its
transitive graph. Concretely, in this order:

### Step 1 — Publish a forked, versioned artifact

Fork `Manevolent/ts3j` into the project's GitHub organization (or a maintainer-controlled
account) at the pinned commit, tag it `ts3mobile-1` (or an equivalent semver tag), and publish
through JitPack under the fork coordinates, e.g. `com.github.<org>:ts3j:ts3mobile-1`. Update
`gradle/libs.versions.toml` to the fork coordinates and keep the source commit recorded in a
`THIRD_PARTY_NOTICES.md` entry. This makes the artifact reproducible from a repository we
control while preserving the single-line dependency declaration.

If a JitPack-backed fork is not acceptable, the fallback is a checked-in Maven repository
(`libs/ts3j`) populated with the built jar/pom and consumed via
`maven(url = uri("libs/ts3j"))`. This is more invasive but removes the network dependency
entirely. Choose the JitPack fork unless there is a concrete reason it will not work.

### Step 2 — Enable Gradle dependency locking on every module (implemented)

Implemented in all three module build scripts ([`ts3-protocol`](../../ts3-protocol/build.gradle.kts),
[`app`](../../app/build.gradle.kts), [`audio-opus`](../../audio-opus/build.gradle.kts)):

```kotlin
dependencyLocking {
    lockMode.set(LockMode.STRICT)
}

configurations.all {
    resolutionStrategy.activateDependencyLocking()
}
```

The `dependencyLocking` block alone only sets policy. What actually attaches a lock
state to a configuration is `resolutionStrategy.activateDependencyLocking()` — without
it, no lockfile is ever written and STRICT mode is never enforced. **This is the root
cause of the first failed attempt on this toolchain** (Gradle 8.9 / AGP 8.7 / JDK 17):
the initial try configured only the `dependencyLocking` block, observed that
`--write-locks` produced nothing but an empty `settings-gradle.lockfile`, and concluded
that per-project locking did not work on Gradle 8.9. That conclusion was wrong; the
activation call was missing. With the activation in place, `./gradlew
:ts3-protocol:dependencies --write-locks` writes `ts3-protocol/gradle.lockfile`
containing every resolved module and configuration.

Two operational details learned while validating:

- The `dependencies` report task renders resolution failures as `FAILED` nodes but does
  **not** fail the build. Verifying enforcement therefore requires a task that consumes
  the artifacts: raising the bcprov constraint to 1.70 fails
  `:ts3-protocol:compileKotlin` with `Cannot find a version of
  'org.bouncycastle:bcprov-jdk15on' that satisfies the version constraints`, which is
  the expected STRICT behavior.
- Lock state is written during the same run that resolves it, so a first `--write-locks`
  invocation may show transient `FAILED` nodes for configurations whose lock state has
  not been persisted yet (notably `project :ts3-protocol` inside `:app`'s classpaths).
  Running the build again without flags resolves cleanly.

Lockfiles are committed per module (`app/gradle.lockfile`,
`audio-opus/gradle.lockfile`, `ts3-protocol/gradle.lockfile`) plus a one-line
`settings-gradle.lockfile` (an empty lock state for the version catalog's incoming
dependencies, generated by the same mechanism). Updating a dependency now requires
`--write-locks` so the lockfile diff lands in the same commit as the version bump.

### Step 3 — Constrain the transitive versions explicitly (implemented)

Implemented in [`ts3-protocol/build.gradle.kts`](../../ts3-protocol/build.gradle.kts).
With Step 2 now active, the lockfile is the machine-enforced pin of the full resolution
graph; the `constraints` block below remains as a readable, review-visible statement of
intent for the four artifacts ts3j pulls in, capping each at the currently-resolved
version:

```kotlin
dependencies {
    implementation(libs.ts3j)
    implementation(libs.kotlinx.coroutines.core)

    // Pin the exact resolved versions of ts3j's transitive dependencies so a
    // consumer, BOM, or upstream ts3j bump cannot silently move them. See
    // docs/decisions/0004-take-ownership-of-ts3j-dependency.md.
    constraints {
        implementation("org.bouncycastle:bcprov-jdk15on:1.67")
        implementation("commons-lang:commons-lang:2.6")
        implementation("dnsjava:dnsjava:2.1.8")
        implementation("org.ini4j:ini4j:0.5.1")
    }

    testImplementation(libs.junit)
}
```

A constraint caps the version a transitive is allowed to resolve to; it does not pull in
an additional dependency. Together with the lockfile this gives two layers: the
constraints fail loudly on version drift in the four ts3j transitives even in a
lockless consumer context, and the lockfile pins the entire graph of every module.

### Step 4 — Extend the ProGuard keep rules to the transitive set (implemented)

Implemented in [`app/proguard-rules.pro`](../../app/proguard-rules.pro). The set of keep
rules was derived by scanning ts3j's bytecode (extracting `com.github.manevolent.ts3j.**`
`.class` files from the cached JitPack jar and listing their referenced types with `javap`)
so the rules target the classes ts3j actually references by name, not a broad prefix:

- `com.github.manevolent.ts3j.**` — the protocol library itself (kept wholesale; ts3j
  reflects on its own command/event/identity classes and is not annotation-driven).
- `Punisher.**` — ts3j's vendored NaCl/Ed25519 implementation, reached only through the
  identity classes; kept explicitly so R8 cannot prune the crypto primitives.
- BouncyCastle packages ts3j uses directly: `org.bouncycastle.math.ec.**`,
  `crypto.params.**`, `crypto.generators.**`, `crypto.digests.**`, `crypto.engines.**`,
  `crypto.modes.**`, `crypto.signers.**`, `jce.**`, `jce.provider.**`, `jce.spec.**`,
  `asn1.**`, plus `-dontwarn org.bouncycastle.**`.
- `dnsjava` resolver entry points: `org.xbill.DNS.{Lookup,Name,Record,SRVRecord}`, plus
  `-dontwarn org.xbill.DNS.**`.
- `ini4j`: `org.ini4j.Ini`, plus `-dontwarn org.ini4j.**`.

Validated by `./gradlew :app:assembleRelease` (JDK 17, R8 active): `BUILD SUCCESSFUL`,
no new R8 warnings beyond the pre-existing default-config constructor notices that ship
with `proguard-android-optimize.txt`. The minified release APK assembles and the keep
rules emit no warnings about the ts3j transitive set.

## Consequences

- Steps 2–4 are in place: every module's resolution graph is locked by a committed
  `gradle.lockfile` under `LockMode.STRICT` (verified by a negative test), the four ts3j
  transitives are additionally capped by explicit constraints, and the ProGuard keep
  rules cover the transitive set with a green release build. A supply-chain change in
  any dependency surfaces as a lockfile diff in code review instead of a silent
  resolution to a higher version, and the minified release build keeps the classes ts3j
  actually references by name.
- Updating any dependency now requires running the build with `--write-locks` (or
  `--update-locks group:artifact`) and committing the lockfile diff together with the
  version change; a version bump without the lockfile update fails the build under
  STRICT mode. This is intentional friction.
- Step 1 (forking ts3j) remains open: a clean clone still resolves ts3j from JitPack
  against the upstream `Manevolent/ts3j` repository, so build reproducibility still
  depends on a third-party GitHub repository and JitPack. Once the fork is published,
  update `gradle/libs.versions.toml`, re-run `--write-locks`, and record the source
  commit in `THIRD_PARTY_NOTICES.md`.
- The fork will become a maintenance surface: protocol bugs that matter to TS3 Mobile
  are fixed on the fork, and merging upstream changes becomes a deliberate, reviewable
  action rather than a version bump. Document the fork's policy ("track upstream,
  rebase onto our tag, do not diverge in behavior unless a security fix requires it")
  in `THIRD_PARTY_NOTICES.md` next to the commit provenance.
- `README.md`'s dependency-locking statement is satisfied; only the fork remains open
  before a stable product release.

## Alternatives considered

- **Keep the upstream JitPack pin as-is.** Rejected: it leaves build reproducibility dependent
  on a third-party GitHub repository and JitPack, and the README already commits to doing this
  before release.
- **Pin by checksum only (Gradle dependency verification).** `gradle/verification-metadata.xml`
  would catch a tampered artifact but would not stop a missing artifact from breaking the build,
  and it does nothing for transitive version drift. Locking plus a fork is stronger.
- **Vendor the ts3j source into the repository as a submodule.** Rejected for now: it is the
  most reproducible option, but it adds build-time complexity (an extra Gradle include, a
  Java-source module with its own dependencies) that is not justified while ts3j changes
  infrequently. Re-evaluate if the fork needs frequent local patches.

## Re-evaluate when

- ts3j upstream releases a protocol fix we need and the fork policy would force a delay, or
- a different Java TeamSpeak library (or a maintained fork of ts3j with a real release process)
  becomes available, or
- the project moves to a multi-server architecture that changes how many concurrent ts3j
  sockets are alive and exposes new threading assumptions in ts3j.
