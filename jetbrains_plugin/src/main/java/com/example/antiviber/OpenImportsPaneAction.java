package com.example.antiviber;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

/**
 * Toolbar action that shows the Improve tab of the Antiviber
 * Interpretation tool window, whose top part manages the files attached
 * to the analysis: the imports of the current file the local scan found,
 * the files the user added manually, and which of them are attached.
 * Placed between the Reload and the Settings actions in the tool
 * window's title bar (see HintsToolWindowFactory), so the button sits
 * directly left of the settings gear.
 */
public class OpenImportsPaneAction extends AnAction {

    public OpenImportsPaneAction() {
        // A folder icon, so the button reads as "a list of files" at a
        // glance and stays distinct from the reload action's refresh
        // icon and the settings action's gear icon.
        super("Imports", "Show the Improve tab, which manages the files attached to the analysis above the problems and recommendations", AllIcons.Nodes.Folder);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Antiviber Interpretation");
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(null);
        ContentManager contentManager = toolWindow.getContentManager();
        for (Content content : contentManager.getContents()) {
            if ("Improve".equals(content.getDisplayName())) {
                contentManager.setSelectedContent(content);
                return;
            }
        }
    }
}
