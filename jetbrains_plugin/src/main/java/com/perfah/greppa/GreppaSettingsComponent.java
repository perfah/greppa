package com.perfah.greppa;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

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
                    "How the analyzed file relates to each file attached in the Relate tab, in both directions: what it depends on, and which attached files use it."),
            new Section("parts", "Parts",
                    "The file split into segments, each reviewed in a few sentences."),
            new Section("direction", "Suggested direction",
                    "What to do next on a high conceptual level."),
            new Section("problems", "Problems",
                    "The compilation and logical problems found in the file."),
            new Section("recommendations", "Recommendations",
                    "Concrete next steps: the critical ones with code snippets, the automatable ones with AI agent prompts, and the trivial ones to copy-paste yourself."));

    /**
     * One tool window tab the settings group the sections by: the tab's
     * display name and the section filter list its panel is built from
     * (see HintsToolWindowFactory). The settings' section groups are
     * derived from these entries, so each section always sits in the
     * group of the tab that shows it, however the tab lists change.
     */
    private record Tab(String title, List<String> sectionNames) {}

    /** The tabs with a section group in the settings, in tab order. */
    private static final List<Tab> TABS = List.of(
            new Tab("Interpret", HintsToolWindowFactory.interpretSections()),
            new Tab("Improve", HintsToolWindowFactory.improveSections()),
            new Tab("Relate", HintsToolWindowFactory.relateSections()));

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
    /**
     * Toggles the save-triggered refresh (see
     * GreppaSettingsState.refreshOnSave). A checkbox with its own label
     * rather than a labeled form row, because it is a single boolean.
     */
    private final JBCheckBox refreshOnSaveField = new JBCheckBox("Refresh interpretation on save");
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

        refreshOnSaveField.setToolTipText(
                "When on, saving a file (Ctrl+S, Save All and the IDE's autosaves alike) re-generates its interpretation. "
                        + "The refresh also runs when the file content is unchanged - e.g. after moving only the cursor - "
                        + "so the analysis reflects the current caret. Takes effect immediately; no restart needed.");

        for (Section section : SECTIONS) {
            JBCheckBox checkBox = new JBCheckBox(section.label());
            checkBox.setToolTipText(section.tooltip());
            sectionCheckBoxes.put(section.key(), checkBox);
        }

        // The restart note for the server settings. Every setting except
        // the save-refresh toggle is passed to the server as an
        // environment variable when the server is launched (see
        // GreppaLspServerDescriptor.createCommandLine), and a running
        // process's environment never changes, so those settings cannot
        // take effect while the current server keeps running. One note at
        // the bottom instead of a sentence in every tooltip, and it names
        // the actionable instruction (restart the IDE) rather than the
        // mechanism ("the next time the server is started"). The
        // save-refresh toggle is the exception named in the note: the
        // IDE's save listeners read it when the save events fire (see
        // GreppaProjectActivity), so it applies immediately. Rendered in
        // the context-help color so it reads as a note, not as another
        // form label. The color is set through setForeground, not
        // JBLabel.setFontColor: in this IDE build setFontColor takes the
        // new UI's FontColor type, not a java.awt.Color.
        JBLabel restartNote = new JBLabel(
                "The server settings take effect after the IDE is restarted; the save-refresh toggle applies immediately.");
        restartNote.setForeground(UIUtil.getContextHelpForeground());

        // The section checkboxes grouped by the tool window tab that
        // shows each section (see addSectionGroups): the "Sections" header
        // above one group per tab, with the sections every tab shows in
        // a leading group above the tab groups.
        JPanel sectionsPanel = new JPanel(new GridLayout(0, 1));
        addSectionGroups(sectionsPanel);
        JBLabel sectionsHeader = new JBLabel("Sections:");
        sectionsHeader.setToolTipText(
                "The sections of the analysis document generated for each file, grouped by the "
                        + "tool window tab that shows them. Disabled sections are left out of the document.");

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("LLM base URL:", llmBaseUrlField, 1, false)
                .addLabeledComponent("LLM model:", llmModelField, 1, false)
                .addLabeledComponent("LLM API key:", llmApiKeyField, 1, false)
                .addLabeledComponent("LLM guideline:", new JBScrollPane(guidelineField), 1, false)
                .addLabeledComponent("Min. chars changed for auto-refresh:", autoUpdateMinCharsField, 1, false)
                .addComponent(refreshOnSaveField)
                .addComponent(sectionsHeader)
                .addComponent(sectionsPanel)
                .addComponent(restartNote)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    /**
     * Fills the panel with the section checkboxes, grouped by the tool
     * window tab that shows each section. The groups reuse the tabs'
     * own filter lists (see TABS) and the same heading match the tabs
     * filter with (see HintsService.headingMatches), so the grouping
     * always mirrors the tabs. The sections every tab shows - Scope,
     * which each tab's list contains - sit in the leading "All tabs"
     * group above the per-tab groups; each remaining section sits in
     * the group of the one tab that shows it, and a section no tab's
     * list catches would sit in the Etc group, like the tab that shows
     * the leftover sections.
     */
    private void addSectionGroups(JPanel sectionsPanel) {
        List<Section> allTabs = new ArrayList<>();
        Map<Tab, List<Section>> perTab = new LinkedHashMap<>();
        for (Tab tab : TABS) {
            perTab.put(tab, new ArrayList<>());
        }
        List<Section> etc = new ArrayList<>();
        for (Section section : SECTIONS) {
            List<Tab> showing = new ArrayList<>();
            for (Tab tab : TABS) {
                if (HintsService.headingMatches(section.label(), tab.sectionNames())) {
                    showing.add(tab);
                }
            }
            if (showing.size() > 1) {
                allTabs.add(section);
            } else if (showing.size() == 1) {
                perTab.get(showing.get(0)).add(section);
            } else {
                etc.add(section);
            }
        }
        addSectionGroup(sectionsPanel, "All tabs",
                "Sections shown in every tab of the Greppa tool window.", allTabs);
        for (Tab tab : TABS) {
            addSectionGroup(sectionsPanel, tab.title(),
                    "Sections shown in the " + tab.title() + " tab of the Greppa tool window.",
                    perTab.get(tab));
        }
        addSectionGroup(sectionsPanel, "Etc",
                "Sections shown in the Etc tab of the Greppa tool window.", etc);
    }

    /**
     * Adds one group to the sections panel: a label naming the tab the
     * group's sections are shown in, followed by the group's checkboxes
     * indented under the label. Empty groups are skipped.
     */
    private void addSectionGroup(JPanel sectionsPanel, String title, String tooltip, List<Section> sections) {
        if (sections.isEmpty()) {
            return;
        }
        JBLabel label = new JBLabel(title);
        label.setToolTipText(tooltip);
        sectionsPanel.add(label);
        for (Section section : sections) {
            JBCheckBox checkBox = sectionCheckBoxes.get(section.key());
            checkBox.setBorder(new EmptyBorder(JBUI.insets(0, 16, 0, 0)));
            sectionsPanel.add(checkBox);
        }
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

    /** Returns whether the save-triggered refresh is on; see GreppaSettingsState.refreshOnSave. */
    public boolean isRefreshOnSave() {
        return refreshOnSaveField.isSelected();
    }

    /** Sets the shown save-refresh toggle. */
    public void setRefreshOnSave(boolean refreshOnSave) {
        refreshOnSaveField.setSelected(refreshOnSave);
    }
}
