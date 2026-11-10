package com.perfah.greppa;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerDescriptor;
import com.intellij.platform.lsp.api.LspServerListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

public class GreppaLspServerDescriptor extends LspServerDescriptor {

    private static final Logger LOG = Logger.getInstance(GreppaLspServerDescriptor.class);

    /**
     * The plugin's id from plugin.xml, used to locate the plugin's own
     * installation directory and with it the bundled server binary.
     */
    private static final String PLUGIN_ID = "com.perfah.greppa";

    /**
     * Directory inside the plugin installation that holds the bundled
     * server binaries, one per platform; the Gradle build puts them
     * there (see the prepareSandbox blocks in rustrover/build.gradle.kts
     * and idea/build.gradle.kts).
     */
    private static final String BUNDLED_SERVER_DIR = "bin";

    public GreppaLspServerDescriptor(@NotNull Project project) {
        super(project, "Greppa");
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile virtualFile) {
        return !virtualFile.isDirectory();
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        // The server is always the binary bundled with the plugin's own
        // distribution - there is no server path setting, so a fresh
        // install is zero-setup. A custom server build is tested by
        // staging it in lsp_server/bin and running the plugin from a
        // Gradle sandbox (see the prepareSandbox blocks in
        // rustrover/build.gradle.kts and idea/build.gradle.kts), not by
        // pointing a setting at it.
        File serverFile = bundledServerFile();
        if (serverFile == null) {
            String message = "No Greppa server available: the plugin installation contains no bundled server binary for this platform. "
                    + "Reinstall the plugin; on an Intel Mac no binary is bundled (Apple Silicon only).";
            LOG.error(message);
            throw new RuntimeException(message);
        }
        // The bundled binary must be executable, and distribution paths
        // can lose the executable bit on macOS and Linux: GitHub artifact
        // downloads reset file permissions (the CI workflow uploads the
        // extracted plugin folder so the artifact download itself is the
        // installable zip, and the wrapper GitHub creates carries no
        // permissions), and manual unzipping can lose it too. Without
        // the bit the launch fails with a permission error. Restoring it
        // here makes every distribution path work; on Windows this is a
        // no-op (there is no executable bit, and canExecute() is true
        // for .exe files).
        if (!serverFile.canExecute() && !serverFile.setExecutable(true)) {
            String message = "The bundled Greppa server binary is not executable and the permission cannot be restored: "
                    + serverFile.getAbsolutePath();
            LOG.error(message);
            throw new RuntimeException(message);
        }
        LOG.info("Using the server binary bundled with the plugin: " + serverFile.getAbsolutePath());

        GreppaSettingsState settings = GreppaSettingsState.getInstance();

        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath(serverFile.getAbsolutePath());
        commandLine.setWorkDirectory(serverFile.getParentFile());
        // Prevent BLAS/OpenMP thread-pool initialization from blocking the LSP
        // handshake. The Rust server still enables GPU via ANTIVIBER_USE_GPU=1.
        commandLine.withEnvironment("MKL_NUM_THREADS", "1");
        commandLine.withEnvironment("OMP_NUM_THREADS", "1");
        commandLine.withEnvironment("RAYON_NUM_THREADS", "1");
        // The LLM service configuration. The Rust server reads
        // ANTIVIBER_LLM_BASE_URL, ANTIVIBER_LLM_MODEL and
        // ANTIVIBER_LLM_API_KEY itself (both the analysis listener and the
        // completion helper), so the settings only have to reach the
        // server's environment. Empty settings leave the variables unset
        // and the server's built-in defaults apply: a local Ollama at
        // http://localhost:11434/v1 with the gemma4:31b-cloud model and a
        // placeholder key that local services ignore.
        if (settings.llmBaseUrl != null && !settings.llmBaseUrl.isBlank()) {
            commandLine.withEnvironment("ANTIVIBER_LLM_BASE_URL", settings.llmBaseUrl);
            LOG.info("Setting ANTIVIBER_LLM_BASE_URL to " + settings.llmBaseUrl);
        }
        if (settings.llmModel != null && !settings.llmModel.isBlank()) {
            commandLine.withEnvironment("ANTIVIBER_LLM_MODEL", settings.llmModel);
            LOG.info("Setting ANTIVIBER_LLM_MODEL to " + settings.llmModel);
        }
        String llmApiKey = settings.getLlmApiKey();
        if (!llmApiKey.isBlank()) {
            commandLine.withEnvironment("ANTIVIBER_LLM_API_KEY", llmApiKey);
            // The key itself is deliberately not logged.
        }
        // The user's free-text standing instructions for the LLM. The Rust
        // server's prompt builders read ANTIVIBER_LLM_GUIDELINE and inject
        // it into the analysis and completion prompts; an empty setting
        // leaves the variable unset and the prompts stay guideline-free.
        if (settings.guideline != null && !settings.guideline.isBlank()) {
            commandLine.withEnvironment("ANTIVIBER_LLM_GUIDELINE", settings.guideline);
            LOG.info("Setting ANTIVIBER_LLM_GUIDELINE to " + settings.guideline);
        }
        // The analysis document's sections the user disabled in the
        // settings: the Rust prompt builder omits the disabled sections'
        // instructions, so the document contains only the enabled
        // sections. An empty value leaves the variable unset, which the
        // server reads as every section enabled - the default, and the
        // state of plugin builds without the section settings.
        if (settings.disabledSections != null && !settings.disabledSections.isBlank()) {
            commandLine.withEnvironment("ANTIVIBER_DISABLED_SECTIONS", settings.disabledSections);
            LOG.info("Setting ANTIVIBER_DISABLED_SECTIONS to " + settings.disabledSections);
        }
        // The auto-refresh threshold for edits: the minimum number of
        // characters that must have changed since the last completed
        // analysis before typing triggers a new one; 0 disables the
        // auto-refresh while writing. Always passed, because the setting
        // is an int with a default rather than an optional string; the
        // server's own fallback only applies when the variable is unset,
        // which does not happen when the plugin launches the server.
        commandLine.withEnvironment(
                "ANTIVIBER_AUTO_UPDATE_MIN_CHARS",
                String.valueOf(settings.autoUpdateMinChars));
        LOG.info("Setting ANTIVIBER_AUTO_UPDATE_MIN_CHARS to " + settings.autoUpdateMinChars);
        // Whether opening a file triggers its analysis: the server's
        // did_open handler schedules a tick 500 ms after the open when
        // this is on (see refresh_on_open in main.rs). Always passed,
        // like the auto-refresh threshold above: the setting is a
        // boolean with a default rather than an optional string, and the
        // server's own fallback (off) only applies when the variable is
        // unset, which does not happen when the plugin launches the
        // server. The IDE's tab-selection listener - which also fires on
        // file opens, because opening a file selects it - is gated by
        // the same setting on the IDE side (see
        // LspTabSelectionListener), so both open triggers follow one
        // toggle.
        commandLine.withEnvironment(
                "ANTIVIBER_REFRESH_ON_OPEN",
                String.valueOf(settings.refreshOnOpen));
        LOG.info("Setting ANTIVIBER_REFRESH_ON_OPEN to " + settings.refreshOnOpen);
        LOG.info("Greppa LSP command line: " + commandLine.getCommandLineString());
        return commandLine;
    }

