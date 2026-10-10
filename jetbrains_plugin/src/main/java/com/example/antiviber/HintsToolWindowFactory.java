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
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);

        List<AnAction> titleActions = Arrays.asList(
                new RefreshHintsAction(),
                new OpenAntiviberSettingsAction()
        );
        toolWindow.setTitleActions(titleActions);

        // Ensure the panel refreshes whenever hints are updated or the active file changes.
        // This is the single listener registration for the panel, and the
        // reference is stored so contentRemoved below removes this exact
        // instance: every panel::refresh evaluation creates a new lambda,
        // so calling removeListener(panel::refresh) there (as before)
        // removed nothing and the panel kept receiving notifications
        // forever after its content was removed. The panel does not
        // register itself either, so each update refreshes it exactly once.
        Runnable refreshListener = panel::refresh;
        HintsService.addListener(refreshListener);

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
            }
        });
    }
}
