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
     * server binary; the Gradle build puts it there (see the
     * prepareSandbox block in build.gradle.kts).
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
        GreppaSettingsState settings = GreppaSettingsState.getInstance();
        String serverPath = settings.lspServerPath;

        LOG.info("GreppaLspServerDescriptor.createCommandLine called with server path: " + serverPath);

        File serverFile = resolveServerFile(serverPath);

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
        LOG.info("Greppa LSP command line: " + commandLine.getCommandLineString());
        return commandLine;
    }

    /**
     * Resolves the server executable to launch: the configured path when
     * the Server path setting is non-empty, otherwise the binary bundled
     * with the plugin's own distribution, so a fresh install runs with
     * no path setup at all. Throws when neither exists - the plugin
     * cannot run without a server.
     */
    private File resolveServerFile(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            File serverFile = new File(configuredPath);
            if (!serverFile.isAbsolute()) {
                String basePath = getProject().getBasePath();
                if (basePath != null) {
                    serverFile = new File(basePath, configuredPath);
                    LOG.info("Resolved relative server path against project base path: " + serverFile.getAbsolutePath());
                }
            }
            if (!serverFile.exists()) {
                String message = "Greppa server binary does not exist: " + serverFile.getAbsolutePath();
                LOG.error(message);
                throw new RuntimeException(message);
            }
            return serverFile;
        }

        File bundled = bundledServerFile();
        if (bundled == null) {
            String message = "No Greppa server available: the Server path setting is empty and the plugin installation contains no bundled server binary. Set the server path under Settings | Tools | Greppa.";
            LOG.error(message);
            throw new RuntimeException(message);
        }
        LOG.info("Using the server binary bundled with the plugin: " + bundled.getAbsolutePath());
        return bundled;
    }

    /**
     * Returns the server binary bundled with the plugin distribution
     * (bin/ inside the plugin's installation directory), or null when
     * the plugin cannot be resolved or the binary is absent - e.g. a
     * distribution built without it (see the prepareSandbox block in
     * build.gradle.kts).
     */
    @Nullable
    private static File bundledServerFile() {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        if (plugin == null) {
            return null;
        }
        String binaryName = SystemInfo.isWindows ? "lsp_server.exe" : "lsp_server";
        File binary = plugin.getPluginPath().resolve(BUNDLED_SERVER_DIR).resolve(binaryName).toFile();
        return binary.exists() ? binary : null;
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
