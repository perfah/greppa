package com.perfah.greppa;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The Imports pane of the Greppa Interpretation tool window (the
 * strip at the top of the Relate tab; see HintsToolWindowFactory and
 * OpenImportsPaneAction).
 *
 * It lists the local files the currently selected file imports plus the
 * files the user added manually, one row per entry: a checkbox, the
 * import string and the resolved file path. Found files are checked by
 * default, so they attach to the analysis as context without extra
 * clicks; unchecking detaches them. The attachment content is sent with
 * every trigger file (see HintsFilePoller.writeTriggerFile) and the
 * analysis prompt renders it after the analyzed code. Checking or
 * unchecking stores immediately but does not re-run the analysis; the
 * change applies from the next analysis on (Refresh, Reload, tab
 * switch, save).
 *
 * References the scan looked up but could not resolve to a project
 * file - standard-library and third-party paths such as
 * tower::Service, or a mod declaration without its file - are listed
 * at the bottom of the pane, so it is visible what the scan tried:
 * they are rendered as grey text without a checkbox and with "not
 * found" in place of the path, because there is no file to attach
 * (see ImportDetector, and HintsService.getAttachmentUris, which
 * skips entries without a resolved file). The Add file button covers
 * attaching the right file manually.
 *
 * Below the entries, the pane offers the recently opened files as
 * quick manual adds (see HintsService.getRecentFileUris): one button
 * per file that is neither the current file nor already attached.
 * This is how the reverse direction of a relationship gets attached:
 * the scan only looks at the current file's own references, so when
 * A imports B and the user is viewing B, A is offered as a quick add
 * once it has been open, and attaching it lets the analysis describe
 * how A uses B (the prompt's Relationship section splits into Depends
 * on and Used by).
 *
 * The scan behind the list is local and instant (see ImportDetector):
 * the file's text is scanned for quoted path-like strings, mod
 * declarations and use declarations, and every candidate is checked
 * for existence relative to the file, which keeps standard-library and
 * third-party imports out of the viable rows - the analysis relies on
 * the model's trained understanding of those. No LLM inference is
 * involved, so the pane never waits. The list is updated manually by
 * design: the scan runs once automatically the first time the pane
 * sees a file, and after that only through the pane's Refresh button.
 */
public class ImportsPanel extends JPanel {

    /** Names the file whose imports are shown. */
    private final JLabel fileLabel = new JLabel(" ");
    /** One row per import; rebuilt on refresh. */
    private final JPanel rowsPanel = new JPanel();
    private final JBScrollPane scrollPane = new JBScrollPane(rowsPanel);
    private final JLabel statusLabel = new JLabel(" ");
    /**
     * The quick-add strip below the entry list: one button per recently
     * opened file (see HintsService.getRecentFileUris) that is neither
     * the current file nor already attached, so attaching a file the
     * user just worked with - e.g. the file that imports the current
     * one, which the scan cannot discover - is one click instead of a
     * trip through the Add file chooser. Rebuilt on every refresh;
     * hidden while empty.
     */
    private final JPanel recentFilesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    /** How many recently opened files the quick-add strip offers at most. */
    private static final int MAX_QUICK_ADDS = 5;
    /**
     * The URI whose imports were last rendered. Initialized to a
     * non-null sentinel so the first refresh always builds the rows
     * (also when no file is open); afterwards refresh() compares
     * against it to skip redundant rebuilds.
     */
    private String renderedUri = "";
    /** The entries last rendered, to skip redundant rebuilds. */
    private List<HintsService.ImportEntry> renderedEntries = null;

