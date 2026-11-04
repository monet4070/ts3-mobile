# ADR-0019: First stable release documentation

Date: 2026-11-02. Status: documentation preparation accepted; release not published.

## Context and scope

The maintainer requests the current feature set as the first stable GitHub
release and asks for the complete documentation and accompanying instructions.
Use `v1.0.0` as the proposed stable release tag. The published latest release is
still `v0.11.1-m10`; app metadata remains `0.11.1-m10` / versionCode 12.
Writing release copy does not change the binary version or create a release.

README files currently mix user instructions, historical milestones, development
plans and detailed test records. Some claims are stale: bookmarks/volume gate are
implemented, channel details were removed, and background poke notifications exist.
The source contains substantial uncommitted feature/UI changes that must be included
in the reviewed release commit rather than tagging the current HEAD prematurely.

## Documentation boundaries and decomposition

Rewrite the README files (currently around/above 300 lines) as concise user entry
points. Move operation details into user guides and troubleshooting, installation
and signature handling into an upgrade guide, compatibility/validation boundaries
into one support matrix, and release mechanics into `docs/release/1.0.0/`.
Extract the old Unreleased implementation notes from CHANGELOG into a historical
record, retaining earlier published release entries. Keep existing acceptance logs
as dated evidence; add navigation notices to superseded release plans.

ARCHITECTURE already exceeds 300 lines. Limit its edits to correcting release
scope and removed UI descriptions; all new release-process detail stays in the
dedicated release documents, without expanding application responsibilities.

Prepare Chinese and English Release bodies, a release checklist and a reproducible
artifact/signing/publication guide. Update privacy, security, support and issue
templates against the actual code and public repository settings. Link the existing
license notices rather than changing license terms or dependency versions.

## Evidence and compatibility

The latest bookmark record supplies 239 app JVM, 114 offline emulator UI and 22
physical-device UI checks, with 3 final count-label rechecks. These are development
evidence, not certification of a final signed `1.0.0` APK. Carry forward the open
live interoperability, acoustic/long-duration, physical routing, second-device,
signing/upgrade and dependency-ownership work. Do not close ADR-0004 or the device
matrix by editing prose; changes to those requirements need their own decision.

This task changes documentation and GitHub issue guidance only. It does not alter
production code, storage, signing configuration, app version or workflow behavior.
No commit, tag, upload or public announcement is made as part of writing the docs.

On 2026-11-03 the maintainer requested a detailed README for new users. The
bilingual entry points now include the complete common workflows and an interface
tour, while installation/signing edge cases and maintainer procedures remain in
their dedicated documents. See the [README record](../testing/README_ONBOARDING_2026-11-03.md).

## Verification

Check local Markdown links/anchors, factual consistency with resources and manifest,
issue-template YAML, shell/Python examples, privacy scan and whitespace. Reuse dated
application test records explicitly; documentation-only edits do not require running
the unchanged full application suite again.
