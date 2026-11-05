plugins {
    `java-library`
}

// Vendored from HMCL commit b46a07cd45be326878498b22daa01a79ea3ea1bd.
// Keep the upstream source untouched; this build file is ours so the vendor
// copy can be consumed by YMCL's Gradle 8.14 build without HMCL's buildSrc.
tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.encoding = "UTF-8"
    options.compilerArgs.add("--add-exports=jdk.attach/sun.tools.attach=ALL-UNNAMED")
}

dependencies {
    api(libs.kala.compress.zip)
    api(libs.kala.compress.tar)
    api(libs.kala.encoding.detctor)
    api(libs.simple.png.javafx)
    api(libs.gson)
    api(libs.tomlj)
    api(libs.xz)
    api(libs.lz4)
    api(libs.fx.gson)
    api(libs.constant.pool.scanner)
    api(libs.nanohttpd)
    api(libs.jsoup)
    api(libs.jna)
    api(libs.pci.ids)
    api(libs.hello.nbt)
    api(libs.weburl)
    api(libs.uuid.tools)
    api(libs.commonmark)
    api(libs.commonmark.autolink)
    api(libs.commonmark.underline)
    api(libs.commonmark.strikethrough)
    api(libs.commonmark.table)

    compileOnlyApi(libs.jetbrains.annotations)

    // HMCLCore compiles against JavaFX for skin/property helpers. YMCL only
    // calls the non-JavaFX launch path, so these are compile-only and never
    // shipped in the desktop app image. Linux is the P2 acceptance platform.
    compileOnly("org.openjfx:javafx-base:21.0.8:linux")
    compileOnly("org.openjfx:javafx-graphics:21.0.8:linux")
    compileOnly("org.openjfx:javafx-controls:21.0.8:linux")

    testImplementation(libs.jna.platform)
    testImplementation(libs.jimfs)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    dependsOn(":HMCLTransformerDiscoveryService:jar", ":HMCLMultiMCBootstrap:jar")
    into("assets/game") {
        from(project(":HMCLTransformerDiscoveryService").tasks.named("jar"))
        from(project(":HMCLMultiMCBootstrap").tasks.named("jar"))
    }
}

tasks.test {
    // The vendored tree keeps upstream tests for reference, but YMCL does not
    // run HMCL's JavaFX-heavy test suite. YMCL's own tests live in composeApp.
    enabled = false
}

tasks.compileTestJava {
    enabled = false
}
