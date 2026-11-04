package com.perfah.greppa

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.Endpoint

/**
 * Parameters for the custom `custom/didSwitchFile` notification.
 * Must match the Rust server's expected JSON shape:
 * { "textDocument": { "uri": "..." } }
 */
data class DidSwitchFileParams(val textDocument: TextDocumentIdentifier)

/**
 * Sends a custom notification to the Greppa LSP server whenever the user
 * switches to a different editor tab. Standard LSP does not have a
 * didSwitchFile notification, so we use a custom endpoint.
 */
class LspTabSelectionListener : FileEditorManagerListener {

    private val LOG = Logger.getInstance(LspTabSelectionListener::class.java)

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newFile: VirtualFile = event.newFile ?: return

        LOG.info("LspTabSelectionListener: selection changed to ${newFile.url}")

        // The Greppa server runs under IntelliJ's own LSP API
        // (com.intellij.platform.lsp.api, registered via the
        // platform.lsp.serverSupportProvider extension point in plugin.xml).
        // It is NOT registered with the third-party lsp4intellij library, so
        // lsp4intellij's LspServerManager never knows about it and always
        // returned an empty list. Use the running servers tracked by the
        // server lifecycle listener instead.
        val lspServers = GreppaLspServerListener.getRunningServers()

        if (lspServers.isEmpty()) {
            LOG.warn("LspTabSelectionListener: no running Greppa LSP servers found")
            return
        }

        val fileUri = newFile.url
        val params = DidSwitchFileParams(TextDocumentIdentifier(fileUri))

        for (server in lspServers) {
            if (!server.descriptor.isSupportedFile(newFile)) {
                LOG.info("LspTabSelectionListener: skipping server for unsupported file $fileUri")
                continue
            }

            LOG.info("LspTabSelectionListener: sending custom/didSwitchFile for $fileUri")
            try {
                server.sendNotification { lsp4jServer ->
                    // The lsp4j server proxy also implements Endpoint, which is
                    // the only way to send a custom (non-standard) notification.
                    (lsp4jServer as Endpoint).notify("custom/didSwitchFile", params)
                }
            } catch (e: Exception) {
                LOG.error("LspTabSelectionListener: failed to send custom/didSwitchFile", e)
            }
        }
    }
}
