package com.perfah.greppa;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

public class GreppaLspServerSupportProvider implements LspServerSupportProvider {

    private static final Logger LOG = Logger.getInstance(GreppaLspServerSupportProvider.class);

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        LOG.info("GreppaLspServerSupportProvider.fileOpened called for " + file.getPath());
        if (isSupportedFile(file)) {
            LOG.info("File opened: " + file.getPath());
            starter.ensureServerStarted(new GreppaLspServerDescriptor(project));
        } else {
            LOG.info("File not supported by Greppa: " + file.getPath());
        }
    }

    private boolean isSupportedFile(@NotNull VirtualFile file) {
        // Activate for all editable, non-directory files.
        return !file.isDirectory() && !file.getFileType().isReadOnly();
    }
}