    /**
     * Returns the server binary bundled with the plugin distribution for
     * the platform the IDE runs on (bin/ inside the plugin's installation
     * directory), or null when the plugin cannot be resolved or the
     * platform's binary is absent - e.g. a distribution built without it
     * (see the prepareSandbox blocks in rustrover/build.gradle.kts and
     * idea/build.gradle.kts), or an Intel Mac, whose platform is not
     * bundled (see bundledBinaryName).
     */
    @Nullable
    private static File bundledServerFile() {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        if (plugin == null) {
            return null;
        }
        File binary = plugin.getPluginPath().resolve(BUNDLED_SERVER_DIR).resolve(bundledBinaryName()).toFile();
        return binary.exists() ? binary : null;
    }

    /**
     * The platform-specific name of the bundled server binary, picked by
     * the OS the IDE runs on. The Gradle build bundles one binary per
     * platform into the plugin's bin/ directory, and CI builds and stages
     * all three (see .github/workflows/build.yml):
     * lsp_server-windows-x64.exe, lsp_server-macos-arm64 and
     * lsp_server-linux-x64. macOS is arm64-only for now, so an Intel Mac
     * finds no binary and the launch fails with the missing bundled
     * server message above.
     */
    private static String bundledBinaryName() {
        if (SystemInfo.isMac) {
            return "lsp_server-macos-arm64";
        }
        if (SystemInfo.isWindows) {
            return "lsp_server-windows-x64.exe";
        }
        return "lsp_server-linux-x64";
    }

    // No getLspCompletionSupport override: an earlier version tried to sort
    // the AI completion to the top of the popup by wrapping it in
    // PrioritizedLookupElement, but this IDE build has neither that class
    // in com.intellij.codeInsight.lookup nor a
    // customizeCompletion(CompletionItem, LookupElementBuilder) method on
    // LspCompletionSupport, so that code never compiled. The Rust server
    // still marks its single completion item with preselect/sortText; the
    // popup ordering is left to the IDE.

    public @Nullable LspServerListener createLspServerListener(@NotNull LspServer server) {
        LOG.info("Creating GreppaLspServerListener for server " + server);
        // The platform hands us the running server here, and this IDE
        // version's LspServerListener has no serverInitialized/serverStopped
        // callbacks, so this is the only reliable place to capture the
        // running server for the tab selection listener.
        GreppaLspServerListener.registerServer(server);
        return new GreppaLspServerListener();
    }
}
