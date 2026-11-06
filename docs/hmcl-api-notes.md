# HMCLCore API recon (P2)

Source checkout: `HMCL/` (local, gitignored clone)
Commit: `b46a07cd45be326878498b22daa01a79ea3ea1bd`
License: GPL-3.0-or-later WITH AdditionRef-HMCL (see `LICENSE` and `vendor/README.md`)

## Build integration decision

Scheme A (composite build) was tried first with:

```kotlin
includeBuild("HMCL") {
    dependencySubstitution {
        substitute(module("org.jackhuang:HMCLCore")).using(project(":HMCLCore"))
    }
}
```

It failed at configuration time before compiling anything:

```text
Execution failed for task ':composeApp:dependencies'.
> Could not resolve all dependencies for configuration ':composeApp:desktopCompileClasspath'.
   > Build completed with 1 failures.
      > 'void org.gradle.api.component.AdhocComponentWithVariants.addVariantsFromConfiguration(org.gradle.api.provider.Provider, org.gradle.api.Action)'
```

HMCL uses Gradle 9.8.0 (`HMCL/gradle/wrapper/gradle-wrapper.properties`); its `buildSrc`
classes are not binary-compatible with YMCL's Gradle 8.14. Upgrading YMCL's wrapper would
change the P1 build contract, so we used scheme B: vendor `HMCLCore` plus its two bundled
helper libraries under `vendor/hmcl/`, with a YMCL-owned Gradle build file and an
unmodified source copy.

## APIs actually used

### Java runtime

- `org.jackhuang.hmcl.java.JavaInfo`
  - `JavaInfo(Platform platform, String version, String vendor)`
  - `JavaInfo.parseVersion(String)`
  - `JavaInfo.CURRENT_ENVIRONMENT`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/java/JavaInfo.java`
- `org.jackhuang.hmcl.java.JavaRuntime`
  - `static JavaRuntime of(Path binary, JavaInfo info, boolean isManaged)`
  - `Path getBinary()`, `String getVersion()`, `int getParsedVersion()`,
    `String getVendor()`, `Platform getPlatform()`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/java/JavaRuntime.java`
- There is no `JavaManager.java` in this HMCL revision. The equivalent boundaries are
  `JavaRepository` (managed installs) and the per-runtime `JavaInfo`/`JavaRuntime` pair.
  P2 only probes system runtimes, so we build `JavaInfo` from
  `<java> -XshowSettings:properties -version` and pass the resulting `JavaRuntime` into
  `LaunchOptions`. HMCL's `JavaRepository` is install/download-oriented and is not needed.

### Instance data and layout

- `org.jackhuang.hmcl.game.GameInstance`
  - Interface implemented by `HmclGameInstance` in the YMCL engine adapter.
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/game/GameInstance.java`
- `org.jackhuang.hmcl.game.GameInstanceManifest`
  - `static GameInstanceManifest fromJson(JsonObject json, boolean copyJson)`
  - Record accessors: `id()`, `mainClass()`, `inheritsFrom()`, `jar()`,
    `assetIndex()`, `libraries()`, `arguments()`, `downloads()`, `javaVersion()`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/game/GameInstanceManifest.java`
- `org.jackhuang.hmcl.game.DefaultGameRepositoryLayout`
  - `DefaultGameRepositoryLayout(Path baseDirectory)`
  - `getInstanceRoot`, `getInstanceJson`, `getInstanceJarFile`, `getLibrariesDirectory`,
    `getLibraryFile`, `getAssetDirectory`, `getAssetIndexFile`, `getAssetObject`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/game/DefaultGameRepositoryLayout.java`
- `org.jackhuang.hmcl.game.GameInstanceID`
  - `GameInstanceID(String id)`, `static boolean isValid(String)`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/game/GameInstanceID.java`
- `org.jackhuang.hmcl.game.GameComponentAnalyzer`
  - `static GameComponentAnalyzer analyze(GameInstanceManifest, GameVersionNumber)`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/game/GameComponentAnalyzer.java`
- `org.jackhuang.hmcl.util.versioning.GameVersionNumber`
  - `asGameVersion(String)`, `detectFromJar(Path)`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/util/versioning/GameVersionNumber.java`

`DefaultGameRepository` is deliberately not used: it imports `javafx.application.Platform`
and `javafx.beans.property`, so instantiating it would pull JavaFX graphics into a Compose
desktop app. We implement the `GameInstance` boundary directly; the launch path below does
not need `DefaultGameRepository`. `GameInstance.getRepository()` is therefore a fail-fast
stub (HMCL's `DefaultLauncher` does not call it).

### Launch

- `org.jackhuang.hmcl.auth.AuthInfo`
  - `AuthInfo(String username, UUID uuid, String accessToken, String userType, String userProperties)`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/auth/AuthInfo.java`
- `org.jackhuang.hmcl.game.LaunchOptions` + `LaunchOptions.Builder`
  - Builder methods used: `setInstanceId`, `setGameDir`, `setJava`, `setMaxMemory`,
    `setWidth`, `setHeight`, `setDaemon`, `setNoGeneratedJVMArgs`, `setVersionName`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/game/LaunchOptions.java`
- `org.jackhuang.hmcl.launch.DefaultLauncher`
  - `DefaultLauncher(GameInstance, GameInstanceManifest, AuthInfo, LaunchOptions, ProcessListener, boolean)`
  - `ManagedProcess launch() throws IOException, InterruptedException`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/DefaultLauncher.java`
- `org.jackhuang.hmcl.launch.ProcessListener`
  - `onLog(String log, boolean isErrorStream)`
  - `onExit(int exitCode, ExitType exitType)`
  - File: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/ProcessListener.java`

`DefaultLauncher` builds the classpath, substitutes `${auth_*}`, `${game_directory}`,
`${assets_root}`, `${natives_directory}`, etc., extracts natives, writes the per-instance
`log4j2.xml`, and starts the process. We pass a `ProcessListener` so stdout/stderr are
captured instead of inherited; the same listener feeds the log file and the Compose flow.

## Dependencies that must be supplied

From `HMCLCore/build.gradle.kts`: kala-compress (zip/tar), kala-encoding-detector,
simple-png-javafx, gson, tomlj, xz, lz4, fx-gson, constant-pool-scanner, nanohttpd,
jsoup, jna, pci-ids, HelloNBT, weburl, uuid-tools, commonmark (+ extensions),
jetbrains-annotations (compile-only API). JavaFX base/graphics/controls are compile-only
for HMCLCore; YMCL's launch path does not initialize JavaFX, so they are not packaged.
