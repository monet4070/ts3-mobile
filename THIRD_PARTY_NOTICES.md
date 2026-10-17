# Third-Party Notices

TS3 Mobile incorporates or depends on the following third-party software. Each
component remains subject to its own license.

## ts3j

- Project: [Manevolent/ts3j](https://github.com/Manevolent/ts3j)
- Pinned revision: `db57d60c989e399626aa16d921390f5033e6cdeb`
- License: Apache License 2.0

The Apache License 2.0 text is included in this repository as [LICENSE](LICENSE).

## libopus

- Project: [Xiph.Org Opus](https://opus-codec.org/)
- Package version: 1.3.1
- License: BSD 3-Clause
- License text: [app/src/main/assets/licenses/libopus.txt](app/src/main/assets/licenses/libopus.txt)

## RNNoise

- Project: [Xiph.Org RNNoise](https://gitlab.xiph.org/xiph/rnnoise)
- Model/source release: v0.2, vendored from revision `904a876d`
- License: BSD 3-Clause
- License text: [app/src/main/assets/licenses/rnnoise.txt](app/src/main/assets/licenses/rnnoise.txt)
- Vendored provenance: [audio-opus/src/main/cpp/third_party/rnnoise/VERSION.md](audio-opus/src/main/cpp/third_party/rnnoise/VERSION.md)

## Lucide icons

- Project: [Lucide](https://lucide.dev/), [source](https://github.com/lucide-icons/lucide)
- Source package: `lucide-static` 0.468.0 from the npm registry
- License: ISC (includes the upstream Feather copyright attribution)
- License text: [app/src/main/assets/licenses/lucide.txt](app/src/main/assets/licenses/lucide.txt)
- Selected 24-unit SVGs are converted to Android vector drawables under
  `app/src/main/res/drawable/ic_lucide_*`, using 1.75-unit rounded strokes.
- Visual theme reference: [TailSync](https://github.com/monet4070/TailSync),
  warm Canvas palette; no TailSync artwork is bundled.

## Android and Kotlin libraries

The project also depends on AndroidX, Jetpack Compose, Kotlin,
and kotlinx.coroutines components distributed under the Apache License 2.0.
Exact versions are declared in `gradle/libs.versions.toml` and resolved by
Gradle. Their copyright notices and source distributions are available from
their respective upstream projects.

No TeamSpeak artwork, logos, or proprietary client source code is included in
this repository.
