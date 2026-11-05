package com.perfah.greppa;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HintsPanel extends JPanel {

    /** Color for the "Critical for understanding" heading (light theme, dark theme). */
    private static final Color CRITICAL_COLOR =
            new JBColor(new Color(0xC6, 0x28, 0x28), new Color(0xFF, 0x74, 0x74));
    /** Color for the "Automatable" heading (light theme, dark theme). */
    private static final Color AUTOMATABLE_COLOR =
            new JBColor(new Color(0x2E, 0x7D, 0x32), new Color(0x69, 0xDB, 0x7C));

    /**
     * Prefix of the synthetic links that navigate to a line range. The full
     * href is greppa://lines/START/END, where START and END are 1-based
     * line numbers, matching the line numbers the editor gutter shows (the
     * analysis prompt makes the model use those numbers); see
     * linkifyLineReferences and navigateToLineRange.
     */
    private static final String LINE_LINK_PREFIX = "greppa://lines/";

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);
    private final JLabel placeholderLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    /** The status label's normal foreground, restored after an error is cleared. */
    private final Color statusForeground = statusLabel.getForeground();
    private final JTextPane textPane = new JTextPane();
    /** Scroll pane around the text pane; a field so refresh() can preserve the scroll position. */
    private final JBScrollPane scrollPane = new JBScrollPane(textPane);
    /** Input for the optional topic/goal the analyses are viewed through. */
    private final JBTextField topicField = new JBTextField();
    /**
     * Toggles pausing the LSP server's analyses and completions. Its label
     * always names the action a click will perform: "Pause" while analyses
     * run, "Unpause" while they are paused; see syncPauseButton and
     * setPaused.
     */
    private final JToggleButton pauseButton = new JToggleButton("Pause");

    /**
     * Lists the current interpretation and the previous complete
     * interpretations of the selected file, newest first ("Current",
     * "Previous 1", ...). Selecting a previous version only displays it;
     * the Restore button makes the selection current again. See
     * buildHistoryPanel and HintsService.getHistoryForUri.
     */
    private final JComboBox<String> historyCombo = new JComboBox<>(new String[]{"Current"});
    /**
     * Makes the version selected in the history combo the current
     * interpretation again (HintsService.restoreFromHistory), e.g. after
     * a bad regeneration. Enabled only while a previous version is
     * selected.
     */
    private final JButton restoreButton = new JButton("Restore");
    /**
     * True while refresh() rebuilds the history combo model, so the
     * combo's own action listener ignores the programmatic selection
     * change instead of triggering another refresh.
     */
    private boolean updatingHistoryCombo = false;
    /**
     * The URI whose history the combo was last built for (null before the
     * first refresh). refresh() compares against it to detect file
     * switches.
     */
    private String renderedHistoryUri = null;
    /**
     * The history list the combo was last built from. refresh() compares
     * against it to detect that a new analysis completed, an entry was
     * restored, or the analysis was cleared - all of which shift the
     * entry indices, so the combo is rebuilt and the selection reset to
     * "Current".
     */
    private List<String> renderedHistory = List.of();

    /**
     * The Markdown currently shown in the text pane (null before the first
     * refresh). refresh() compares against it to skip redundant
     * re-renders: notifications also fire for selection changes and
     * re-reads of unchanged content, and re-rendering identical Markdown
     * would only flicker and reset the scroll position to the top.
     */
    private String renderedMarkdown = null;

    /**
     * The URI of the file whose analysis is currently shown (null before the
     * first refresh). When it changes, the next refresh starts at the
     * top of the document; refreshes of the same file keep the scroll
     * position, so the progressive updates of a streaming analysis can be
     * watched without the view jumping back to the top on every update.
     */
    private String renderedUri = null;

    /**
     * The server-reported error currently reflected in the status label,
     * or the empty string when none. refresh() compares against it so an
     * error, or its clearing, is applied exactly once.
     */
    private String renderedError = "";

    /**
     * The plain-text content of each code block of the rendered analysis,
     * in document order. MarkdownParser collects the texts while it
     * rewrites the code blocks and embeds the "Copy" button at each
     * block's top right, whose href references the entry by index (see
     * MarkdownParser.COPY_LINK_PREFIX); copyCodeBlock serves the clicks.
     * Assigned in refresh() together with the rendered document, so it
     * always matches what the text pane shows. Both refresh() and the
     * hyperlink events run on the EDT, so no synchronization is needed.
     */
    private List<String> codeBlocks = new ArrayList<>();

    /**
     * The sections of the analysis this panel shows, matched against the
     * document's ## headings (see HintsService.filterSections). Every tab
     * of the tool window passes its own subset, so all tabs render the
     * same document through different filters.
     */
    private final List<String> sectionNames;
    /**
     * True when the panel shows the sections the names do NOT select -
     * the complement view the Etc tab uses, so the sections no tab's
     * filter catches are visible and the tab lists can be adjusted to
     * the headings the server actually writes; see
     * HintsService.filterUnmatchedSections.
     */
    private final boolean unmatchedOnly;

    public HintsPanel(List<String> sectionNames) {
        this(sectionNames, false);
    }

    public HintsPanel(List<String> sectionNames, boolean unmatchedOnly) {
        super(new BorderLayout());
        this.sectionNames = List.copyOf(sectionNames);
        this.unmatchedOnly = unmatchedOnly;
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // The analysis arrives as Markdown; it is converted to HTML by
        // MarkdownParser and rendered here. JTextPane wraps text at the
        // viewport width and recomputes its height, so nothing is cut off
        // and resizing the tool window re-flows the document.
        textPane.setEditable(false);
        HTMLEditorKit editorKit = new HTMLEditorKit();
        editorKit.setStyleSheet(buildStyleSheet());
        textPane.setEditorKit(editorKit);
        textPane.setBackground(getBackground());

        // Line references in the analysis ("Lines 5-12") are turned into
        // greppa://lines links by linkifyLineReferences; clicking such a
        // link navigates the currently selected editor to the referenced
        // code. The "Copy" button at the top right of each code block
        // (embedded by MarkdownParser) copies the block's plain text to the
        // clipboard. Hyperlink events are dispatched on the EDT, which
        // is what the editor APIs require.
        textPane.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            String description = e.getDescription();
            if (description == null) {
                return;
            }
            if (description.startsWith(LINE_LINK_PREFIX)) {
                navigateToLineRange(description);
            } else if (description.startsWith(MarkdownParser.COPY_LINK_PREFIX)) {
                copyCodeBlock(description);
            }
        });

        contentPanel.add(scrollPane, "LIST");
        contentPanel.add(buildPlaceholder(), "PLACEHOLDER");
        // The north area holds two strips: the topic/goal input and, below
        // it, the history selector for previous interpretations of the
        // selected file.
        JPanel northPanel = new JPanel(new BorderLayout(0, 4));
        northPanel.add(buildTopicPanel(), BorderLayout.NORTH);
        northPanel.add(buildHistoryPanel(), BorderLayout.SOUTH);
        add(northPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        // Initial render only. The single listener registration is owned by
        // HintsToolWindowFactory, which stores the reference and removes
        // that exact instance when the content is removed. The panel must
        // not register itself too: that made every update refresh the
        // panel twice, and because every panel::refresh evaluation
        // creates a new lambda instance, the factory's removeListener
        // call never matched the panel's own registration - removed
        // panels stayed in HintsService's listener list forever.
        refresh();
    }

    private JPanel buildPlaceholder() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // Shown only when no analysis file exists for the current file at
        // all - the AI service unreachable or misconfigured, or nothing
        // ever analyzed. While an analysis is generating, the server
        // writes a "Generating interpretation..." placeholder to the
        // analysis file, so the panel shows that instead and this card
        // stays hidden.
        placeholderLabel.setText(
                "<html><center>" +
                "No interpretation yet.<br>" +
                "Open a file to generate its interpretation.<br>" +
                "If none appears, check the AI service settings in<br>" +
                "<b>Settings | Tools | Greppa</b>." +
                "</center></html>"
        );
        placeholderLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel link = new JLabel("<html><a href=''>Open Greppa Settings</a></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setHorizontalAlignment(SwingConstants.CENTER);
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                OpenGreppaSettingsAction.openSettings();
            }
        });

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insetsBottom(8);
        panel.add(placeholderLabel, gbc);

        gbc.gridy = 1;
        gbc.insets = JBUI.emptyInsets();
        panel.add(link, gbc);

        return panel;
    }

    /**
     * Builds the topic/goal input strip at the top of the panel. The text
     * it holds is stored in HintsService and sent to the LSP server with
     * every custom notification, so the server analyzes the file through
     * this topic when one is set.
     *
     * The topic is applied only explicitly: pressing Enter or clicking the
     * Refresh button stores it and asks the server to re-analyze the
     * current file. Typing alone triggers nothing - re-analyzing on every
     * keystroke would restart the LLM analysis for each character typed,
     * which is exactly what the explicit apply avoids.
     *
     * The strip also holds the Pause button, which stops the LSP server's
     * analyses and completions until it is toggled off again; see
     * setPaused.
     */
    private JPanel buildTopicPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.add(new JLabel("Focus:"), BorderLayout.WEST);
        topicField.getEmptyText().setText("Optional topic or goal, e.g. 'thread safety'");
        topicField.setToolTipText(
                "Optional topic/goal the interpretation is viewed through. Press Enter or click Refresh to set it and re-generate the current file's interpretation; typing alone triggers nothing.");
        topicField.addActionListener(e -> applyTopic());
        panel.add(topicField, BorderLayout.CENTER);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Re-generate the current file's interpretation, applying the topic/goal.");
        refreshButton.addActionListener(e -> applyTopic());

        pauseButton.setToolTipText(
                "Pause interpretations and AI completions so no tokens are spent on them. "
                        + "The current interpretation stays visible; resuming re-generates the current file's interpretation.");
        syncPauseButton();
        pauseButton.addActionListener(e -> setPaused(pauseButton.isSelected()));

        JPanel controls = new JPanel(new BorderLayout(4, 0));
        controls.add(refreshButton, BorderLayout.WEST);
        controls.add(pauseButton, BorderLayout.EAST);
        panel.add(controls, BorderLayout.EAST);
        return panel;
    }

    /**
     * Builds the history strip below the topic strip. The combo lists the
     * current interpretation and the previous complete interpretations of
     * the selected file; selecting a previous version displays it in the
     * panel without changing anything else, and the Restore button makes
     * the selected version the current interpretation again (see
     * HintsService.restoreFromHistory).
     *
     * Selecting an entry only refreshes the panel: the displayed document
     * is the selected history entry while the selection points at one,
     * and the live document (including streaming updates) while the
     * selection points at "Current". The selection survives refreshes as
     * long as the history does not change; when a new analysis completes,
     * the entry indices shift, so the combo is rebuilt and the selection
     * resets to "Current" (see refresh).
     */
    private JPanel buildHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.add(new JLabel("History:"), BorderLayout.WEST);
        historyCombo.setToolTipText(
                "Previous interpretations of the current file, newest first. Selecting one only displays it; Restore makes it the current interpretation again.");
        historyCombo.addActionListener(e -> {
            if (updatingHistoryCombo) {
                return;
            }
            restoreButton.setEnabled(historyCombo.getSelectedIndex() > 0);
            refresh();
        });
        panel.add(historyCombo, BorderLayout.CENTER);

        restoreButton.setToolTipText(
                "Make the selected previous interpretation the current one again. The interpretation it replaces is kept in the history, so this can be undone the same way.");
        restoreButton.setEnabled(false);
        restoreButton.addActionListener(e -> restoreSelectedHistory());
        panel.add(restoreButton, BorderLayout.EAST);
        return panel;
    }

    /**
     * Rebuilds the history combo from renderedHistory and resets the
     * selection to "Current". Called only when the file or its history
     * changed, because the entry indices shifted; while the history is
     * unchanged the user's selection is preserved. The guard flag keeps
     * the combo's action listener from treating the programmatic
     * selection change as a user selection.
     */
    private void rebuildHistoryCombo() {
        updatingHistoryCombo = true;
        historyCombo.removeAllItems();
        historyCombo.addItem("Current");
        for (int i = 0; i < renderedHistory.size(); i++) {
            historyCombo.addItem("Previous " + (i + 1));
        }
        historyCombo.setSelectedIndex(0);
        restoreButton.setEnabled(false);
        updatingHistoryCombo = false;
    }

    /**
     * Makes the version selected in the history combo the current
     * interpretation of the selected file again. The restore swaps the
     * two documents (see HintsService.restoreFromHistory), the change
     * notification rebuilds the combo, and the next refresh shows the
     * restored document as the current one.
     */
    private void restoreSelectedHistory() {
        int index = historyCombo.getSelectedIndex() - 1;
        String uri = HintsService.getCurrentFileUri();
        if (index < 0 || uri == null) {
            return;
        }
        HintsService.restoreFromHistory(uri, index);
    }

    /**
     * Stores the field's text as the current topic and asks the server for
     * a fresh analysis of the current file, so the new topic takes effect
     * immediately instead of only on the next file change or tab switch.
     *
     * Called by the Refresh button and by pressing Enter in the topic
     * field; typing alone never triggers anything, so the LLM is never
     * re-run per keystroke. The status label confirms that the topic was
     * stored, because the field itself gives no apply feedback; the next
     * refresh overwrites the label once the re-analysis arrives.
     */
    private void applyTopic() {
        String topic = topicField.getText().trim();
        HintsService.setTopic(topic);
        statusLabel.setText(topic.isBlank() ? "Topic cleared" : "Topic set: " + topic);
        HintsFilePoller.pollNow();
    }

    /**
     * Syncs the topic field with the stored topic. The topic is global
     * (HintsService) while every tab has its own field, so a topic set on
     * one tab must appear in the other tabs' fields too. The field is
     * skipped while it has focus, so a refresh never discards text the
     * user is typing before it is applied.
     */
    private void syncTopicField() {
        if (topicField.hasFocus()) {
            return;
        }
        String stored = HintsService.getTopic();
        if (!Objects.equals(topicField.getText(), stored)) {
            topicField.setText(stored);
        }
    }

    /**
     * Sets the pause button's selection and label from the current pause
     * state. Called from refresh, so a panel recreated while paused shows
     * "Unpause" instead of a selected button still reading "Pause", and
     * the tabs stay consistent when the pause is toggled on another tab.
     */
    private void syncPauseButton() {
        boolean paused = HintsService.isPaused();
        pauseButton.setSelected(paused);
        pauseButton.setText(paused ? "Unpause" : "Pause");
    }

    /**
     * Pauses or resumes the LSP server's analyses and completions. The
     * state is stored in HintsService (so a recreated panel restores the
     * button) and written to the pause file the Rust server polls for;
     * see HintsFilePoller.writePauseFile.
     *
     * The button's label always names the action a click will perform:
     * "Pause" while analyses run, "Unpause" while they are paused. The
     * selected look alone did not make the two states obvious enough.
     *
     * While paused the panel keeps showing the current analysis: the
     * server runs no ticks, so no new analysis files appear and the
     * poller has nothing new to forward. Resuming makes the server
     * re-analyze the current file immediately, so the status text is
     * replaced as soon as that analysis arrives.
     */
    private void setPaused(boolean paused) {
        HintsService.setPaused(paused);
        pauseButton.setText(paused ? "Unpause" : "Pause");
        statusLabel.setText(paused ? "Paused - no interpretations or completions" : "Resumed");
    }

    public void refresh() {
        SwingUtilities.invokeLater(() -> {
            // The topic and the pause state are global (HintsService), but
            // every tab has its own panel with its own copy of the controls;
            // sync them on every refresh so a topic set or a pause toggled
            // on one tab is reflected on the other tabs. The topic field is
            // skipped while focused, so a refresh never discards text the
            // user is typing before it is applied.
            syncPauseButton();
            syncTopicField();

            // The server-reported error is handled before the unchanged-
            // Markdown early return: an error can arrive while the document
            // stays unchanged (the failed analysis still wrote its fallback
            // text), and it must reach the status label anyway.
            String error = HintsService.getErrorMessage();
            boolean errorChanged = !Objects.equals(error, renderedError);

            // The history of the selected file, and whether the history
            // combo must be rebuilt: the file changed, a new analysis
            // completed, an entry was restored, or the analysis was
            // cleared. All of these shift the entry indices, so the combo
            // is rebuilt and the selection resets to "Current".
            String currentUri = HintsService.getCurrentFileUri();
            List<String> history = HintsService.getHistoryForUri(currentUri);
            boolean historyChanged = !Objects.equals(currentUri, renderedHistoryUri)
                    || !Objects.equals(history, renderedHistory);
            renderedHistoryUri = currentUri;
            renderedHistory = history;
            if (historyChanged) {
                rebuildHistoryCombo();
            }

            // The document to display: the selected previous version while
            // the user is browsing the history, otherwise the live document
            // (the merge of the last complete document and the streaming
            // partial one). Both are reduced to this panel's view - the
            // sections the names select, or the sections they leave out for
            // the Etc tab - so every tab shows only its own part of the
            // analysis.
            int selectedVersion = historyCombo.getSelectedIndex() - 1;
            String document = selectedVersion >= 0 && selectedVersion < history.size()
                    ? history.get(selectedVersion)
                    : HintsService.getMarkdown();
            String markdown = filterDocument(document);

            // Skip redundant refreshes: the same Markdown often arrives
            // repeatedly (selection changes, re-reads of unchanged files),
            // and re-rendering it would only flicker and reset the scroll
            // position, which looks like the page randomly updating while
            // nothing changed.
            if (Objects.equals(markdown, renderedMarkdown)) {
                if (errorChanged) {
                    renderedError = error;
                    showStatus(error, markdown);
                }
                return;
            }
            renderedMarkdown = markdown;
            renderedError = error;

            // A different file's analysis starts at the top of the
            // document; refreshes of the same file keep the scroll
            // position, so the progressive updates of a streaming analysis
            // can be watched without the view jumping around.
            boolean fileChanged = !Objects.equals(currentUri, renderedUri);
            renderedUri = currentUri;

            JViewport viewport = scrollPane.getViewport();
            Point viewPosition = fileChanged ? new Point(0, 0) : viewport.getViewPosition();

            if (markdown.isBlank()) {
                cardLayout.show(contentPanel, "PLACEHOLDER");
            } else {
                List<String> renderedCodeBlocks = new ArrayList<>();
                String html = MarkdownParser.toHtml(markdown, CRITICAL_COLOR, AUTOMATABLE_COLOR, renderedCodeBlocks);
                codeBlocks = renderedCodeBlocks;
                textPane.setText(linkifyLineReferences(html));
                textPane.setCaretPosition(0);
                cardLayout.show(contentPanel, "LIST");
            }

            showStatus(error, markdown);

            revalidate();
            repaint();
            // setText and the caret reset scroll the viewport back to the
            // top; restore the previous position once the new document has
            // been laid out.
            SwingUtilities.invokeLater(() -> viewport.setViewPosition(viewPosition));
        });
    }

    /**
     * Returns the document reduced to this panel's view: the sections the
     * names select, or - for the Etc tab - the sections they do not (see
     * HintsService.filterUnmatchedSections).
     */
    private String filterDocument(String document) {
        return unmatchedOnly
                ? HintsService.filterUnmatchedSections(document, sectionNames)
                : HintsService.filterSections(document, sectionNames);
    }

    /**
     * Updates the status label. A server-reported error (the LLM service
     * failed; see HintsService.getErrorMessage) outranks the routine
     * statuses, because it is the only explanation the user gets for a
     * missing or partial interpretation. It is shown in the same red as
     * the "Critical for understanding" heading so it stands out; the label
     * returns to its normal foreground once the server clears the error.
     */
    private void showStatus(String error, String markdown) {
        if (error.isBlank()) {
            statusLabel.setForeground(statusForeground);
            statusLabel.setText(markdown.isBlank() ? " " : "Interpretation loaded");
        } else {
            statusLabel.setForeground(CRITICAL_COLOR);
            statusLabel.setText("Error: " + error);
        }
    }

    /**
     * Turns every "Lines X-Y" / "Line X" mention in the rendered HTML into
     * an greppa://lines/X/Y link, so clicking it navigates the editor to
     * the referenced code. The analysis prompt makes the Problems, Current
     * functionality, Suggested actions and Specific recommendations
     * sections carry such references.
     *
     * The HTML is walked tag by tag and only the text between tags is
     * modified, so tag names and attribute values are never touched. Text
     * inside code blocks is linkified too: an AI agent prompt that
     * references lines is a genuine reference, so making it clickable is a
     * feature, not a bug.
     */
    private static String linkifyLineReferences(String html) {
        // "Lines 5-12", "Line 7", with a hyphen or en-dash between the
        // numbers and optional spaces around it. Case-insensitive, so
        // lowercase mentions linkify too.
        Pattern pattern = Pattern.compile(
                "\\b(lines?)\\s+(\\d+)\\s*(?:[-\u2013]\\s*(\\d+))?\\b",
                Pattern.CASE_INSENSITIVE);
        StringBuilder result = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            if (html.charAt(i) == '<') {
                // Copy tags verbatim.
                int close = html.indexOf('>', i);
                if (close < 0) {
                    result.append(html, i, html.length());
                    break;
                }
                result.append(html, i, close + 1);
                i = close + 1;
            } else {
                int nextTag = html.indexOf('<', i);
                int end = nextTag < 0 ? html.length() : nextTag;
                result.append(linkifyText(html.substring(i, end), pattern));
                i = end;
            }
        }
        return result.toString();
    }

    /** Returns the text with every line reference wrapped in a link. */
    private static String linkifyText(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length() + 64);
        int last = 0;
        do {
            result.append(text, last, matcher.start());
            String start = matcher.group(2);
            // A single-line reference ("Line 7") links to that line only.
            String end = matcher.group(3) != null ? matcher.group(3) : start;
            result.append("<a href=\"").append(LINE_LINK_PREFIX)
                    .append(start).append('/').append(end)
                    .append("\">").append(matcher.group()).append("</a>");
            last = matcher.end();
        } while (matcher.find());
        result.append(text, last, text.length());
        return result.toString();
    }

    /**
     * Navigates the currently selected editor to the line range named by
     * an greppa://lines/START/END link. The panel shows the analysis of
     * the currently selected file, so the range always refers to the file
     * the user is looking at.
     *
     * The link's line numbers are 1-based, matching the line numbers the
     * editor gutter shows (the analysis prompt makes the model use those);
     * LogicalPosition is 0-based, so the conversion happens here.
     *
     * The analysis can be stale (the file changed after it ran), so the
     * line is clamped into the document instead of throwing; the jump then
     * lands at the end of the file, which is the best an outdated range
     * can offer. The caret moves to the start of the range and the editor
     * centers on it.
     */
    private static void navigateToLineRange(String description) {
        String[] parts = description.substring(LINE_LINK_PREFIX.length()).split("/");
        if (parts.length == 0) {
            return;
        }
        int startLine;
        try {
            startLine = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return;
        }
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        for (Project project : projects) {
            if (project.isDisposed()) {
                continue;
            }
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                continue;
            }
            int lineCount = editor.getDocument().getLineCount();
            if (lineCount == 0) {
                return;
            }

            int line = Math.max(0, Math.min(startLine - 1, lineCount - 1));

            editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, 0));
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            return;
        }
    }

    /**
     * Copies the code block named by an greppa://copy/N link to the
     * clipboard. The link's N selects the entry in codeBlocks, which
     * MarkdownParser filled with each block's plain text during the last
     * render. The copy goes through the IDE's CopyPasteManager, so the
     * text also lands in the IDE's clipboard history. The status label
     * confirms the copy, because the clipboard itself gives no feedback
     * inside the panel; the next refresh overwrites the label as usual.
     */
    private void copyCodeBlock(String description) {
        String indexText = description.substring(MarkdownParser.COPY_LINK_PREFIX.length());
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
            return;
        }
        if (index < 0 || index >= codeBlocks.size()) {
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(codeBlocks.get(index)));
        statusLabel.setText("Code block copied to clipboard");
    }

    /**
     * Builds the CSS used by the HTML renderer. All colors are written as
     * #rrggbb hex values because Swing's built-in CSS engine does not
     * understand the rgb(...) notation - rgb() values are silently
     * ignored, which is why no colors showed up before. Colors are theme
     * aware through JBColor (first constructor value for the light theme,
     * second for the dark theme):
     * - h1-h6 headings are bold, and h1/h2 use an accent blue so the
     *   document structure stands out.
     * - Code blocks and inline code get a subtle background so snippets are
     *   clearly delimited from prose. Code blocks arrive as div.code-block
     *   elements, not pre: MarkdownParser rewrites them because Swing's
     *   HTML renderer never wraps pre content, which gave the panel a
     *   horizontal scroll bar whenever a code line was wider than the
     *   tool window.
     * - The "Copy" button at the top right of every code block is styled
     *   here: div.copy-row right-aligns the button (Swing's HTML renderer
     *   supports neither absolute positioning nor floats, so the button
     *   cannot overlay the code GitHub-style), and a.copy-button gives the
     *   link its button look - accent blue background, bold label, no
     *   underline. The nbsp entities MarkdownParser pads the label with
     *   provide the horizontal breathing room, because Swing's CSS has no
     *   padding support for inline elements.
     * - The "Critical for understanding" and "Automatable" category
     *   headings of the Suggested actions section are not styled here:
     *   they are colored by MarkdownParser with font tags, which Swing's
     *   HTML renderer supports more reliably than CSS classes.
     * Margins are generous because the tool window scrolls, so extra
     * spacing costs nothing.
     */
    private static StyleSheet buildStyleSheet() {
        Font base = uiFont();
        Color foreground = new JLabel().getForeground();
        int size = base.getSize();

        Color accent = new JBColor(new Color(0x21, 0x5C, 0xF3), new Color(0x7A, 0xA5, 0xFF));
        Color codeBackground = new JBColor(new Color(0xF2, 0xF2, 0xF2), new Color(0x3C, 0x3F, 0x41));
        // Label color of the Copy button: white on the dark accent blue of
        // the light theme, near-black on the light accent blue of the dark
        // theme, so the button stays readable in both themes.
        Color buttonText = new JBColor(Color.WHITE, new Color(0x18, 0x18, 0x18));

        StyleSheet style = new StyleSheet();
        style.addRule("body {font-family: \"" + base.getFamily() + "\"; font-size: " + size
                + "pt; color: " + toHex(foreground) + "; margin-right: 6;}");
        style.addRule("h1 {font-size: " + (size + 7) + "pt; font-weight: bold; color: "
                + toHex(accent) + "; margin-top: 18; margin-bottom: 10;}");
        style.addRule("h2 {font-size: " + (size + 5) + "pt; font-weight: bold; color: "
                + toHex(accent) + "; margin-top: 20; margin-bottom: 10;}");
        style.addRule("h3 {font-size: " + (size + 3)
                + "pt; font-weight: bold; margin-top: 14; margin-bottom: 8;}");
        style.addRule("h4 {font-size: " + (size + 1)
                + "pt; font-weight: bold; margin-top: 12; margin-bottom: 6;}");
        style.addRule("h5 {font-size: " + size
                + "pt; font-weight: bold; margin-top: 10; margin-bottom: 5;}");
        style.addRule("h6 {font-size: " + size
                + "pt; font-weight: bold; margin-top: 10; margin-bottom: 5;}");
        style.addRule("p {margin-top: 5; margin-bottom: 5;}");
        style.addRule("ul {margin-top: 5; margin-bottom: 5;}");
        style.addRule("ol {margin-top: 5; margin-bottom: 5;}");
        style.addRule("li {margin-top: 3; margin-bottom: 3;}");
        style.addRule("div.code-block {font-family: \"Monospaced\"; font-size: " + size
                + "pt; background-color: " + toHex(codeBackground)
                + "; margin-top: 10; margin-bottom: 10;"
                + " padding-top: 8; padding-bottom: 8; padding-left: 8; padding-right: 8;}");
        style.addRule("code {font-family: \"Monospaced\"; background-color: "
                + toHex(codeBackground) + ";}");
        style.addRule("blockquote {color: " + toHex(Color.GRAY) + "; font-style: italic;"
                + " margin-top: 8; margin-bottom: 8; margin-left: 12; margin-right: 12;}");
        style.addRule("a {color: " + toHex(accent) + ";}");
        // The Copy button of every code block: a right-aligned row
        // (div.copy-row) holding a link dressed up as a button
        // (a.copy-button). See the method comment for why the button is a
        // right-aligned row rather than an overlay.
        style.addRule("div.copy-row {text-align: right; margin-bottom: 4;}");
        style.addRule("a.copy-button {background-color: " + toHex(accent)
                + "; color: " + toHex(buttonText)
                + "; text-decoration: none; font-weight: bold;}");
        return style;
    }

    /**
     * Formats a color as #rrggbb, the notation Swing's CSS engine
     * supports (unlike rgb()).
     */
    private static String toHex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }

    /** Returns the UI label font used for all rendered text. */
    private static Font uiFont() {
        return new JLabel().getFont();
    }
}
