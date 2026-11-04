# Third-Party Notices

TS3 Mobile incorporates or depends on the following third-party software. Each
component remains subject to its own license.

## ts3j

- Project: [Manevolent/ts3j](https://github.com/Manevolent/ts3j)
- Maintainer fork: [monet4070/ts3j](https://github.com/monet4070/ts3j)
- Release artifact: `com.github.monet4070:ts3j:ts3mobile-1` (JitPack)
- Pinned revision: `db57d60c989e399626aa16d921390f5033e6cdeb`
- License: Apache License 2.0

The `ts3mobile-1` tag points to that unchanged upstream commit. Keep this tag
immutable, track upstream deliberately, and make any behavior or security patch
a new reviewed revision/tag with updated locks and release validation. The
maintainer fork does not change the protocol implementation for 1.0.0.

The Apache License 2.0 text is included in this repository as [LICENSE](LICENSE).

## libopus

- Project: [Xiph.Org Opus](https://opus-codec.org/)
- Version: 1.6.1, built from the official source archive with pinned SHA-256
- Source and comparison hashes: [Opus source lock](tools/opus-compat.lock.json)
- Build configuration: [source](audio-opus/src/main/cpp/opus-source.cmake) and
  [options](audio-opus/src/main/cpp/opus-options.cmake); DRED, deep PLC and OSCE disabled
- License: BSD 3-Clause
- License text: [app/src/main/assets/licenses/libopus.txt](app/src/main/assets/licenses/libopus.txt)

## RNNoise

- Project: [Xiph.Org RNNoise](https://gitlab.xiph.org/xiph/rnnoise)
- Model/source release: v0.2, vendored from revision `904a876d`
- License: BSD 3-Clause
- License text: [app/src/main/assets/licenses/rnnoise.txt](app/src/main/assets/licenses/rnnoise.txt)
- Vendored provenance: [audio-opus/src/main/cpp/third_party/rnnoise/VERSION.md](audio-opus/src/main/cpp/third_party/rnnoise/VERSION.md)

## Optional APM development experiment

- Project: [freedesktop webrtc-audio-processing](https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing/), standalone release 1.3; WebRTC BSD 3-Clause with its PATENTS grant.
- Dependency: Abseil 20230125.1, Apache License 2.0; its Meson wrap patch is fixed separately.
- Exact source URLs and SHA-256 values: [experiment lock](tools/apm-experiment.lock.json).
- The [explicit development build](tools/build-apm-experiment.py) preserves WebRTC,
  PATENTS, Abseil, PFFFT, Ooura FFT, SPL sqrt, FFT and RNNoise VAD notices next to
  its outputs in `build/apm-experiment/notices/`. Distribute these with experimental binaries.
- This APM snapshot and its bundled VAD are separate from the existing RNNoise
  suppressor. AGC1/AGC2 are disabled in the prototype. Default builds contain no APM binary.
- Build tooling: Meson 1.5.2 (Apache License 2.0); the pinned wheel retains its license metadata.

## Lucide icons

- Project: [Lucide](https://lucide.dev/), [source](https://github.com/lucide-icons/lucide)
- Source package: `lucide-static` 0.468.0 from the npm registry
- License: ISC (includes the upstream Feather copyright attribution)
- License text: [app/src/main/assets/licenses/lucide.txt](app/src/main/assets/licenses/lucide.txt)
- Selected 24-unit SVGs are converted to Android vector drawables under
  `app/src/main/res/drawable/ic_lucide_*`, using 1.75-unit rounded strokes.
- Visual theme reference: [TailSync](https://github.com/monet4070/TailSync),
  warm Canvas palette; no TailSync artwork is bundled.

## First-party artwork and documentation

The project's original poke, away and add icons are first-party artwork under
the repository's Apache License 2.0, distinct from the Lucide assets above.
Documentation screenshots use synthetic UI fixtures; no private server or
participant content is included. See [image provenance](docs/images/README.md).

## Android and Kotlin libraries

The project also depends on AndroidX, Jetpack Compose, Kotlin,
and kotlinx.coroutines components distributed under the Apache License 2.0.
Exact versions are declared in `gradle/libs.versions.toml` and resolved by
Gradle. Their copyright notices and source distributions are available from
their respective upstream projects.

No TeamSpeak artwork, logos, or proprietary client source code is included in
this repository.
