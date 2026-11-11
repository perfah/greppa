plugins {
    id("java")
    // The same build tool as the rustrover subproject, resolved the
    // same way; see rustrover/build.gradle.kts for the version
    // rationale. Keep the two version strings in sync.
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
        // on: compiling against IntelliJ IDEA 2026.2.4 with a 17
        // toolchain fails with "class file has wrong version 69.0,
        // should be 61.0". The foojay resolver in settings.gradle.kts
        // downloads a matching JDK when no local installation does.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The plugin code is identical for both build targets; only the IDE it
// is compiled against differs. This subproject therefore compiles the
// shared sources in ../src instead of keeping a copy: without this,
// Gradle would look for sources under idea/src, which does not exist.
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
        // The IDE this project targets: IntelliJ IDEA. The accessors
        // are edition-specific: intellijIdeaUltimate is the edition
        // JetBrains calls plain "IntelliJ IDEA"; for IDEA Community use
        // intellijIdeaCommunity instead, which carries the LSP API the
        // plugin registers its server under as well. The rustrover
        // subproject targets RustRover; the sources and plugin.xml are
        // shared.
        intellijIdeaUltimate("2026.2.4")
        // The bundled Markdown plugin's classes (org.intellij.markdown),
        // which MarkdownParser renders the analysis with; mirrors the
        // <depends>org.intellij.plugins.markdown</depends> in plugin.xml.
        // The platform itself is added automatically.
        bundledPlugin("org.intellij.plugins.markdown")
    }

    // The same library dependencies as the rustrover subproject; see
    // the comments there for the rationale.
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.0") {
        exclude(group = "com.google.code.gson", module = "gson")
    }

    // Provided by the IDE at runtime; compile classpath only.
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.jetbrains:annotations:24.0.0")
}

// The same Rust server binary bundling as the rustrover subproject;
// see the comments there for the rationale, the staging directory and
// the platform-specific binary names.
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
            // See the rustrover subproject's build.gradle.kts: the copy
            // root is the sandbox root, not the plugin directory, so
            // the binaries must go to <pluginName>/bin to land inside
            // the plugin folder - which is named "idea" in this
            // subproject, not "rustrover".
            into(pluginName.get() + "/bin")
        }
    }
}
