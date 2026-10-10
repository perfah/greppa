package com.example.antiviber;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

/**
 * Sends a trigger to the Antiviber LSP server whenever the user switches
 * to a different editor tab, so the server re-analyzes the newly
 * selected file.
 *
 * The trigger is a small file written to the hints directory (see
 * HintsFilePoller.writeTriggerFile), which the Rust server polls for.
 * The previous approach - sending a custom custom/didSwitchFile LSP
 * notification through the running-server registry - never worked because
 * the platform does not call
 * LspServerDescriptor.createLspServerListener in this IDE build, so the
 * registry stayed empty and no notification could be sent. The trigger
 * file bypasses the LSP connection entirely.
 *
 * The trigger carries the caret position in the newly selected file, so
 * the analysis can mark the segment containing the cursor with
 * "(current)", and the current topic/goal from HintsService, so the
 * analysis of the newly selected file is viewed through it.
 */
public class LspTabSelectionListener implements FileEditorManagerListener {

    private static final Logger LOG = Logger.getInstance(LspTabSelectionListener.class);

    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        VirtualFile newFile = event.getNewFile();
        if (newFile == null) {
            return;
        }

        LOG.info("LspTabSelectionListener: selection changed to " + newFile.getUrl());

        String fileUri = newFile.getUrl();
        // Caret position of the newly selected editor, or null when the
        // selected file has no text editor. selectionChanged runs on the
        // EDT, so the FileEditorManager query is allowed.
        Position caretPosition = HintsService.getCurrentCaretPosition();
        HintsFilePoller.writeTriggerFile(fileUri, caretPosition, HintsService.getTopic(), false);
    }
}
