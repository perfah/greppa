package com.perfah.greppa;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.VirtualFile;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 *
 * Java port of the former GreppaProjectActivity.kt: the Maven build
 * compiles only src/main/java (the pom configures no Kotlin compiler),
 * so the Kotlin original never reached the plugin jar. ProjectActivity
 * is a Kotlin suspend interface, which from Java has the
 * execute(Project, Continuation) signature; the body performs no
 * suspension, so it returns Unit.INSTANCE directly.
 */
public final class GreppaProjectActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project,
                           @NotNull Continuation<? super Unit> continuation) {
        ApplicationManager.getApplication().getMessageBus().connect(project).subscribe(
                FileDocumentManagerListener.TOPIC,
                new FileDocumentManagerListener() {
                    @Override
                    public void beforeDocumentSaving(@NotNull Document document) {
                        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                        if (file == null) {
                            return;
                        }
                        // The caret is only known for the file open in the
                        // selected editor; a background file saved by Save All
                        // gets no position, so the server analyzes it without
                        // marking a current segment instead of attributing the
                        // selected file's caret to it.
                        String currentUri = HintsService.getCurrentFileUri();
                        Position caret = currentUri != null && currentUri.equals(file.getUrl())
                                ? HintsService.getCurrentCaretPosition()
                                : null;
                        HintsFilePoller.writeTriggerFile(
                                file.getUrl(),
                                caret,
                                HintsService.getTopic(),
                                true
                        );
                    }
                }
        );
        return Unit.INSTANCE;
    }
}
