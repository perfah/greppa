package com.example.greppa

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Refreshes the analysis when a document is saved. Subscribes a
 * FileDocumentManagerListener once per project and, on every save
 * (Ctrl+S, Save All and the IDE's autosaves alike - the platform does
 * not distinguish them), writes the same tick trigger file the reload
 * button uses (HintsFilePoller.writeTriggerFile with force = true), so
 * the Rust LSP server picks it up within 250 ms, cancels the analysis
 * currently streaming and runs a forced tick for the saved file. The
 * trigger file is used because the custom LSP notifications cannot be
 * sent (the IDE's running-server registry is never populated in this
 * build); see HintsFilePoller.writeTriggerFile.
 *
 * FileDocumentManagerListener is an application-level topic, so the
 * subscription goes to the application message bus; the connection is
 * bound to the project, so it is disposed when the project closes and
 * reopening a project does not stack duplicate subscriptions.
 */
class GreppaProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val file = FileDocumentManager.getInstance().getFile(document) ?: return
                    // The caret is only known for the file open in the
                    // selected editor; a background file saved by Save All
                    // gets no position, so the server analyzes it without
                    // marking a current segment instead of attributing the
                    // selected file's caret to it.
                    val caret =
                        if (HintsService.getCurrentFileUri() == file.url) {
                            HintsService.getCurrentCaretPosition()
                        } else {
                            null
                        }
                    HintsFilePoller.writeTriggerFile(
                        file.url,
                        caret,
                        HintsService.getTopic(),
                        true
                    )
                }
            }
        )
    }
}
