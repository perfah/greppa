package com.perfah.greppa;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.GridLayout;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.*;

public class GreppaSettingsComponent {

    /**
     * One toggleable section of the analysis document: the key exchanged
     * with the LSP server, the checkbox label, and the tooltip
     * describing what the section contains.
     */
    private record Section(String key, String label, String tooltip) {}

    /**
     * The analysis document's sections in document order. The keys match
     * the section keys the Rust prompt builder toggles (see prompts.rs);
     * they travel through the disabledSections setting and the
     * ANTIVIBER_DISABLED_SECTIONS environment variable, so the two
     * lists must stay in sync.
     */
    private static final List<Section> SECTIONS = List.of(
            new Section("scope", "Scope",
                    "Answers to questions found in the code, and the topic summary when a topic is set."),
            new Section("cursor", "Cursor",
                    "Describes the exact part of the code the caret is in."),
            new Section("explanation", "Explanation",
                    "The file explained: simplified, technical, at five levels of difficulty, its key concepts, and a flowchart."),
            new Section("relationship", "Relationship to other files",
                    "How the analyzed file uses each file attached in the Relate tab."),
            new Section("parts", "Parts",
                    "The file split into segments, each reviewed in a few sentences."),
            new Section("direction", "Suggested direction",
                    "What to do next on a high conceptual level."),
            new Section("problems", "Problems",
                    "The compilation and logical problems found in the file."),
            new Section("recommendations", "Recommendations",
                    "Concrete next steps: the critical ones with code snippets, the automatable ones with AI agent prompts."));

    private final JPanel panel;
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
    /** The section checkboxes by section key, built from SECTIONS in document order. */
    private final Map<String, JBCheckBox> sectionCheckBoxes = new LinkedHashMap<>();

    public GreppaSettingsComponent() {
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
                        + "Applied to all interpretations and completions. Empty = no guideline.");

        autoUpdateMinCharsField.setToolTipText(
                "Minimum number of characters that must have changed since the last interpretation "
                        + "before typing triggers a new one. 0 disables the automatic refresh while typing; "
                        + "opening a file, switching tabs and the Refresh button still re-analyze. "
                        + "Default: 0 (automatic refresh off).");

        for (Section section : SECTIONS) {
            JBCheckBox checkBox = new JBCheckBox(section.label());
            checkBox.setToolTipText(section.tooltip());
            sectionCheckBoxes.put(section.key(), checkBox);
        }

        // The single restart note for the whole page. Every setting is
        // passed to the server as an environment variable when the server
        // is launched (see GreppaLspServerDescriptor.createCommandLine),
        // and a running process's environment never changes, so no
        // setting can take effect while the current server keeps running.
        // One note at the bottom instead of a sentence in every tooltip,
        // and it names the actionable instruction (restart the IDE)
        // rather than the mechanism ("the next time the server is
        // started"). Rendered in the context-help color so it reads as a
        // note, not as another form label.
        JBLabel restartNote = new JBLabel("All changes take effect after the IDE is restarted.");
        restartNote.setFontColor(UIUtil.getContextHelpForeground());

        // The section checkboxes in one single-column panel, added to
        // the form as a single component so the builder chain stays
        // readable.
        JPanel sectionsPanel = new JPanel(new GridLayout(0, 1));
        for (JBCheckBox checkBox : sectionCheckBoxes.values()) {
            sectionsPanel.add(checkBox);
        }
        JBLabel sectionsHeader = new JBLabel("Analysis sections:");
        sectionsHeader.setToolTipText(
                "The sections of the analysis document generated for each file. Disabled sections are left out of the document.");

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("LLM base URL:", llmBaseUrlField, 1, false)
                .addLabeledComponent("LLM model:", llmModelField, 1, false)
                .addLabeledComponent("LLM API key:", llmApiKeyField, 1, false)
                .addLabeledComponent("LLM guideline:", new JBScrollPane(guidelineField), 1, false)
                .addLabeledComponent("Min. chars changed for auto-refresh:", autoUpdateMinCharsField, 1, false)
                .addComponent(sectionsHeader)
                .addComponent(sectionsPanel)
                .addComponent(restartNote)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public @NotNull JPanel getPanel() {
        return panel;
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

    /**
     * Returns the keys of the disabled sections, comma-separated in
     * document order; empty when every section is enabled.
     */
    public @NotNull String getDisabledSections() {
        StringBuilder disabled = new StringBuilder();
        for (Section section : SECTIONS) {
            if (!sectionCheckBoxes.get(section.key()).isSelected()) {
                if (disabled.length() > 0) {
                    disabled.append(',');
                }
                disabled.append(section.key());
            }
        }
        return disabled.toString();
    }

    /**
     * Sets the checked sections from the comma-separated keys of the
     * disabled ones: a section is checked unless its key is named. Null
     * is treated as the empty string (every section enabled); unknown
     * keys are ignored.
     */
    public void setDisabledSections(String disabledSections) {
        Set<String> disabled = new HashSet<>();
        if (disabledSections != null) {
            for (String key : disabledSections.split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) {
                    disabled.add(trimmed);
                }
            }
        }
        for (Section section : SECTIONS) {
            sectionCheckBoxes.get(section.key()).setSelected(!disabled.contains(section.key()));
        }
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
