package com.perfah.greppa;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls the LSP server's Markdown analysis files and updates the tool window.
 * The Rust server writes one file per analyzed file to the project's hints
 * directory (see hintsDirFor): <tmp>/greppa/<project>, or
 * $GREPPA_HINTS_DIR/greppa/<project> when the IDE was started with the
 * environment variable set. The first line of each file is a hidden marker
 * that names the analyzed file's URI; the rest of the file is the Markdown
 * document.
 *
 * The hints directory is per-project: every project's poller watches its
 * own directory, and the project's LSP server writes to the directory the
 * server descriptor passed it as GREPPA_HINTS_DIR (see
 * GreppaLspServerDescriptor.createCommandLine), so with two projects open
 * side by side neither project consumes the other's trigger files or
 * analyses. Before the directory was per-project, all projects shared one
 * directory and every project's server polled the same tick.trigger and
 * pause.trigger - a Pause click was consumed by whichever server polled
 * first and could pause the other project's server.
 *
 * A file is only re-read when its last-modified timestamp changes, so the UI
 * refreshes only when the server actually wrote a new analysis (file open,
 * file switch or edit) and stays quiet while nothing happened. While an
 * analysis streams in, the server rewrites the file every few hundred
 * milliseconds; every rewrite is forwarded to HintsService, which merges
 * the partial document with the last complete analysis of the same file,
 * so the panel updates section by section instead of showing the document
 * typing itself out. When a file has stayed unchanged for a few polls, the
 * stream is over and the last read content is committed to HintsService as
 * the complete document. The poll interval is short (250 ms) so the section
 * updates and the completion show up promptly.
 *
 * Besides the analysis files, the poller reads the error file
 * (error.trigger) the Rust LSP server writes when the LLM service fails, and
 * forwards the message to HintsService so the tool window shows it in its
 * status line; see readErrorFile.
 *
 * The Analyze button calls pollNow(), which additionally asks the server to
 * generate a new analysis of the currently selected file by writing a
 * trigger file (see writeTriggerFile): re-reading the disk files alone would
 * only show the previous analysis, because the server skips ticks for
 * unchanged files.
 */
public class HintsFilePoller implements Runnable {

    private static final Logger LOG = Logger.getInstance(HintsFilePoller.class);
    private static final String URI_MARKER_PREFIX = "<!-- greppa-uri: ";
    private static final String URI_MARKER_SUFFIX = " -->";
    /** Name of the error file the Rust server writes when the LLM service fails. */
    private static final String ERROR_FILE_NAME = "error.trigger";

    /**
     * Number of consecutive polls an analysis file must stay unchanged
     * before its last read content is committed as the complete document.
     * The server rewrites the file at most once per 250 ms while an
     * analysis streams in, so four quiet polls (about one second at the
     * 250 ms poll interval) mean the stream has ended. A stream that stalls
     * or is cancelled looks the same as a finished one, so its partial
     * content is committed too; the next analysis then replaces it
     * section by section, which makes the glitch temporary.
     */
    private static final int QUIET_POLLS_TO_COMPLETE = 4;

    /**
     * The running pollers by their project's hints directory, so the static
     * entry points (pollNow, writeTriggerFile, writePauseFile) can reach
     * the poller - and the directory - of one specific project. One poller
     * exists per open project (see
     * HintsToolWindowFactory.createToolWindowContent). Keyed by the
     * directory path, which is unique per project, and not by the Project
     * object, so removing a stopped poller holds no reference to a closed
     * project.
     */
    private static final Map<String, HintsFilePoller> pollersByHintsDir = new ConcurrentHashMap<>();

    private final Path hintsDir;
    private final Map<Path, Long> processedTimestamps = new HashMap<>();
    /**
     * The last content read for each analysis file whose stream may still
     * be in progress, keyed by file. An entry leaves this map when the
     * file's content is committed as complete, when the file disappears,
     * or when the next rewrite replaces it.
     */
    private final Map<Path, PendingAnalysis> pendingByPath = new HashMap<>();
    private volatile boolean running = true;

    /**
     * Creates the poller for one project's hints directory (see
     * hintsDirFor), which the poller watches for that project's analysis
     * files and error file.
     */
    public HintsFilePoller(@NotNull Project project) {
        this.hintsDir = hintsDirFor(project);
        LOG.info("HintsFilePoller watching directory: " + this.hintsDir.toAbsolutePath());
    }

