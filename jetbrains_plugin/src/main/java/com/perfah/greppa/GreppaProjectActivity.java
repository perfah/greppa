package com.perfah.greppa;

import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
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

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

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
 * - A dispatcher on the IDE's event queue (IdeEventQueue) watches the
 *   Ctrl+S / Cmd+S keystroke and covers the case the save listener
 *   cannot see: the platform fires beforeDocumentSaving only for
 *   documents with unsaved changes, so pressing Ctrl+S on an unchanged
 *   document produced no event at all and no new analysis - even
 *   though the caret may have moved, which is meaningful input for the
 *   analysis (the Cursor section and the Parts section's
 *   current-segment marker are computed from it). The dispatcher sees
 *   the keystroke before the action system processes it; when the
 *   current file's document has no unsaved changes, so the save
 *   listener will not fire for it, the trigger is written for the
 *   current file with its caret. A save of changed content is left to
 *   the save listener, so one Ctrl+S still produces one trigger.
 *
 * The keystroke is watched instead of the save actions themselves
 * because this IDE build exposes no action listener to plugins: the
 * com.intellij.openapi.actionSystem.ex.ActionListener that current
 * platform documentation names does not exist in this build (the CI
 * compile failed on the import), and this build has already removed
 * or hidden other long-standing action-system APIs, so betting on
 * the older AnActionListener instead is no safer. IdeEventQueue's
 * dispatcher mechanism has been stable across many platform versions.
 * The trade-offs of watching the keystroke rather than the action:
 * the dispatcher reacts to the key combination, not to whatever
 * action the keymap binds it to, so a save shortcut remapped to
 * another key is not covered, and keystrokes that go to a dialog are
 * ignored because dialogs handle their own shortcuts. Key
 * auto-repeat (holding Ctrl+S) is throttled by a cooldown, so a held
 * key does not restart the analysis in a loop.
 *
 * The trigger file is used because the custom LSP notifications
 * cannot be sent (the IDE's running-server registry is never
 * populated in this build); see HintsFilePoller.writeTriggerFile.
 *
 * FileDocumentManagerListener is an application-level topic, so the
 * subscription goes to the application message bus; the connection is
 * bound to the project, so it is disposed when the project closes and
 * reopening a project does not stack duplicate subscriptions. The
 * keystroke dispatcher, in contrast, is registered once for the whole
 * application (see registerSaveKeystrokeDispatcher): the IDE event
 * queue is application-global, so a per-project registration would
 * run the keystroke handling once per open project for every Ctrl+S.
 *
 * Java port of the former GreppaProjectActivity.kt: the Maven build
 * compiles only src/main/java (the pom configures no Kotlin
 * compiler), so the Kotlin original never reached the plugin jar.
 * ProjectActivity is a Kotlin suspend interface, which from Java has
 * the execute(Project, Continuation) signature; the body performs no
 * suspension, so it returns Unit.INSTANCE directly.
 */
