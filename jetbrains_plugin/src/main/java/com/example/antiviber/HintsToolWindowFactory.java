package com.example.antiviber;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.List;

public class HintsToolWindowFactory implements ToolWindowFactory {

    private HintsFilePoller poller;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // The three tabs render the same analysis document, each through
        // its own section filter (see HintsService.filterSections):
        // Interpret shows the understanding sections, Improve the
        // problems and recommendations below the attached-files
        // management, and Relate the relationships to other files.
        HintsPanel interpretPanel = new HintsPanel(
                List.of("Scope", "General understanding", "Current functionality"));
        Content interpretContent = ContentFactory.getInstance()
                .createContent(interpretPanel, "Interpret", false);

        ImportsPanel importsPanel = new ImportsPanel();
        HintsPanel improvePanel = new HintsPanel(List.of("Scope", "Problems", "Recommendations"));
        Content improveContent = ContentFactory.getInstance()
                .createContent(buildImproveTab(importsPanel, improvePanel), "Improve", false);

        HintsPanel relatePanel = new HintsPanel(List.of("Scope", "Relationships to other files"));
        Content relateContent = ContentFactory.getInstance()
                .createContent(relatePanel, "Relate", false);

        toolWindow.getContentManager().addContent(interpretContent);
        toolWindow.getContentManager().addContent(improveContent);
        toolWindow.getContentManager().addContent(relateContent);

        // The Imports action sits between Reload and Settings, so the
        // button that opens the Improve tab (whose top manages the files
        // attached to the analysis) is directly left of the settings
        // gear.
        List<AnAction> titleActions = Arrays.asList(
                new RefreshHintsAction(),
                new OpenImportsPaneAction(),
                new OpenAntiviberSettingsAction()
        );
        toolWindow.setTitleActions(titleActions);

        // Ensure the panels refresh whenever hints are updated or the active file changes.
        // These are the single listener registrations for the panels, and the
        // references are stored so contentRemoved below removes these exact
        // instances: every panel::refresh evaluation creates a new lambda,
        // so calling removeListener(panel::refresh) there (as before)
        // removed nothing and the panels kept receiving notifications
        // forever after their content was removed. The panels do not
        // register themselves either, so each update refreshes them exactly once.
        List<Runnable> refreshListeners = List.of(
                interpretPanel::refresh,
                importsPanel::refresh,
                improvePanel::refresh,
                relatePanel::refresh);
        for (Runnable listener : refreshListeners) {
            HintsService.addListener(listener);
        }

        // Register the per-project listeners here instead of from
        // AntiviberProjectManagerListener. That class hooked
        // ProjectManagerListener.projectOpened, which this IDE build no
        // longer calls (the compiler already warns it is deprecated and
        // marked for removal), so its registrations never ran: switching
        // editor tabs sent no custom/didSwitchFile notification, and the
        // panel did not refresh on tab switches either - only closing and
        // reopening a file did anything, because didOpen is driven by the
        // platform directly. createToolWindowContent runs exactly once per
        // project, which makes it the per-project initialization point:
        // initForProject refreshes the panel when the selected editor
        // changes, and LspTabSelectionListener asks the LSP server to
        // re-analyze the newly selected file.
        HintsService.initForProject(project);
        project.getMessageBus().connect().subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new LspTabSelectionListener()
        );

        poller = new HintsFilePoller();
        poller.start();

        toolWindow.getContentManager().addContentManagerListener(new com.intellij.ui.content.ContentManagerListener() {
            @Override
            public void contentRemoved(com.intellij.ui.content.ContentManagerEvent event) {
                if (poller != null) {
                    poller.stop();
                }
                for (Runnable listener : refreshListeners) {
                    HintsService.removeListener(listener);
                }
            }
        });
    }

    /**
     * Builds the Improve tab: the attached-files management (the former
     * Imports pane) as a strip at the top, the problems and
     * recommendations markdown below it. The strip's height is capped so
     * the markdown keeps the bulk of the tab; the imports list scrolls
     * within the cap.
     */
    private static JComponent buildImproveTab(ImportsPanel importsPanel, HintsPanel improvePanel) {
        importsPanel.setPreferredSize(new Dimension(0, 220));
        JPanel tab = new JPanel(new BorderLayout(0, 0));
        tab.add(importsPanel, BorderLayout.NORTH);
        tab.add(improvePanel, BorderLayout.CENTER);
        return tab;
    }
}