    public void start() {
        pollersByHintsDir.put(hintsDir.toString(), this);
        Thread thread = new Thread(this, "Greppa analysis poller");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        pollersByHintsDir.remove(hintsDir.toString(), this);
    }

    /**
     * Entry point of the Analyze button: re-reads the analysis files
     * already on disk (picking up anything the poll loop has not
     * forwarded yet, and the only option when no LSP server is running)
     * and asks the project's LSP server to generate a new analysis of the
     * project's currently selected file. The new analysis is picked up by
     * the regular polling loop shortly after the server writes it, and its
     * finished document becomes the file's newest history entry (see
     * HintsService.completeMarkdownForUri).
     */
    public static void pollNow(@NotNull Project project) {
        HintsFilePoller poller = pollersByHintsDir.get(hintsDirFor(project).toString());
        if (poller != null) {
            poller.forcePoll();
        } else {
            LOG.warn("HintsFilePoller.pollNow called before the poller for " + project.getName() + " started");
        }
        requestForceTick(project);
    }

    /**
     * The project-less variant of pollNow: resolves the project of the
     * currently selected file and delegates to it. For callers that have
     * no project of their own, like the tool window title bar's reload
     * action (see RefreshHintsAction); the panels pass their project
     * explicitly (see pollNow(Project)).
     */
    public static void pollNow() {
        String uri = HintsService.getCurrentFileUri();
        if (uri == null) {
            LOG.warn("HintsFilePoller.pollNow called but no file is selected");
            return;
        }
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(uri);
        Project project = file != null
                ? ProjectLocator.getInstance().guessProjectForFile(file)
                : null;
        if (project == null) {
            LOG.warn("HintsFilePoller.pollNow: no project found for " + uri);
            return;
        }
        pollNow(project);
    }

    @Override
    public void run() {
        while (running) {
            try {
                // Poll fast (250 ms) so the section-by-section updates of a
                // streaming analysis and the completion of a document show
                // up in the panel promptly.
                Thread.sleep(250);
                poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warn("Unexpected error in HintsFilePoller", e);
            }
        }
    }

    /**
     * Re-reads all analysis files even if they have not changed. Used by
     * the Analyze button (via pollNow).
     */
    private synchronized void forcePoll() {
        processedTimestamps.clear();
        poll();
    }

    private synchronized void poll() {
        if (!Files.exists(hintsDir)) {
            return;
        }

        readErrorFile();

        List<Path> files = listMarkdownFiles();
        if (files.isEmpty()) {
            return;
        }

        // Forget timestamps of files that disappeared, so a recreated file is
        // treated as new again.
        processedTimestamps.keySet().retainAll(files);
        pendingByPath.keySet().retainAll(files);

        for (Path path : files) {
            try {
                long modified = lastModified(path);

                // The file has not been rewritten since the previous poll.
                // If a stream was in progress, it has ended or stalled;
                // after enough quiet polls the last read content is the
                // complete document.
                PendingAnalysis pending = pendingByPath.get(path);
                if (pending != null && pending.modifiedTime == modified) {
                    pending.quietPolls++;
                    if (pending.quietPolls >= QUIET_POLLS_TO_COMPLETE) {
                        pendingByPath.remove(path);
                        // Content that is not an analysis must not be
                        // committed as one. A blank document means the
                        // server truncated the file at the start of a
                        // stream (or a write is in progress); committing
                        // it would blank the panel. The "Generating
                        // analysis..." placeholder (see
                        // HintsService.GENERATING_PLACEHOLDER) is the
                        // loading indication the server writes at the
                        // start of every tick: a reasoning-heavy model
                        // then thinks for minutes before the first real
                        // content, so the file looks quiet while the
                        // stream is still in its reasoning phase - the
                        // quiet polls do not mean it is over. Committing
                        // the placeholder made it a completed analysis:
                        // a spurious history entry whose document is the
                        // placeholder, which also consumed the trigger
                        // timestamp the real analysis's entry should have
                        // carried (see
                        // HintsService.completeMarkdownForUri). Like the
                        // Rust side (read_analysis_for_uri), the
                        // placeholder is never an analysis; the next real
                        // write re-reads the file and completes normally.
                        if (!pending.markdown.isBlank()
                                && !HintsService.GENERATING_PLACEHOLDER.equals(pending.markdown.trim())) {
                            LOG.info("HintsFilePoller: analysis file " + path.getFileName()
                                    + " unchanged for " + QUIET_POLLS_TO_COMPLETE
                                    + " polls, completing analysis for " + pending.uri);
                            HintsService.completeMarkdownForUri(pending.uri, pending.markdown);
                        }
                    }
                    continue;
                }

                Long processed = processedTimestamps.get(path);
                if (processed != null && processed == modified) {
                    // Unchanged since the last poll: skip it so the UI is not
                    // refreshed while nothing happened.
                    continue;
                }
                processedTimestamps.put(path, modified);

                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                String uri = extractUriMarker(content);
                if (uri == null) {
                    LOG.warn("Analysis file " + path + " has no uri marker");
                    continue;
                }
                String markdown = stripUriMarker(content);
                LOG.info("HintsFilePoller: read analysis (" + markdown.length() + " chars) for "
                        + uri + " from " + path.getFileName());
                HintsService.updateMarkdownForUri(uri, markdown);
                pendingByPath.put(path, new PendingAnalysis(uri, markdown, modified));
            } catch (Exception e) {
                LOG.warn("Failed to read analysis file " + path + ", skipping", e);
            }
        }
    }

