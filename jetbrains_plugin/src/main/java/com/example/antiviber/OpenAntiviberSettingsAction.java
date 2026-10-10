package com.example.antiviber;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toolbar action that opens the Antiviber settings page.
 * This also makes the tool window show the options (cogwheel) menu.
 */
public class OpenAntiviberSettingsAction extends AnAction {

    public OpenAntiviberSettingsAction() {
        // The platform's standard gear icon, so the settings button is
        // recognizable at a glance and visually distinct from the reload
        // action's refresh icon; the previous null icon made the title bar
        // render both toolbar actions the same way.
        super("Antiviber Settings", "Open Antiviber LSP Server settings", AllIcons.General.GearPlain);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        openSettings();
    }

    public static void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog((Project) null, AntiviberSettingsConfigurable.class);
    }
}
