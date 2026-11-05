# Vendored HMCLCore

YMCL's engine adapter is built on HMCLCore, the engine half of [HMCL](https://github.com/HMCL-dev/HMCL).
This directory is a source copy, not a binary drop, so the build is reproducible and the
GPLv3 source obligation is satisfied.

- Upstream: `https://github.com/HMCL-dev/HMCL`
- Source checkout used: `/home/decafmayo/src/ymcl/HMCL`
- Commit: `b46a07cd45be326878498b22daa01a79ea3ea1bd`
  (`feat(modpack-export): 为整合包导出添加进度显示 (#7206)`)
- Copied paths:
  - `HMCLCore/` (upstream `HMCLCore/`)
  - `HMCLTransformerDiscoveryService/` (upstream `minecraft/libraries/HMCLTransformerDiscoveryService/`)
  - `HMCLMultiMCBootstrap/` (upstream `minecraft/libraries/HMCLMultiMCBootstrap/`)
- License: GPL-3.0-or-later WITH AdditionRef-HMCL. See the repository root `LICENSE`
  and the SPDX headers in every copied source file. HMCL's §7 additional terms require
  modified distributions to use a different name and keep the copyright notices; YMCL is
  an independent product name and keeps the HMCL notice in Settings → About.

## What was changed

Only Gradle build files were adapted (the upstream build uses HMCL's Gradle 9.8 `buildSrc`,
which YMCL's Gradle 8.14 cannot load). The Java/Kotlin source and resources are unmodified.

- `HMCLCore/build.gradle.kts` is YMCL-owned: it declares the same dependency set, compiles
  with Java 17, embeds the two helper jars under `assets/game/`, and adds JavaFX as
  compile-only (YMCL's launch path never initializes JavaFX).
- The two helper `build.gradle.kts` files only add the `java` plugin so they work outside
  HMCL's root `subprojects` configuration.

## Syncing

Do not edit copied source in place. To update:

1. Check out the desired HMCL commit in `HMCL/`.
2. Re-copy the three directories.
3. Restore the three YMCL-owned `build.gradle.kts` files (or re-apply the changes above).
4. Update this file's commit hash and re-run `./gradlew build`.
