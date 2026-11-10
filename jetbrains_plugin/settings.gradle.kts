plugins {
    // Lets Gradle download a JDK matching the toolchain defined in the
    // subprojects' build.gradle.kts when no local installation does,
    // so the build works on a machine (or CI runner) with any Java
    // installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "greppa"

// The build targets. The IntelliJ Platform Gradle Plugin allows
// exactly one platform dependency per Gradle project, so every IDE
// the plugin is built against is its own subproject compiling the
// same shared sources from ../src: rustrover targets RustRover, idea
// targets IntelliJ IDEA (see rustrover/build.gradle.kts and
// idea/build.gradle.kts).
include("rustrover")
include("idea")