    /**
     * Reads and deletes the error file the Rust LSP server writes when the
     * LLM service fails, and forwards the message to HintsService so the
     * tool window shows it in its status line. The same file-based
     * mechanism as the tick and pause trigger files, only in the other
     * direction: the server writes, the IDE polls. An empty message
     * clears the displayed error (the server writes it at the start of
     * every analysis attempt, so the status line always describes the
     * latest attempt). The file is deleted immediately after reading so
     * each message is processed exactly once.
     */
    private void readErrorFile() {
        Path errorPath = hintsDir.resolve(ERROR_FILE_NAME);
        String content;
        try {
            content = new String(Files.readAllBytes(errorPath), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            LOG.warn("Failed to read error file " + errorPath, e);
            return;
        }
        try {
            Files.deleteIfExists(errorPath);
        } catch (IOException e) {
            LOG.warn("Failed to delete error file " + errorPath, e);
        }
        HintsService.setErrorMessage(content.trim());
    }

    /**
     * Returns the file's current text: the editor's unsaved content when
     * the file is open, its persisted content otherwise. Runs inside a
     * read action, so it is safe from the EDT and from background
     * threads alike. Shared by the attachment inlining below and the
     * Imports pane's local scan (ImportDetector).
     */
    static String readText(VirtualFile file) {
        return ReadAction.compute(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            try {
                return document != null ? document.getText() : VfsUtil.loadText(file);
            } catch (IOException e) {
                LOG.warn("Failed to read " + file.getUrl(), e);
                return null;
            }
        });
    }

    /**
     * Asks the project's Rust LSP server to generate a new analysis of
     * the project's currently selected file by writing a trigger file
     * into the project's hints directory. The server polls for the
     * file and runs a forced tick (bypassing its duplicate check), so the
     * Analyze button always produces a new analysis.
     *
     * The current file and its caret position are resolved on the EDT
     * (FileEditorManager access). The request runs immediately when
     * already on the EDT - the Analyze button's click is - so the
     * trigger timestamp the write records (see
     * HintsService.noteAnalysisTriggered) exists by the time applyTopic
     * reads it back for its status label; from any other thread it is
     * queued onto the EDT as before.
     */
    private static void requestForceTick(@NotNull Project project) {
        Runnable request = () -> {
            String uri = HintsService.getCurrentFileUri(project);
            if (uri == null) {
                LOG.info("Force tick requested but no file is selected in project " + project.getName());
                return;
            }
            Position caretPosition = HintsService.getCurrentCaretPosition(project);
            writeTriggerFile(uri, caretPosition, HintsService.getTopic(), true, project);
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            request.run();
        } else {
            ApplicationManager.getApplication().invokeLater(request);
        }
    }

