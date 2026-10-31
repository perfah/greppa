package com.example.greppa;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toolbar action that opens the Greppa settings page.
 * This also makes the tool window show the options (cogwheel) menu.
 */
public class OpenGreppaSettingsAction extends AnAction {

    public OpenGreppaSettingsAction() {
        // The platform's standard gear icon, so the settings button is
        // recognizable at a glance and visually distinct from the reload
        // action's refresh icon; the previous null icon made the title bar
        // render both toolbar actions the same way.
        super("Greppa Settings", "Open Greppa LSP Server settings", AllIcons.General.GearPlain);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        openSettings();
    }

    public static void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog((Project) null, GreppaSettingsConfigurable.class);
    }
}