    public ImportsPanel() {
        super(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        add(buildToolbar(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        // The quick-add strip sits between the entries and the status
        // line, outside the scroll pane, so it stays visible however
        // long the entry list grows.
        JPanel southPanel = new JPanel(new BorderLayout(0, 4));
        southPanel.add(recentFilesPanel, BorderLayout.NORTH);
        southPanel.add(statusLabel, BorderLayout.SOUTH);
        add(southPanel, BorderLayout.SOUTH);
        refresh();
    }

    private JPanel buildToolbar() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.add(fileLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Re-run the local import scan for the current file.");
        refreshButton.addActionListener(e -> refreshImports());
        buttons.add(refreshButton);

        JButton addButton = new JButton("Add file…");
        addButton.setToolTipText("Attach a file to the analysis that the scan did not find.");
        addButton.addActionListener(e -> addFile());
        buttons.add(addButton);

        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    /**
     * The pane's Refresh button: re-runs the local import scan for the
     * current file. The result replaces the scanned entries; the
     * checked state of entries that still resolve to the same file
     * survives, and manually added files are kept (see
     * HintsService.setImportsForUri).
     */
    private void refreshImports() {
        String uri = HintsService.getCurrentFileUri();
        if (uri == null) {
            return;
        }
        HintsService.setImportsForUri(uri, detectImports(uri));
    }

    /**
     * The Add file button: lets the user attach a file the scan missed
     * or could not resolve, e.g. a Rust use path or a dotted Java
     * import. The added file is checked immediately and survives
     * re-scans.
     */
    private void addFile() {
        String uri = HintsService.getCurrentFileUri();
        if (uri == null) {
            return;
        }
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        Project project = projects.length > 0 ? projects[0] : null;
        VirtualFile file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor(), project, null);
        if (file == null) {
            return;
        }
        HintsService.addManualImport(uri, file.getUrl(), file.getName(), file.getPath());
    }

    /**
     * Rebuilds the rows when the current file or its imports changed; a
     * no-op otherwise. The single listener registration is owned by
     * HintsToolWindowFactory, like for the interpretation panel.
     */
    public void refresh() {
        SwingUtilities.invokeLater(() -> {
            String uri = HintsService.getCurrentFileUri();
            List<HintsService.ImportEntry> entries = HintsService.getImportsForUri(uri);
            // The first time the pane sees a file, scan its imports once;
            // afterwards the list only changes through the pane's Refresh
            // button and Add file, as designed. The scan is a local text
            // scan (ImportDetector), so it costs nothing and never waits.
            if (entries == null && uri != null) {
                HintsService.setImportsForUri(uri, detectImports(uri));
                entries = HintsService.getImportsForUri(uri);
            }
            if (Objects.equals(uri, renderedUri)
                    && Objects.equals(entries, renderedEntries)) {
                return;
            }
            renderedUri = uri;
            renderedEntries = entries;
            rebuildRows(uri, entries);
            rebuildRecentFiles(uri, entries);
        });
    }

    /**
     * Runs the local import scan for the file (ImportDetector.detect)
     * and returns its entries; an empty list when the uri does not
     * resolve to a readable file, so the pane treats the file as
     * scanned instead of retrying forever.
     */
    private static List<HintsService.ImportEntry> detectImports(String uri) {
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(uri);
        if (file == null || file.isDirectory()) {
            return List.of();
        }
        return ImportDetector.detect(file);
    }

    private void rebuildRows(String uri, List<HintsService.ImportEntry> entries) {
        rowsPanel.removeAll();
        fileLabel.setText(uri == null ? "No file selected" : "Imports of: " + fileNameOf(uri));

        if (uri == null) {
            statusLabel.setText(" ");
            rowsPanel.add(hint("No file selected."));
        } else if (entries.isEmpty()) {
            statusLabel.setText(" ");
            rowsPanel.add(hint("No local imports found. Use Add file… for files the scan missed."));
        } else {
            // The attachable files come first; the references that were
            // looked up but not found are moved to the bottom of the list,
            // where they are informational only.
            List<HintsService.ImportEntry> found = new ArrayList<>();
            List<HintsService.ImportEntry> notFound = new ArrayList<>();
            for (HintsService.ImportEntry entry : entries) {
                if (entry.uri == null) {
                    notFound.add(entry);
                } else {
                    found.add(entry);
                }
            }
            int attached = 0;
            for (HintsService.ImportEntry entry : found) {
                if (entry.checked) {
                    attached++;
                }
            }
            String status = attached + " of " + found.size() + " file(s) attached to the analysis";
            if (!notFound.isEmpty()) {
                status += "; " + notFound.size() + " reference(s) not found";
            }
            statusLabel.setText(status);
            for (HintsService.ImportEntry entry : found) {
                rowsPanel.add(buildRow(uri, entry));
            }
            for (HintsService.ImportEntry entry : notFound) {
                rowsPanel.add(buildRow(uri, entry));
            }
        }

        revalidate();
        repaint();
    }

    /**
     * Rebuilds the quick-add strip from the recently opened files: one
     * button per file that is neither the current file nor already
     * attached (by URI - a file the scan found and a file added manually
     * are the same attachment), skipping files that no longer resolve.
     * The strip is hidden while empty, e.g. on a fresh session before
     * any file switch.
     */
    private void rebuildRecentFiles(String uri, List<HintsService.ImportEntry> entries) {
        recentFilesPanel.removeAll();
        if (uri != null) {
            Set<String> attached = new HashSet<>();
            for (HintsService.ImportEntry entry : entries) {
                if (entry.uri != null) {
                    attached.add(entry.uri);
                }
            }
            int shown = 0;
            for (String recentUri : HintsService.getRecentFileUris()) {
                if (shown >= MAX_QUICK_ADDS) {
                    break;
                }
                if (recentUri.equals(uri) || attached.contains(recentUri)) {
                    continue;
                }
                VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(recentUri);
                if (file == null || file.isDirectory()) {
                    continue;
                }
                if (recentFilesPanel.getComponentCount() == 0) {
                    recentFilesPanel.add(hint("Recently opened:"));
                }
                recentFilesPanel.add(buildQuickAddButton(uri, file));
                shown++;
            }
        }
        recentFilesPanel.setVisible(recentFilesPanel.getComponentCount() > 0);
        recentFilesPanel.revalidate();
        recentFilesPanel.repaint();
    }

    /**
     * One quick-add button: attaches a recently opened file to the
     * current file's analysis as a manually added, checked entry (see
     * HintsService.addManualImport), so it is context from the next
     * analysis on like every attachment. The entry appears in the list
     * above with a Remove button, like every manually added file.
     */
    private static JButton buildQuickAddButton(String uri, VirtualFile file) {
        JButton button = new JButton(file.getName());
        button.setToolTipText(
                "Attach " + file.getName() + " to the analysis of the current file, as context from the next analysis on.");
        button.addActionListener(e ->
                HintsService.addManualImport(uri, file.getUrl(), file.getName(), file.getPath()));
        return button;
    }

    private JPanel buildRow(String uri, HintsService.ImportEntry entry) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (entry.uri == null) {
            // The reference was looked up but no project file matches it,
            // so there is nothing to attach: no checkbox, the specifier as
            // grey text, and "not found" in place of the path. A checkbox
            // here would suggest that checking it attaches something.
            JLabel specifierLabel = new JLabel(entry.specifier);
            specifierLabel.setForeground(JBColor.GRAY);
            specifierLabel.setToolTipText(
                    "This reference was looked up but does not resolve to a file in the project, so there is nothing to attach. Use Add file… to attach the right file manually.");
            row.add(specifierLabel, BorderLayout.WEST);

            JLabel pathLabel = new JLabel("not found");
            pathLabel.setForeground(JBColor.GRAY);
            row.add(pathLabel, BorderLayout.CENTER);
            capHeight(row);
            return row;
        }

        JCheckBox checkBox = new JCheckBox(entry.specifier);
        checkBox.setSelected(entry.checked);
        checkBox.setToolTipText("Checked files are attached to the analysis as context; the attachment applies from the next analysis on.");
        checkBox.addActionListener(e -> HintsService.setImportChecked(uri, entry.id(), checkBox.isSelected()));
        row.add(checkBox, BorderLayout.WEST);

        JLabel pathLabel = new JLabel(entry.resolvedPath);
        pathLabel.setForeground(JBColor.GRAY);
        row.add(pathLabel, BorderLayout.CENTER);

        if (entry.manual) {
            JButton removeButton = new JButton("Remove");
            removeButton.setToolTipText("Remove this manually added file.");
            removeButton.addActionListener(e -> HintsService.removeImport(uri, entry.id()));
            row.add(removeButton, BorderLayout.EAST);
        }
        capHeight(row);
        return row;
    }

    private static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(JBColor.GRAY);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        capHeight(label);
        return label;
    }

    /**
     * Caps a component's maximum height at its preferred height,
     * leaving the width unbounded. rowsPanel lays its rows out with a
     * vertical BoxLayout, which grows components up to their maximum
     * sizes - and a JPanel's or JLabel's default maximum is unbounded,
     * so the few rows of a short list were stretched to share the
     * pane's whole height: three rows ended up evenly spread from top
     * to bottom, as if the spacing between them grew with the empty
     * space. Capped, the rows keep their natural height and stack from
     * the top with fixed spacing, the space below the last row stays
     * empty, and the scroll pane's scrollbar appears once the rows'
     * natural heights exceed the visible height.
     */
    private static void capHeight(JComponent component) {
        component.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
    }

    private static String fileNameOf(String uri) {
        int slash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'));
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }
}
