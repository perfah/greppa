package com.example.greppa;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level listener that sets up the per-project listeners for
 * every opened project.
 *
 * This class was previously a Kotlin source, but the Maven build only
 * compiles .java files (pom.xml configures no Kotlin compiler), so the
 * Kotlin version was never part of the plugin jar and its registrations
 * never ran - which is why the tool window did not refresh on tab
 * switches. It is now a plain Java class so that javac builds it.
 */
public class GreppaProjectManagerListener implements ProjectManagerListener {

    private static final Logger LOG = Logger.getInstance(GreppaProjectManagerListener.class);

    @Override
    public void projectOpened(@NotNull Project project) {
        LOG.info("GreppaProjectManagerListener: project opened, registering listeners for " + project.getName());
        // Refresh the tool window when the selected editor changes, so
        // switching tabs shows the analysis of the newly selected file.
        HintsService.initForProject(project);
        // Ask the LSP server to re-analyze the newly selected file on tab
        // switches via the custom/didSwitchFile notification.
        project.getMessageBus().connect().subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new LspTabSelectionListener()
        );
    }

    @Override
    public void projectClosed(@NotNull Project project) {
        LOG.info("GreppaProjectManagerListener: project closed for " + project.getName());
    }
}
