package com.example.greppa;

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
        name = "com.example.greppa.GreppaSettingsState",
        storages = @Storage("GreppaSettings.xml")
)
public class GreppaSettingsState implements PersistentStateComponent<GreppaSettingsState> {

    public String lspServerPath = "C:\\source\\greppa\\lsp_server\\target\\release\\lsp_server.exe";

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
     * Minimum number of characters that must have changed since the last
     * completed interpretation before writing triggers a new one; 0
     * disables the automatic refresh while writing entirely (the document
     * stays in sync, but only opening a file, switching tabs or the
     * reload button re-analyze). Passed to the LSP server as the
     * ANTIVIBER_AUTO_UPDATE_MIN_CHARS environment variable when the server
     * is launched. The default of 50 re-analyzes only after a substantial
     * edit instead of on every keystroke; 1 was the behavior before this
     * setting existed. Like the other settings, a change takes effect the
     * next time the LSP server is started.
     */
    public int autoUpdateMinChars = 50;

    /**
     * Service name under which the API key is stored in the IDE's
     * PasswordSafe: the fully qualified name of this class plus the
     * setting, so the entry is unique and identifiable in the IDE's
     * password storage settings.
     */
    private static final String API_KEY_SERVICE_NAME =
            "com.example.greppa.GreppaSettingsState.llmApiKey";

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
