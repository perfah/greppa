package com.example.greppa;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
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
import org.eclipse.lsp4j.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Polls the LSP server's Markdown analysis files and updates the tool window.
 * The Rust server writes one file per analyzed file to
 * %ANTIVIBER_HINTS_DIR%/greppa-hints/&lt;sanitized-uri&gt;.md
 * (or %TEMP%/greppa-hints on Windows, /tmp/greppa-hints elsewhere).
 * The first line of each file is a hidden marker that names the analyzed
 * file's URI; the rest of the file is the Markdown document.
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
 * The manual reload action calls pollNow(), which additionally asks the
 * server to re-analyze the currently selected file by writing a trigger
 * file (see writeTriggerFile): re-reading the disk files alone would only
 * show the previous analysis, because the server skips ticks for
 * unchanged files.
 */
public class HintsFilePoller implements Runnable {

    private static final Logger LOG = Logger.getInstance(HintsFilePoller.class);
    private static final int MAX_FILES_TO_KEEP = 10;
    private static final String URI_MARKER_PREFIX = "<!-- greppa-uri: ";
    private static final String URI_MARKER_SUFFIX = " -->";
    /** Name of the error file the Rust server writes when the LLM service fails. */
    private static final String ERROR_FILE_NAME = "error.trigger";
    /** Maximum number of lines of one attached file sent to the analysis. */
    private static final int MAX_LINES_PER_ATTACHMENT = 400;
    /** Maximum number of attached lines sent to the analysis in total. */
    private static final int MAX_TOTAL_ATTACHMENT_LINES = 2000;

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

    private static HintsFilePoller instance;

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

    public HintsFilePoller() {
        this.hintsDir = getHintsDir();
        LOG.info("HintsFilePoller watching directory: " + this.hintsDir.toAbsolutePath());
    }

    public void start() {
        instance = this;
        Thread thread = new Thread(this, "Greppa Interpretation Poller");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
    }

