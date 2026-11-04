package com.perfah.greppa;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class GreppaSettingsComponent {

    private final JPanel panel;
    private final TextFieldWithBrowseButton lspServerPathField = new TextFieldWithBrowseButton();
    /** Base URL of the LLM service; empty means the server's default. */
    private final JTextField llmBaseUrlField = new JTextField();
    /** Input for the LLM model name; empty means the server's default. */
    private final JTextField llmModelField = new JTextField();
    /** Input for the LLM service's API key; empty means the server's placeholder key. */
    private final JPasswordField llmApiKeyField = new JPasswordField();
    /** Free-text standing instructions for the LLM; empty means none. */
    private final JTextArea guidelineField = new JTextArea(4, 40);
    /**
     * Minimum number of characters that must have changed since the last
     * interpretation before writing triggers a new one; 0 disables the
     * automatic refresh while writing. A spinner instead of a text field,
     * so the value is always a valid non-negative number.
     */
    private final JSpinner autoUpdateMinCharsField =
            new JSpinner(new SpinnerNumberModel(50, 0, Integer.MAX_VALUE, 1));

    public GreppaSettingsComponent() {
        lspServerPathField.addBrowseFolderListener(
                "Select Server Binary",
                "Path to the Greppa server executable",
                null,
                new FileChooserDescriptor(true, false, false, false, false, false)
        );
        lspServerPathField.setToolTipText(
                "Path to the Greppa server executable. Leave empty to use the server bundled with the plugin; "
                        + "set it to launch a custom build instead. "
                        + "Takes effect the next time the server is started.");

        llmBaseUrlField.setToolTipText(
                "OpenAI-compatible base URL of the LLM service, including the version path, "
                        + "e.g. 'http://localhost:11434/v1' (Ollama), 'https://api.openai.com/v1' (OpenAI) "
                        + "or 'https://api.anthropic.com/v1/' (Anthropic). "
                        + "Empty = the default 'http://localhost:11434/v1' (a local Ollama).");

        llmModelField.setToolTipText(
                "LLM model used for analyses and completions, e.g. 'qwen2.5-coder:7b' (Ollama) "
                        + "or 'gpt-4o-mini' (OpenAI). "
                        + "Empty = the default model 'gemma4:31b-cloud' "
                        + "(run 'ollama pull gemma4:31b-cloud' once to install it).");

        llmApiKeyField.setToolTipText(
                "API key for the LLM service. Hosted services (OpenAI, Anthropic) require it; "
                        + "local services such as Ollama don't. Empty = no key.");

        // Free text, so wrap lines instead of growing one long line.
        guidelineField.setLineWrap(true);
        guidelineField.setWrapStyleWord(true);
        guidelineField.setToolTipText(
                "Free-text standing instructions for the LLM, e.g. 'prefer functional style, never suggest unsafe code'. "
                        + "Applied to all interpretations and completions. Empty = no guideline. "
                        + "Takes effect the next time the server is started.");

        autoUpdateMinCharsField.setToolTipText(
                "Minimum number of characters that must have changed since the last interpretation "
                        + "before typing triggers a new one. 0 disables the automatic refresh while typing; "
                        + "opening a file, switching tabs and the Refresh button still re-analyze. "
                        + "Default: 0 (automatic refresh off). "
                        + "Takes effect the next time the server is started.");

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Server path:", lspServerPathField, 1, false)
                .addLabeledComponent("LLM base URL:", llmBaseUrlField, 1, false)
                .addLabeledComponent("LLM model:", llmModelField, 1, false)
                .addLabeledComponent("LLM API key:", llmApiKeyField, 1, false)
                .addLabeledComponent("LLM guideline:", new JBScrollPane(guidelineField), 1, false)
                .addLabeledComponent("Min. chars changed for auto-refresh:", autoUpdateMinCharsField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public @NotNull JPanel getPanel() {
        return panel;
    }

    public @NotNull String getLspServerPath() {
        return lspServerPathField.getText();
    }

    public void setLspServerPath(@NotNull String path) {
        lspServerPathField.setText(path);
    }

    /** Returns the configured LLM base URL, or an empty string for the default. */
    public @NotNull String getLlmBaseUrl() {
        return llmBaseUrlField.getText();
    }

    /** Sets the shown LLM base URL; null is treated as the empty string. */
    public void setLlmBaseUrl(String baseUrl) {
        llmBaseUrlField.setText(baseUrl == null ? "" : baseUrl);
    }

    /** Returns the configured LLM model, or an empty string for the default. */
    public @NotNull String getLlmModel() {
        return llmModelField.getText();
    }

    /** Sets the shown LLM model; null is treated as the empty string. */
    public void setLlmModel(String model) {
        llmModelField.setText(model == null ? "" : model);
    }

    /** Returns the configured API key, or an empty string for the placeholder. */
    public @NotNull String getLlmApiKey() {
        return new String(llmApiKeyField.getPassword());
    }

    /** Sets the shown API key; null is treated as the empty string. */
    public void setLlmApiKey(String apiKey) {
        llmApiKeyField.setText(apiKey == null ? "" : apiKey);
    }

    /** Returns the configured guideline, or an empty string for none. */
    public @NotNull String getGuideline() {
        return guidelineField.getText();
    }

    /** Sets the shown guideline; null is treated as the empty string. */
    public void setGuideline(String guideline) {
        guidelineField.setText(guideline == null ? "" : guideline);
    }

    /** Returns the minimum number of changed chars for the auto-refresh; 0 disables it. */
    public int getAutoUpdateMinChars() {
        return (Integer) autoUpdateMinCharsField.getValue();
    }

    /** Sets the shown minimum number of changed chars for the auto-refresh. */
    public void setAutoUpdateMinChars(int minChars) {
        autoUpdateMinCharsField.setValue(minChars);
    }
}
