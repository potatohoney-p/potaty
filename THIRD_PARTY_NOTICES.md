# Third-party notices

Potaty source code is Apache-2.0 licensed. Third-party components remain under their own licences.

## Source foundation

### MonoSketch

Potaty's manual ASCII editor and supporting drawing modules are derived from MonoSketch. Potaty
retains the original copyright notices in derived files and marks subsequent Potaty changes
separately.

- Copyright (c) 2023-2024, tuanchauict
- Project: <https://github.com/tuanchauict/MonoSketch>
- Licence: Apache License 2.0
- Licence text: [LICENSE](LICENSE)

## Bundled assets

### JetBrains Mono

The web application bundles JetBrains Mono WOFF2 files under `src/main/resources/fonts/`.

- Copyright 2020 The JetBrains Mono Project Authors
- Project: <https://github.com/JetBrains/JetBrainsMono>
- Licence: SIL Open Font License 1.1
- Licence text: [src/main/resources/fonts/OFL.txt](src/main/resources/fonts/OFL.txt)

### Pretendard

The web build bundles Pretendard Variable's unicode-range WOFF2 subsets from the locked Pretendard 1.3.9 npm package as the open Latin and Hangul UI fallback. Browsers fetch only the subsets needed by the current page instead of preloading the roughly 2 MiB monolithic font.

- Copyright (c) 2021, Kil Hyung-jin (<https://github.com/orioncactus/pretendard>)
- Reserved Font Name: Pretendard
- Project: <https://github.com/orioncactus/pretendard>
- Licence: SIL Open Font License 1.1
- Licence text: [src/main/resources/fonts/PRETENDARD-OFL.txt](src/main/resources/fonts/PRETENDARD-OFL.txt)

### D2Coding

The generated-result canvas uses D2Coding for Korean fixed-width terminal alignment. The locked
`@kfonts/d2coding` package supplies the WOFF2 file during the reproducible web build; the font
binary is not copied into the source tree.

- Copyright Naver Corporation and the D2Coding project contributors
- Project: <https://github.com/naver/d2codingfont>
- Build package: `@kfonts/d2coding` 0.2.0
- Font version: D2Coding 1.3.2
- Licence: SIL Open Font License 1.1
- Licence text: [src/main/resources/fonts/OFL.txt](src/main/resources/fonts/OFL.txt)
- Build notice: [src/main/resources/fonts/D2CODING-NOTICE.txt](src/main/resources/fonts/D2CODING-NOTICE.txt)

### Samsung Sharp Sans

Samsung Sharp Sans is proprietary and is not distributed with Potaty. A deployment may provide it only under its own valid webfont and redistribution terms. Without that licensed asset, Potaty uses the bundled Pretendard fallback. See [the deployment note](src/main/resources/fonts/samsung-sharp-sans/README.md).

## Software dependencies

JavaScript and JVM dependencies are declared in `package-lock.json`, `gradle/libs.versions.toml`, and Gradle build files. Their package metadata and upstream licence files are authoritative. Release artifacts should include an automatically generated SBOM and licence report; this hand-maintained notice is not a substitute for that release step.

Do not remove copyright, attribution, or licence files from bundled third-party components.