public final class GreppaProjectActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(GreppaProjectActivity.class);

    /**
     * Minimum time in milliseconds between two analyses triggered by the
     * save keystroke. Key auto-repeat fires KEY_PRESSED events for as
     * long as Ctrl+S is held, and every trigger cancels the running
     * analysis and starts a new one, so a held key would restart the
     * analysis in a loop; the cooldown lets only the first keystroke
     * through. One second also swallows an intentional double-press: an
     * analysis takes far longer than that, so pressing again that
     * quickly cannot be a deliberate request for a second one.
     */
    private static final long SAVE_KEYSTROKE_COOLDOWN_MS = 1000L;

    /**
     * Ensures the save keystroke dispatcher is registered once per
     * application: the IDE event queue is application-global, and this
     * activity runs once per open project, so without the guard two
     * projects opened side by side would register two dispatchers and
     * every Ctrl+S would run the keystroke handling twice.
     */
    private static final AtomicBoolean SAVE_KEYSTROKE_DISPATCHER_REGISTERED = new AtomicBoolean(false);

    /**
     * When the last analysis trigger of the save keystroke path was
     * written, in milliseconds since the epoch (see
     * SAVE_KEYSTROKE_COOLDOWN_MS). Written and read only on the event
     * dispatch thread, where the IDE event queue delivers its events.
     */
    private static volatile long lastSaveKeystrokeTriggerMs = 0L;

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
        registerSaveKeystrokeDispatcher();
        return Unit.INSTANCE;
    }

    /**
     * Registers the save keystroke dispatcher on the IDE's event queue,
     * once per application (see SAVE_KEYSTROKE_DISPATCHER_REGISTERED).
     * The dispatcher observes every AWT event before the action system
     * processes it and never consumes anything (it always returns
     * false); a registration failure is contained - without the
     * dispatcher only the save listener path remains, so saving changed
     * files still generates analyses.
     */
    private static void registerSaveKeystrokeDispatcher() {
        if (!SAVE_KEYSTROKE_DISPATCHER_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            // The dispatcher is passed as a lambda, so its functional
            // interface's method name never has to be named here, and the
            // null parent means the dispatcher lives for the whole
            // application, matching the once-per-application guard. The
            // null is cast to Disposable because the IntelliJ IDEA build
            // of the platform adds a second addDispatcher overload taking
            // a CoroutineScope, and an untyped null makes the call
            // ambiguous (the compiler reports both
            // addDispatcher(NonLockedEventDispatcher, Disposable) and
            // addDispatcher(NonLockedEventDispatcher, CoroutineScope) as
            // matching); the cast selects the Disposable overload, which
            // exists in every targeted IDE build.
            IdeEventQueue.getInstance().addDispatcher(event -> {
                handleSaveKeystroke(event);
                return false;
            }, (Disposable) null);
            LOG.info("Registered the Greppa save keystroke dispatcher on the IDE event queue");
        } catch (Exception e) {
            LOG.warn("Failed to register the save keystroke dispatcher", e);
        }
    }

    /**
     * Handles one event observed by the save keystroke dispatcher: when
     * it is the plain Ctrl+S / Cmd+S keystroke pressed outside a dialog,
     * the analysis trigger is written for the current file with its
     * caret - unless the current document has unsaved changes, in which
     * case the dispatcher does nothing and the save listener fires for
     * the save instead, so one Ctrl+S produces one trigger either way.
     * Runs on the event dispatch thread, where the IDE event queue
     * delivers events, so the FileEditorManager and FileDocumentManager
     * queries are allowed.
     */
    private static void handleSaveKeystroke(AWTEvent event) {
        // The dispatcher sees every AWT event (mouse moves, paints, key
        // events alike), so the cheap type check comes first.
        if (!(event instanceof KeyEvent)) {
            return;
        }
        if (!GreppaSettingsState.getInstance().refreshOnSave) {
            return;
        }
        KeyEvent keyEvent = (KeyEvent) event;
        // Ctrl+S on Windows and Linux, Cmd+S on macOS. The Alt and Shift
        // combinations belong to other actions (Ctrl+Alt+S opens the
        // Settings dialog), so only the plain modifier combination counts.
        if (keyEvent.getID() != KeyEvent.KEY_PRESSED
                || keyEvent.getKeyCode() != KeyEvent.VK_S
                || !(keyEvent.isControlDown() || keyEvent.isMetaDown())
                || keyEvent.isAltDown()
                || keyEvent.isShiftDown()) {
            return;
        }
        // Keystrokes that go to a dialog are ignored: a dialog handles its
        // own shortcuts, and Ctrl+S there does not save the current file.
        // The IDE's main window, and the tool windows inside it, is a
        // frame rather than a dialog.
        Component source = keyEvent.getComponent();
        if (source == null) {
            return;
        }
        if (SwingUtilities.getWindowAncestor(source) instanceof Dialog) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveKeystrokeTriggerMs < SAVE_KEYSTROKE_COOLDOWN_MS) {
            return;
        }
        lastSaveKeystrokeTriggerMs = now;
        analyzeUnchangedCurrentFile();
    }

    /**
     * Writes the analysis trigger for the currently selected file when a
     * save keystroke runs but the save listener will not fire for it:
     * the platform only fires beforeDocumentSaving for documents with
     * unsaved changes, so an unchanged file - e.g. after only the caret
     * moved - would otherwise see no new analysis on Ctrl+S. The caret
     * is part of the trigger, so the new analysis describes the
     * position the user is actually at.
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