    /**
     * Writes a trigger file that the project's Rust LSP server picks up to
     * run a tick for the given file. This is the primary trigger mechanism
     * for tab switches and the Analyze button: the custom LSP notifications
     * (custom/didSwitchFile, custom/forceTick) cannot be sent because the
     * IDE's running-server registry is never populated (the platform does
     * not call LspServerDescriptor.createLspServerListener in this build),
     * so the trigger file bypasses the LSP connection entirely.
     *
     * The file goes into the given project's own hints directory (see
     * hintsDirFor), so only that project's server - the one the descriptor
     * launched with the same directory - polls it; with two projects open
     * side by side, one project's trigger can never be consumed by the
     * other project's server.
     *
     * The file is a small JSON document in the hints directory:
     *   {"uri": "...", "line": N, "character": N, "topic": "...", "force": true,
     *    "errors": [{"line": N, "message": "..."}],
     *    "attachments": [{"name": "...", "uri": "...", "content": "..."}]}
     * The Rust server polls for it every 250 ms, reads the parameters,
     * deletes the file, and runs the tick. The write is atomic (tmp+rename)
     * so the server never reads a partially-written file.
     *
     * The errors are the syntax errors the IDE's parser currently reports
     * for the file (see collectErrorsJson); the server remembers them per
     * file and its analysis prompt lists them in the Problems section.
     *
     * The attachments are the files checked in the Imports pane (see
     * attachmentsJson): their content is inlined here because the server
     * has no other way to read them - it only ever sees the documents the
     * LSP client syncs.
     *
     * Writing the trigger also records the trigger timestamp for the file
     * (see HintsService.noteAnalysisTriggered): the completion of the
     * file's next analysis consumes it as the history entry's timestamp,
     * so the history labels the analysis with the time it was triggered -
     * the same time the Analyze click's status label names it with (see
     * HintsPanel.applyTopic) - rather than the later completion time.
     *
     * @param uri           the URI of the file to analyze
     * @param caretPosition the caret position, or null when unknown
     * @param topic         the topic/goal, or an empty string when none
     * @param force         true to bypass the server's duplicate check
     * @param project       the project whose hints directory - and whose
     *                      server - the trigger goes to
     */
    public static void writeTriggerFile(String uri, Position caretPosition, String topic, boolean force, @NotNull Project project) {
        // The trigger moment, recorded before anything below can fail: the
        // file's next analysis completion consumes it as the history entry's
        // timestamp (see HintsService.completeMarkdownForUri).
        HintsService.noteAnalysisTriggered(uri);
        try {
            Path dir = hintsDirFor(project);
            Files.createDirectories(dir);

            StringBuilder json = new StringBuilder();
            json.append("{\"uri\":").append(jsonQuote(uri));
            if (caretPosition != null) {
                json.append(",\"line\":").append(caretPosition.getLine());
                json.append(",\"character\":").append(caretPosition.getCharacter());
            }
            json.append(",\"topic\":").append(jsonQuote(topic == null ? "" : topic));
            json.append(",\"force\":").append(force);
            json.append(",\"errors\":").append(collectErrorsJson(uri));
            json.append(",\"attachments\":").append(attachmentsJson(uri));
            json.append("}");

            Path triggerPath = dir.resolve("tick.trigger");
            Path tempPath = dir.resolve("tick.trigger.tmp");
            Files.write(tempPath, json.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tempPath, triggerPath, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Wrote trigger file for " + uri + (force ? " (forced)" : ""));
        } catch (IOException e) {
            LOG.warn("Failed to write trigger file for " + uri, e);
        }
    }

    /**
     * Builds the JSON array of the files attached to the analysis of the
     * given file: the entries checked in the Imports pane (see
     * HintsService.getAttachmentUris), each with its name, URI and current
     * content. The content is the editor's unsaved text when the file is
     * open. The full content is attached, uncapped: the model's context
     * window is the only limit on how much attachment content the
     * analysis can consider, so a line cap here would silently hide part
     * of an attached file from the analysis.
     */
    private static String attachmentsJson(String uri) {
        StringBuilder json = new StringBuilder("[");
        for (String attachmentUri : HintsService.getAttachmentUris(uri)) {
            VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(attachmentUri);
            if (file == null || file.isDirectory()) {
                LOG.warn("Attachment " + attachmentUri + " does not resolve, skipping");
                continue;
            }
            String content = readText(file);
            if (content == null) {
                continue;
            }
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"name\":").append(jsonQuote(file.getName()));
            json.append(",\"uri\":").append(jsonQuote(attachmentUri));
            json.append(",\"content\":").append(jsonQuote(content));
            json.append('}');
            LOG.info("Attaching " + attachmentUri + " (" + content.lines().count() + " lines)");
        }
        json.append(']');
        return json.toString();
    }

