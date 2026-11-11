plugins {
    id("java")
    // JetBrains' official build tool for IntelliJ platform plugins. It
    // downloads the target IDE, compiles against it, builds the
    // distributable zip and provides the runIde/verifyPlugin tasks -
    // the standard replacement for the hand-rolled Maven build with
    // its generated classpath jar.
    //
    // "2.+" resolves the newest 2.x from the Gradle Plugin Portal at
    // build time. The previously pinned 2.2.1 predates the Gradle
    // version the wrapper uses and fails under it with "No IntelliJ
    // Platform dependency found" - the plugin's own validation, fired
    // because its IDE dependency never reaches the compile classpath.
    // Once the build is green, pin the resolved concrete version (the
    // "gradlew buildEnvironment" output names it) so builds stay
    // reproducible. Keep this version in sync with the idea
    // subproject's build.gradle.kts.
    id("org.jetbrains.intellij.platform") version "2.+"
}

group = "com.perfah"
// Keep in sync with the <version> in plugin.xml.
version = "1.0.1"

java {
    toolchain {
        // 25 because the targeted 2026.x IDE distributions ship their
        // platform jars compiled with class file version 69 (Java 25),
        // and javac cannot read class files newer than the JDK it runs
        // on: compiling against RustRover 2026.2.3 with a 17 toolchain
        // fails with "class file has wrong version 69.0, should be
        // 61.0". The foojay resolver in settings.gradle.kts downloads
        // a matching JDK when no local installation does.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The plugin code is identical for both build targets; only the IDE it
// is compiled against differs. This subproject therefore compiles the
// shared sources in ../src instead of keeping a copy: without this,
// Gradle would look for sources under rustrover/src, which does not
// exist.
sourceSets {
    main {
        java.srcDirs("../src/main/java")
        resources.srcDirs("../src/main/resources")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        // JetBrains' repositories holding the IDE distributions and
        // bundled plugin artifacts.
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // The IDE this project targets: RustRover. Downloaded from
        // JetBrains on the first build and cached in the Gradle cache
        // afterwards, which replaces the local RustRover installation
        // the old classpath-jar build compiled against: the build is
        // self-contained and runs anywhere, including a CI runner.
        // The IntelliJ IDEA target lives in the sibling idea/
        // subproject.
        rustRover("2026.2.3")
        // The bundled Markdown plugin's classes (org.intellij.markdown),
        // which MarkdownParser renders the analysis with; mirrors the
        // <depends>org.intellij.plugins.markdown</depends> in plugin.xml.
        // The platform itself is added automatically.
        bundledPlugin("org.intellij.plugins.markdown")
    }

    // Packaged into the plugin distribution's lib/ directory, which the
    // plugin's classloader sees. The IDE ships lsp4j but does not export
    // it to plugin classloaders, so the plugin must carry its own copy -
    // the old build shaded it into the jar for the same reason.
    // org.eclipse.lsp4j.jsonrpc comes in transitively. gson is excluded:
    // lsp4j merely declares it, and the IDE provides gson at runtime, so
    // packaging it would duplicate the IDE's copy.
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.0") {
        exclude(group = "com.google.code.gson", module = "gson")
    }

    // Provided by the IDE at runtime; compile classpath only.
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.jetbrains:annotations:24.0.0")
}

// The Rust server binaries bundled into the plugin distribution: the
// prepareSandbox task copies every file from the staging directory into
// the plugin's bin/ directory, so a fresh install runs without a
// separate download and with no setup at all - the descriptor always
// launches the binary for the current OS (see
// GreppaLspServerDescriptor); there is no server path setting that
// could override it.
//
// The staging directory holds one binary per platform, named by OS and
// arch (lsp_server-windows-x64.exe, lsp_server-macos-arm64,
// lsp_server-linux-x64) - the names GreppaLspServerDescriptor looks up
// at runtime. CI builds all three and stages them here (see
// .github/workflows/build.yml); locally, copy your cargo build output
// there once, e.g.:
//   cp lsp_server/target/release/lsp_server.exe lsp_server/bin/lsp_server-windows-x64.exe
// A distribution with fewer binaries still builds and runs on the
// platforms it does contain, so local dev builds do not need all
// three.
val serverBinariesDir = rootProject.file("../lsp_server/bin")

tasks {
    prepareSandbox {
        doFirst {
            val staged = serverBinariesDir.listFiles()?.filter { it.isFile }.orEmpty()
            if (staged.isEmpty()) {
                throw GradleException(
                    "No Greppa server binaries staged in $serverBinariesDir. " +
                        "CI stages the three platform binaries there; locally, build the server " +
                        "(cd lsp_server && cargo build --release) and copy target/release/lsp_server(.exe) " +
                        "into lsp_server/bin under its platform name (see the comment in build.gradle.kts)."
                )
            }
        }
        from(serverBinariesDir) {
            // prepareSandbox's copy root is the sandbox root - the PARENT
            // of the plugin directory; the task itself places the plugin's
            // jars at <pluginName>/lib. into("bin") therefore landed the
            // binaries NEXT TO the plugin folder, outside the directory
            // buildPlugin packages into the zip and outside the bin/ the
            // descriptor searches at runtime - so the zips carried no
            // bundled server. Mirroring the lib/ placement with the
            // task's pluginName puts them beside lib/, inside the plugin
            // directory, whatever it is named ("rustrover" here, "idea"
            // in the idea subproject).
            into(pluginName.get() + "/bin")
        }
    }
}