    /**
     * Entry point of the manual reload action: re-reads the analysis files
     * already on disk (immediate feedback, and the only option when no LSP
     * server is running) and asks the server to run a fresh analysis of
     * the currently selected file. The new analysis file is picked up by
     * the regular polling loop shortly after the server writes it.
     */
    public static void pollNow() {
        HintsFilePoller poller = instance;
        if (poller != null) {
            poller.forcePoll();
        } else {
            LOG.warn("HintsFilePoller.pollNow called before poller started");
        }
        requestForceTick();
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
     * Re-reads all analysis files even if they have not changed. Used by the
     * manual reload action.
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
                        // A blank document means the server truncated the
                        // file at the start of a stream (or a write is in
                        // progress); committing it would blank the panel, so
                        // keep waiting for real content instead.
                        if (!pending.markdown.isBlank()) {
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

        cleanOldFiles(files);
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
     * Asks the Rust LSP server to re-analyze the currently selected file by
     * writing a trigger file. The server polls for the file and runs a
     * forced tick (bypassing its duplicate check), so clicking reload or
     * Apply always produces a fresh analysis.
     *
     * The current file and its caret position are resolved on the EDT
     * (FileEditorManager access).
     */
    private static void requestForceTick() {
        ApplicationManager.getApplication().invokeLater(() -> {
            String uri = HintsService.getCurrentFileUri();
            if (uri == null) {
                LOG.info("Force tick requested but no file is selected");
                return;
            }
            Position caretPosition = HintsService.getCurrentCaretPosition();
            writeTriggerFile(uri, caretPosition, HintsService.getTopic(), true);
        });
    }

    /**
     * Writes a trigger file that the Rust LSP server picks up to run a tick
     * for the given file. This is the primary trigger mechanism for tab
     * switches and the reload/Apply button: the custom LSP notifications
     * (custom/didSwitchFile, custom/forceTick) cannot be sent because the
     * IDE's running-server registry is never populated (the platform does
     * not call LspServerDescriptor.createLspServerListener in this build),
     * so the trigger file bypasses the LSP connection entirely.
     *
     * The file is a small JSON document in the hints directory:
     *   {"uri": "...", "line": N, "character": N, "topic": "...", "force": true,
     *    "errors": [{"line": N, "message": "..."}],
     *    "attachments": [{"name": "...", "uri": "...", "content": "..."}]}
     * The Rust server polls for it every 250 ms, reads the parameters,
     * deletes the file, and runs the tick. The write is atomic (tmp+rename)
     * so the server never reads a partially-written file.
     *
     * The errors are the compilation/syntax errors the IDE's code analyzer
     * currently reports for the file (see collectErrorsJson); the server
     * remembers them per file and its analysis prompt lists them in the
     * Problems section.
     *
     * The attachments are the files checked in the Imports pane (see
     * attachmentsJson): their content is inlined here because the server
     * has no other way to read them - it only ever sees the documents the
     * LSP client syncs.
     *
     * @param uri           the URI of the file to analyze
     * @param caretPosition the caret position, or null when unknown
     * @param topic         the topic/goal, or an empty string when none
     * @param force         true to bypass the server's duplicate check
     */
    public static void writeTriggerFile(String uri, Position caretPosition, String topic, boolean force) {
        try {
            Path dir = getHintsDir();
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
     * open. Each file is capped at MAX_LINES_PER_ATTACHMENT lines and all
     * files together at MAX_TOTAL_ATTACHMENT_LINES, with a visible
     * truncation note, so one huge dependency cannot crowd the analyzed
     * file out of the analysis prompt.
     */
    private static String attachmentsJson(String uri) {
        StringBuilder json = new StringBuilder("[");
        int remainingLines = MAX_TOTAL_ATTACHMENT_LINES;
        for (String attachmentUri : HintsService.getAttachmentUris(uri)) {
            if (remainingLines <= 0) {
                LOG.warn("Attachment line budget exhausted, skipping " + attachmentUri);
                break;
            }
            VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(attachmentUri);
            if (file == null || file.isDirectory()) {
                LOG.warn("Attachment " + attachmentUri + " does not resolve, skipping");
                continue;
            }
            String content = readText(file);
            if (content == null) {
                continue;
            }
            List<String> lines = content.lines().collect(Collectors.toList());
            int budget = Math.min(MAX_LINES_PER_ATTACHMENT, remainingLines);
            int keptLines = Math.min(budget, lines.size());
            boolean truncated = lines.size() > keptLines;
            StringBuilder kept = new StringBuilder();
            for (int i = 0; i < keptLines; i++) {
                if (i > 0) {
                    kept.append('\n');
                }
                kept.append(lines.get(i));
            }
            if (truncated) {
                kept.append("\n... [truncated: first ").append(keptLines)
                        .append(" of ").append(lines.size()).append(" lines]");
            }
            remainingLines -= keptLines;
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"name\":").append(jsonQuote(file.getName()));
            json.append(",\"uri\":").append(jsonQuote(attachmentUri));
            json.append(",\"content\":").append(jsonQuote(kept.toString()));
            json.append('}');
            LOG.info("Attaching " + attachmentUri + " (" + keptLines + " lines)");
        }
        json.append(']');
        return json.toString();
    }

    /**
     * Collects the compilation/syntax errors the IDE's code analyzer
     * currently reports for the file - the same red highlights the editor
     * and the Problems view show - and returns them as a JSON array
     * string: [{"line":N,"message":"..."}, ...] with 1-based line numbers
     * matching the editor gutter. Returns "[]" when the file, its project
     * or its document cannot be resolved, or when the analyzer reports no
     * errors.
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
                // getHighlights expects the severity filter as its second
                // parameter, before the project.
                List<HighlightInfo> highlights =
                        DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.ERROR, project);
                StringBuilder json = new StringBuilder("[");
                for (HighlightInfo info : highlights) {
                    String message = info.getDescription();
                    if (message == null) {
                        continue;
                    }
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append("{\"line\":").append(document.getLineNumber(info.getStartOffset()) + 1)
                            .append(",\"message\":").append(jsonQuote(message))
                            .append('}');
                }
                json.append(']');
                return json.toString();
            });
        } catch (Exception e) {
            LOG.warn("Failed to collect code analyzer errors for " + uri, e);
            return "[]";
        }
    }

    /**
     * Writes the pause file that tells the Rust LSP server to pause or
     * resume its analyses and completions; see writeTriggerFile for why
     * the LSP connection cannot be used (the running-server registry is
     * never populated in this build).
     *
     * The file is a small JSON document in the hints directory:
     *   {"paused": true}
     * The server polls for it every 250 ms, deletes it, and either stops
     * all ticks - cancelling the analysis that is currently streaming -
     * or resumes and immediately re-analyzes the current file. The write
     * is atomic (tmp+rename) so the server never reads a partially
     * written file.
     *
     * @param paused true to pause analyses and completions, false to resume
     */
    public static void writePauseFile(boolean paused) {
        try {
            Path dir = getHintsDir();
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
     * Returns the hints directory that both the IDE poller and the Rust
     * server agree on: $ANTIVIBER_HINTS_DIR/greppa-hints, or
     * &lt;tmp&gt;/greppa-hints when the environment variable is not set.
     */
    private static Path getHintsDir() {
        String envDir = System.getenv("ANTIVIBER_HINTS_DIR");
        String baseDir = (envDir != null && !envDir.isEmpty())
                ? envDir
                : System.getProperty("java.io.tmpdir");
        return Paths.get(baseDir, "greppa-hints");
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

    private void cleanOldFiles(List<Path> files) {
        if (files.size() <= MAX_FILES_TO_KEEP) {
            return;
        }
        for (Path path : files.subList(MAX_FILES_TO_KEEP, files.size())) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warn("Failed to delete old analysis file " + path, e);
            }
        }
    }
}
