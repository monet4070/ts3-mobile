# 0003: Use a pinned KtLint Gradle formatting gate

**Status:** Accepted  
**Date:** 2026-08-15

## Context

The repository had Kotlin compiler and Android Lint checks but no reproducible
formatting command. IDE-only formatting cannot be enforced in pull requests and
produces inconsistent diffs between contributors.

## Decision

Use `org.jlleitschuh.gradle.ktlint` version `12.1.2`, pinned in the Gradle
version catalog. The root `quality` task runs each module's `ktlintCheck`, and
GitHub Actions executes it before tests and Android Lint. Compose function and
local constant naming rules are disabled in `.editorconfig` because those
conventions intentionally differ from generic Kotlin naming rules.

## Alternatives considered

- IDE formatting only: no CI enforcement and environment-dependent output.
- Spotless with ktfmt: capable, but adds another formatting abstraction and a
  different style from the selected KtLint baseline.
- A custom whitespace script: too weak to validate Kotlin structure.

## Consequences

The first application creates a repository-wide formatting baseline and a large
whitespace-only diff. Future changes receive deterministic checks. Plugin and
KtLint upgrades may change formatting rules, so version updates must be isolated
and reviewed separately from behavior changes.

## Re-evaluate when

The project upgrades Kotlin/AGP and the pinned plugin no longer supports that
toolchain, or the Android Kotlin ecosystem establishes a clearly better shared
formatter.
