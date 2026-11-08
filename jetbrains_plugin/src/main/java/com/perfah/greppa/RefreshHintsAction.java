package com.perfah.greppa;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Toolbar action that forces the LSP server's analysis files to be re-read immediately.
 */
public class RefreshHintsAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(RefreshHintsAction.class);

    public RefreshHintsAction() {
        // The platform's standard refresh icon, so the reload button is
        // recognizable at a glance and visually distinct from the settings
        // action's gear icon; a null icon left the button without a
        // recognizable symbol.
        super("Reload", "Re-generate the current file's analysis", AllIcons.Actions.Refresh);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        LOG.info("Reloading Greppa analysis");
        HintsFilePoller.pollNow();
    }
}
