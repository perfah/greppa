package com.example.antiviber;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

public class AntiviberLspServerSupportProvider implements LspServerSupportProvider {

    private static final Logger LOG = Logger.getInstance(AntiviberLspServerSupportProvider.class);

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        LOG.info("AntiviberLspServerSupportProvider.fileOpened called for " + file.getPath());
        if (isSupportedFile(file)) {
            LOG.info("File opened: " + file.getPath());
            starter.ensureServerStarted(new AntiviberLspServerDescriptor(project));
        } else {
            LOG.info("File not supported by Antiviber: " + file.getPath());
        }
    }

    private boolean isSupportedFile(@NotNull VirtualFile file) {
        // Activate for all editable, non-directory files.
        return !file.isDirectory() && !file.getFileType().isReadOnly();
    }
}
