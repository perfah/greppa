package com.example.antiviber;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class HintsToolWindowFactory implements ToolWindowFactory {

    private HintsFilePoller poller;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        HintsPanel panel = new HintsPanel();
        Content content = ContentFactory.getInstance().createContent(panel, "Interpretation", false);
        ImportsPanel importsPanel = new ImportsPanel();
        Content importsContent = ContentFactory.getInstance().createContent(importsPanel, "Imports", false);
        toolWindow.getContentManager().addContent(content);
        toolWindow.getContentManager().addContent(importsContent);

        // The Imports action sits between Reload and Settings, so the
        // button that opens the Imports pane is directly left of the
        // settings gear.
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
        Runnable refreshListener = panel::refresh;
        HintsService.addListener(refreshListener);
        Runnable importsRefreshListener = importsPanel::refresh;
        HintsService.addListener(importsRefreshListener);

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
                HintsService.removeListener(refreshListener);
                HintsService.removeListener(importsRefreshListener);
            }
        });
    }
}
