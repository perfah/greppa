package com.perfah.greppa;

import com.intellij.openapi.actionSystem.ex.ActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.messages.MessageBusConnection;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Generates a new analysis when the user saves, while the save-refresh
 * setting is on (see GreppaSettingsState.refreshOnSave; the setting is
 * read when the events fire, so toggling it applies immediately).
 *
 * Two paths write the same tick trigger file the Analyze button uses
 * (HintsFilePoller.writeTriggerFile with force = true), so the Rust
 * LSP server picks it up within 250 ms, cancels the analysis
 * currently streaming and runs a forced tick; the finished analysis
 * is added to the file's history like every other (see
 * HintsService.completeMarkdownForUri):
 *
 * - FileDocumentManagerListener.beforeDocumentSaving fires for every
 *   document that actually gets saved (Ctrl+S, Save All and the IDE's
 *   autosaves alike - the platform does not distinguish them). This is
 *   the path for changed content.
 *
 * - An ActionListener on the platform's save actions covers the case the
 *   save listener cannot see: the platform fires beforeDocumentSaving
 *   only for documents with unsaved changes, so pressing Ctrl+S on an
 *   unchanged document produced no event at all and no new analysis -
 *   even though the caret may have moved, which is meaningful input for
 *   the analysis (the Cursor section and the Parts section's
 *   current-segment marker are computed from it). When a save action
 *   runs and the current file's document has no unsaved changes, the
 *   trigger is written for the current file with its caret; a save of
 *   changed content is left to the save listener, so one Ctrl+S still
 *   produces one trigger.
 *
 * The trigger file is used because the custom LSP notifications cannot be
 * sent (the IDE's running-server registry is never populated in this
 * build); see HintsFilePoller.writeTriggerFile.
 *
 * FileDocumentManagerListener is an application-level topic, so the
 * subscriptions go to the application message bus; the connection is
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

    /**
     * The action ids of the platform's save actions. Which of them
     * Ctrl+S is bound to depends on the keymap (the default keymap uses
     * SaveAll), so the action listener recognizes all of them.
     */
    private static final Set<String> SAVE_ACTION_IDS =
            Set.of("SaveAll", "SaveDocument", "SaveProject");

    @Nullable
    @Override
    public Object execute(@NotNull Project project,
                           @NotNull Continuation<? super Unit> continuation) {
        MessageBusConnection connection =
                ApplicationManager.getApplication().getMessageBus().connect(project);
        connection.subscribe(
                FileDocumentManagerListener.TOPIC,
                new FileDocumentManagerListener() {
                    @Override
                    public void beforeDocumentSaving(@NotNull Document document) {
                        if (!GreppaSettingsState.getInstance().refreshOnSave) {
                            return;
                        }
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
        connection.subscribe(
                ActionListener.TOPIC,
                new ActionListener() {
                    @Override
                    public void beforeActionPerformed(ActionEvent event) {
                        if (!GreppaSettingsState.getInstance().refreshOnSave) {
                            return;
                        }
                        if (!SAVE_ACTION_IDS.contains(event.getActionId())) {
                            return;
                        }
                        analyzeUnchangedCurrentFile();
                    }
                }
        );
        return Unit.INSTANCE;
    }

    /**
     * Writes the analysis trigger for the currently selected file when a
     * save action runs but the save listener will not fire for it: the
     * platform only fires beforeDocumentSaving for documents with
     * unsaved changes, so an unchanged file - e.g. after only the caret
     * moved - would otherwise see no new analysis on Ctrl+S. The caret is
     * part of the trigger, so the new analysis describes the position
     * the user is actually at.
     */
    private static void analyzeUnchangedCurrentFile() {
        String currentUri = HintsService.getCurrentFileUri();
        if (currentUri == null) {
            return;
        }
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(currentUri);
        if (file == null) {
            return;
        }
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return;
        }
        // Changed content is left to the save listener, which fires for
        // the document as it is saved; writing the trigger here as well
        // would only duplicate it.
        for (Document unsaved : FileDocumentManager.getInstance().getUnsavedDocuments()) {
            if (unsaved == document) {
                return;
            }
        }
        HintsFilePoller.writeTriggerFile(
                currentUri,
                HintsService.getCurrentCaretPosition(),
                HintsService.getTopic(),
                true
        );
    }
}
