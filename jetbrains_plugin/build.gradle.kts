plugins {
    id("java")
    // JetBrains' official build tool for IntelliJ platform plugins. It
    // downloads the target IDE, compiles against it, builds the
    // distributable zip and provides the runIde/verifyPlugin tasks -
    // the standard replacement for the hand-rolled Maven build with
    // its generated classpath jar.
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.example"
// Keep in sync with the <version> in plugin.xml.
version = "1.0.6"

java {
    toolchain {
        // 17 matches the oldest IDE the plugin supports (since-build
        // 232); the IDE itself may run a newer JBR.
        languageVersion = JavaLanguageVersion.of(17)
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
        // The IDE the plugin targets. Downloaded from JetBrains on the
        // first build and cached in the Gradle cache afterwards, which
        // replaces the local RustRover installation the old
        // classpath-jar build compiled against: the build is
        // self-contained and runs anywhere, including a CI runner.
        rustRover("2024.2.3")
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