    /**
     * Collects the syntax errors the IDE's parser currently reports for
     * the file and returns them as a JSON array string:
     * [{"line":N,"message":"..."}, ...] with 1-based line numbers
     * matching the editor gutter, in document order. Returns "[]" when
     * the file, its project, its document or its PSI tree cannot be
     * resolved, or when the parser reports no errors.
     *
     * The errors are the file's PSI error elements
     * (com.intellij.psi.PsiErrorElement) - the parser's own error
     * reports, and public API. The previous implementation read the code
     * analyzer's error highlights through
     * DaemonCodeAnalyzerImpl.getHighlights, which also covered the
     * semantic errors the analyzer derives (unresolved references, type
     * errors) and the external compiler errors it relays (e.g. cargo
     * check), but DaemonCodeAnalyzerImpl is internal API - the plugin
     * verifier flags it - and the platform exposes no public API for
     * the analyzer's highlights, so those error kinds are no longer
     * reported and the analysis prompt's Problems section relies on the
     * model finding them itself.
     *
     * The errors are collected here, where the trigger file is written,
     * because the trigger file is the only working channel from the IDE to
     * the server that can carry per-file IDE state (the custom LSP
     * notifications cannot be sent; see writeTriggerFile). The server
     * remembers them per file and lists them in the analysis prompt's
     * Problems section.
     *
     * Any failure is contained: the trigger file must still be written
     * (it is the primary tick mechanism), so on error the list is empty.
     */
    private static String collectErrorsJson(String uri) {
        try {
            return ReadAction.compute(() -> {
                VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(uri);
                if (file == null) {
                    return "[]";
                }
                Project project = ProjectLocator.getInstance().guessProjectForFile(file);
                if (project == null || project.isDisposed()) {
                    return "[]";
                }
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document == null) {
                    return "[]";
                }
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile == null) {
                    return "[]";
                }
                List<PsiErrorElement> errorElements =
                        new ArrayList<>(PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement.class));
                // In document order, so the analysis lists the errors top
                // down like the editor gutter does; findChildrenOfType
                // promises no order.
                errorElements.sort(Comparator.comparingInt(PsiErrorElement::getTextOffset));
                StringBuilder json = new StringBuilder("[");
                for (PsiErrorElement error : errorElements) {
                    String message = error.getErrorDescription();
                    int offset = error.getTextOffset();
                    // A stale PSI tree (the document changed after the last
                    // commit) can hold offsets outside the document; one
                    // such element must not drop the rest of the list.
                    if (message == null || offset < 0 || offset > document.getTextLength()) {
                        continue;
                    }
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append("{\"line\":").append(document.getLineNumber(offset) + 1)
                            .append(",\"message\":").append(jsonQuote(message))
                            .append('}');
                }
                json.append(']');
                return json.toString();
            });
        } catch (Exception e) {
            LOG.warn("Failed to collect parser errors for " + uri, e);
            return "[]";
        }
    }

    /**
     * Writes the pause file that tells the project's Rust LSP server to
     * pause or resume its analyses and completions; see writeTriggerFile
     * for why the LSP connection cannot be used (the running-server
     * registry is never populated in this build).
     *
     * The file goes into the given project's own hints directory, so only
     * that project's server pauses or resumes; pausing one project leaves
     * another project's server running.
     *
     * The file is a small JSON document in the hints directory:
     *   {"paused": true}
     * The server polls for it every 250 ms, deletes it, and either stops
     * all ticks - cancelling the analysis that is currently streaming -
     * or resumes and immediately re-analyzes the current file. The write
     * is atomic (tmp+rename) so the server never reads a partially
     * written file.
     *
     * @param paused  true to pause analyses and completions, false to resume
     * @param project the project whose hints directory - and whose
     *                server - the pause file goes to
     */
    public static void writePauseFile(boolean paused, @NotNull Project project) {
        try {
            Path dir = hintsDirFor(project);
            Files.createDirectories(dir);

            String json = "{\"paused\":" + paused + "}";

            Path pausePath = dir.resolve("pause.trigger");
            Path tempPath = dir.resolve("pause.trigger.tmp");
            Files.write(tempPath, json.getBytes(StandardCharsets.UTF_8));
            Files.move(tempPath, pausePath, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Wrote pause file (paused=" + paused + ")");
        } catch (IOException e) {
            LOG.warn("Failed to write pause file", e);
        }
    }

    /**
     * Quotes a string for inclusion in a hand-built JSON document.
     * Escapes everything the JSON grammar requires inside a string
     * literal: the backslash and the double quote, and every control
     * character below 0x20 (newline, tab, carriage return and the
     * rest). A raw control character makes the whole document
     * unparseable, which is what silently killed the trigger file as
     * soon as the first checked attachment was inlined: an attached
     * file's content is multi-line, so the raw newlines made the
     * trigger JSON invalid and the Rust server dropped the trigger
     * with a parse error instead of running the tick - the Refresh
     * button appeared to do nothing.
     */
    private static String jsonQuote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 8);
        quoted.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted.append("\\\"");
            } else if (c == '\\') {
                quoted.append("\\\\");
            } else if (c == '\n') {
                quoted.append("\\n");
            } else if (c == '\r') {
                quoted.append("\\r");
            } else if (c == '\t') {
                quoted.append("\\t");
            } else if (c == '\b') {
                quoted.append("\\b");
            } else if (c == '\f') {
                quoted.append("\\f");
            } else if (c < 0x20) {
                quoted.append(String.format("\\u%04x", (int) c));
            } else {
                quoted.append(c);
            }
        }
        quoted.append('"');
        return quoted.toString();
    }

    /**
     * Returns the project's hints directory: the directory in the temp
     * filesystem where the project's LSP server writes its analysis files
     * and where the IDE writes the trigger files the server polls. The
     * layout is <root>/greppa/<project>, where <root> is the
     * GREPPA_HINTS_DIR environment variable when the IDE was started
     * with it set and the system temp directory otherwise, and
     * <project> is the project's folder name (see projectFolder). The
     * server descriptor passes the returned directory to the project's
     * LSP server as GREPPA_HINTS_DIR, which the server uses verbatim as
     * its hints directory (see hints_dir in main.rs), so both sides
     * agree on every platform - the JVM's java.io.tmpdir and a
     * hardcoded /tmp would disagree on macOS - and two projects never
     * share a directory.
     */
    public static Path hintsDirFor(@NotNull Project project) {
        String envDir = System.getenv("GREPPA_HINTS_DIR");
        String baseDir = (envDir != null && !envDir.isEmpty())
                ? envDir
                : System.getProperty("java.io.tmpdir");
        return Paths.get(baseDir, "greppa", projectFolder(project));
    }

    /**
     * Returns the project's folder name inside the hints root: the
     * project's name with every character that is unsafe in a file name
     * replaced by '_', followed by a '-' and the stable hash of the
     * project's base path (or of its name when it has no base path).
     * The hash keeps two projects with the same name - two checkouts of
     * one repository, for instance - in separate directories, and it is
     * stable across IDE restarts, so the project keeps its directory
     * from session to session.
     */
    private static String projectFolder(@NotNull Project project) {
        String name = project.getName();
        if (name == null || name.isBlank()) {
            name = "project";
        }
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String identity = project.getBasePath();
        String hash = Integer.toHexString((identity != null ? identity : name).hashCode());
        return sanitized + "-" + hash;
    }

    /**
     * The last content read for an analysis file whose stream may still
     * be in progress, plus the file timestamp it was read at and how many
     * polls the file has been quiet since.
     */
    private static final class PendingAnalysis {
        private final String uri;
        private final String markdown;
        private final long modifiedTime;
        private int quietPolls;

        private PendingAnalysis(String uri, String markdown, long modifiedTime) {
            this.uri = uri;
            this.markdown = markdown;
            this.modifiedTime = modifiedTime;
        }
    }

    /**
     * Returns the URI named by the hidden first-line marker of an analysis
     * file, or null if the marker is absent.
     */
    private static String extractUriMarker(String content) {
        String firstLine = firstLine(content);
        if (firstLine.startsWith(URI_MARKER_PREFIX) && firstLine.endsWith(URI_MARKER_SUFFIX)) {
            return firstLine.substring(URI_MARKER_PREFIX.length(),
                    firstLine.length() - URI_MARKER_SUFFIX.length());
        }
        return null;
    }

    /** Returns the analysis file content without the hidden first-line marker. */
    private static String stripUriMarker(String content) {
        if (extractUriMarker(content) == null) {
            return content;
        }
        int newline = content.indexOf('\n');
        return newline >= 0 ? content.substring(newline + 1) : "";
    }

    private static String firstLine(String content) {
        int newline = content.indexOf('\n');
        String line = newline >= 0 ? content.substring(0, newline) : content;
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private List<Path> listMarkdownFiles() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(hintsDir, "*.md")) {
            List<Path> files = new ArrayList<>();
            for (Path path : stream) {
                files.add(path);
            }
            files.sort(Comparator.comparingLong(this::lastModified).reversed());
            return files;
        } catch (IOException e) {
            LOG.warn("Failed to list analysis directory", e);
            return Collections.emptyList();
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
