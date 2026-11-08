package com.perfah.greppa;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
        name = "com.perfah.greppa.GreppaSettingsState",
        storages = @Storage("GreppaSettings.xml")
)
public class GreppaSettingsState implements PersistentStateComponent<GreppaSettingsState> {

    // The server executable needs no setting: the plugin launches the
    // binary bundled with its own distribution (see
    // GreppaLspServerDescriptor), so a fresh install is zero-setup. A
    // lspServerPath field existed here before the bundling; an entry
    // left over in an existing GreppaSettings.xml is simply ignored.

    /**
     * Base URL of the LLM service's OpenAI-compatible chat completions
     * endpoint, including the version path, e.g.
     * "http://localhost:11434/v1" (Ollama), "https://api.openai.com/v1"
     * (OpenAI) or "https://api.anthropic.com/v1/" (Anthropic's
     * OpenAI-compatible endpoint). Passed to the LSP server as the
     * ANTIVIBER_LLM_BASE_URL environment variable when the server is
     * launched; an empty value leaves the variable unset so the Rust
     * server's built-in default (a local Ollama) applies.
     */
    public String llmBaseUrl = "";

    /**
     * The LLM model the LSP server uses for its analyses and completions,
     * e.g. "qwen2.5-coder:7b" (Ollama) or "gpt-4o-mini" (OpenAI). Passed
     * to the server as the ANTIVIBER_LLM_MODEL environment variable; an
     * empty value leaves the variable unset so the Rust server's built-in
     * default model applies.
     */
    public String llmModel = "";

    /**
     * Free-text standing instructions for the LLM, e.g. style preferences
     * or constraints like "prefer functional style, never suggest unsafe
     * code". Injected into both the analysis and the completion prompts,
     * so it guides every LLM output. Passed to the server as the
     * ANTIVIBER_LLM_GUIDELINE environment variable when the server is
     * launched; an empty value leaves the variable unset and the prompts
     * stay guideline-free. Like the other LLM settings, a change takes
     * effect the next time the LSP server is started.
     */
    public String guideline = "";

    /**
     * The keys of the analysis document's sections the user disabled in
     * the settings, comma-separated; empty (the default) means every
     * section is enabled. The settings' checkboxes produce the keys (see
     * GreppaSettingsComponent.SECTIONS); passed to the LSP server as the
     * ANTIVIBER_DISABLED_SECTIONS environment variable when the server is
     * launched, the Rust prompt builder omits the disabled sections'
     * instructions so the analysis document contains only the enabled
     * sections. Unknown keys are ignored. Like the other LLM settings, a
     * change takes effect the next time the LSP server is started.
     */
    public String disabledSections = "";

    /**
     * Minimum number of characters that must have changed since the last
     * completed interpretation before writing triggers a new one; default value
     * 0 disables the automatic refresh while writing entirely (the document
     * stays in sync, but only opening a file, switching tabs or the
     * Refresh button re-analyze).
     */
    public int autoUpdateMinChars = 0;

    /**
     * Whether saving a document refreshes its interpretation: the save
     * listeners write the tick trigger file (see GreppaProjectActivity),
     * so the LSP server re-analyzes the saved file - also when the file
     * content is unchanged, e.g. after only the caret moved. Unlike the
     * other settings this one is IDE-side: it is read when the save
     * happens, not passed to the LSP server as an environment variable,
     * so toggling it applies immediately without an IDE restart. Default
     * on: the save-triggered refresh is the plugin's established
     * behavior.
     */
    public boolean refreshOnSave = true;

    /**
     * Whether the tool window has displayed an analysis at least once,
     * which means first-time setup is complete: the bundled LSP server
     * runs and the LLM service responds. Until then the tool window's
     * empty state shows the first-time setup steps (install an
     * OpenAI-compatible LLM service, pull the model); afterwards it shows
     * only the short "no interpretation yet" message, so a working setup
     * is never walked through the setup steps again. Set by
     * HintsPanel.refresh() on the EDT; persisted like the other settings
     * so it survives an IDE restart.
     */
    public boolean setupComplete = false;

    /**
     * Service name under which the API key is stored in the IDE's
     * PasswordSafe: the fully qualified name of this class plus the
     * setting, so the entry is unique and identifiable in the IDE's
     * password storage settings.
     */
    private static final String API_KEY_SERVICE_NAME =
            "com.perfah.greppa.GreppaSettingsState.llmApiKey";

    /** Account name of the PasswordSafe entry holding the API key. */
    private static final String API_KEY_ACCOUNT_NAME = "greppa";

    public static GreppaSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(GreppaSettingsState.class);
    }

    /**
     * Returns the API key (bearer token) for the LLM service from the
     * IDE's PasswordSafe, or an empty string when none is stored. Local
     * services such as Ollama ignore it; hosted services (OpenAI,
     * Anthropic's compatible endpoint, etc.) require it. Passed to the
     * server as the ANTIVIBER_LLM_API_KEY environment variable; an empty
     * value leaves the variable unset so the Rust server's built-in
     * placeholder key applies.
     *
     * The key lives in the PasswordSafe (the IDE's encrypted, OS-backed
     * credential store) instead of a plain field, so it never lands in
     * clear text in GreppaSettings.xml like the other settings. The
     * accessors are annotated @Transient so the XML serializer does not
     * bind them as a bean property, which would write the key right back
     * into the XML.
     */
    @Transient
    public String getLlmApiKey() {
        String password = PasswordSafe.getInstance().getPassword(credentialAttributes());
        return password == null ? "" : password;
    }

    /**
     * Stores the API key in the IDE's PasswordSafe; null is treated as
     * the empty string (no key). See getLlmApiKey for why the accessors
     * are annotated @Transient.
     */
    @Transient
    public void setLlmApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey;
        PasswordSafe.getInstance().set(credentialAttributes(), new Credentials(API_KEY_ACCOUNT_NAME, value));
    }

    private static CredentialAttributes credentialAttributes() {
        return new CredentialAttributes(API_KEY_SERVICE_NAME, API_KEY_ACCOUNT_NAME, null, false);
    }

    @Override
    public @Nullable GreppaSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull GreppaSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
