pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "ymcl"

include(":composeApp")

// Vendored HMCLCore (scheme B). The composite-build attempt against the full
// HMCL checkout failed during configuration because HMCL's Gradle 9.8 buildSrc
// is binary-incompatible with this project's Gradle 8.14; see
// docs/hmcl-api-notes.md. The source copy is unmodified.
include(":hmcl-core", ":HMCLTransformerDiscoveryService", ":HMCLMultiMCBootstrap")
project(":hmcl-core").projectDir = file("vendor/hmcl/HMCLCore")
project(":HMCLTransformerDiscoveryService").projectDir = file("vendor/hmcl/HMCLTransformerDiscoveryService")
project(":HMCLMultiMCBootstrap").projectDir = file("vendor/hmcl/HMCLMultiMCBootstrap")
