plugins {
    // Lets Gradle download a JDK matching the toolchain defined in
    // build.gradle.kts when no local installation does, so the build
    // works on a machine (or CI runner) with any Java installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "greppa"

// The IntelliJ IDEA build target. The IntelliJ Platform Gradle Plugin
// allows exactly one platform dependency per Gradle project, so every
// IDE the plugin is built against is its own project compiling the
// same sources: the root project targets RustRover, this subproject
// targets IntelliJ IDEA (see idea/build.gradle.kts).
include("idea")
