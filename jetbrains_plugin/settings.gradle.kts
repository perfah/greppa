plugins {
    // Lets Gradle download a JDK matching the toolchain defined in
    // build.gradle.kts when no local installation does, so the build
    // works on a machine (or CI runner) with any Java installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "greppa"
