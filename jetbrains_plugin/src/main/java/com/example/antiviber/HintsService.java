package com.example.antiviber;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry for the Markdown analysis documents received from the
 * LSP server, keyed by file URI.
 *
 * Documents arrive in two flavors: partial documents while the server
 * streams an analysis (the poller forwards every rewrite of the analysis
 * file), registered via updateMarkdownForUri, and complete documents,
 * registered via completeMarkdownForUri once the poller sees the analysis
 * file has stopped changing.
 *
 * While a stream is in progress, getMarkdown() does not return the partial
 * document verbatim. It merges it with the last complete document of the
 * same file: every section that has fully streamed in replaces its old
 * version, while the sections that have not streamed (yet) keep showing
 * their old content. The panel therefore never blanks and never shows a
 * half-typed section; sections flip from old to new one by one as the
 * stream progresses, and the old content stays readable until it is
 * replaced. For the first analysis of a file there is no old document, so
 * the partial one is shown as it streams.
 *
 * The service also holds the optional topic/goal the user set in the tool
 * window (see HintsPanel). It is session-wide state, not per-URI, and the
 * custom notifications carry it so the LSP server analyzes every file
 * through it.
 *
 * The service also holds the last error message the LSP server reported
 * (the LLM service failed), which the panel shows in its status line; the
 * server clears it by writing an empty error file at the start of every
 * analysis attempt, so the message always describes the latest attempt.
 *
 * The service also keeps a bounded history of the previous complete
 * documents of each file (see completeMarkdownForUri, getHistoryForUri
 * and restoreFromHistory), so the tool window can show and restore them,
 * e.g. after a bad regeneration. The history is in-memory only; it does
 * not survive an IDE restart.
 *
 * The service also stores the Imports pane's state per file: the entries
 * the local import scan produced (see ImportDetector) plus the files the
 * user added manually, and which of them are checked (attached to the
 * analysis); see setImportsForUri and ImportEntry. In-memory only, like
 * the documents themselves.
 *
 * The per-event logs are at debug level, not info: while an analysis
 * streams, the poller forwards updates several times per second, and at
 * info level that flooded idea.log with roughly twenty lines per second
 * for a single analysis. Info stays reserved for rare events (project
 * initialization, file close).
 */
public class HintsService {
    private static final Logger LOG = Logger.getInstance(HintsService.class);

    /**
     * Last complete document per normalized URI; the base that the partial
     * documents of the next analysis are merged into.
     */
    private static final Map<String, String> completeByUri = new ConcurrentHashMap<>();
    /** Latest document per normalized URI; partial while a stream is in progress. */
    private static final Map<String, String> latestByUri = new ConcurrentHashMap<>();
    /**
     * How many previous complete documents are kept per file. Bounded so a
     * long session cannot grow the history without limit; the oldest entry
     * is dropped first.
     */
    private static final int MAX_HISTORY = 5;
    /**
     * Previous complete documents per normalized URI, newest first: the
     * document that was current before the latest analysis completed, then
     * the one before that, and so on. Filled by completeMarkdownForUri
     * whenever a new complete document replaces a different one, so the
     * history contains exactly the analyses the file had, in order.
     * In-memory only, like the documents themselves.
     */
    private static final Map<String, List<String>> historyByUri = new ConcurrentHashMap<>();
    /**
     * The import entries of a file for the Imports pane, per normalized
     * URI: the entries the local import scan produced (see ImportDetector)
     * plus the files the user added manually, in that order. In-memory
     * only, like the analysis documents themselves.
     */
    private static final Map<String, List<ImportEntry>> importsByUri = new ConcurrentHashMap<>();
    private static final List<Runnable> listeners = new ArrayList<>();

    /**
     * The topic/goal the user set in the tool window, or an empty string
     * when none is set. Written on the EDT (the topic field's Enter
     * action) and read wherever the custom notifications are built, hence
     * volatile.
     */
    private static volatile String topic = "";

    /**
     * Whether the LSP server's analyses and completions are paused via the
     * tool window's Pause button. Session-wide state like the topic; the
     * authoritative flag lives in the LSP server (notified through the
     * pause file), this copy only restores the button state when the
     * panel is recreated. Not persisted: a fresh IDE session always
     * starts resumed.
     */
    private static volatile boolean paused = false;

    /**
     * The last error message the LSP server reported (the LLM service
     * failed), or an empty string when none. Written by the poller (from
     * its thread) and read on the EDT, hence volatile. The server clears
     * it by writing an empty error file at the start of every analysis
     * attempt, so the message always describes the latest attempt.
     */
    private static volatile String errorMessage = "";

    public static synchronized void updateMarkdownForUri(String uri, String markdown) {
        String normalized = normalizeUri(uri);
        LOG.debug("HintsService.updateMarkdownForUri: raw=" + uri + " normalized=" + normalized
                + " chars=" + (markdown == null ? 0 : markdown.length()));
        latestByUri.put(normalized, markdown == null ? "" : markdown);
        notifyListenersIfCurrentFile(normalized);
    }

    /**
     * Registers the complete document of a finished analysis. It becomes
     * the merge base for the next analysis of the same file, and the
     * complete document it replaces (when different) becomes the newest
     * history entry, so the tool window can show and restore it; see
     * getHistoryForUri.
     */
    public static synchronized void completeMarkdownForUri(String uri, String markdown) {
        String normalized = normalizeUri(uri);
        LOG.debug("HintsService.completeMarkdownForUri: raw=" + uri + " normalized=" + normalized
                + " chars=" + (markdown == null ? 0 : markdown.length()));
        String content = markdown == null ? "" : markdown;
        String previous = completeByUri.get(normalized);
        if (previous != null && !previous.equals(content)) {
            List<String> history = historyByUri.computeIfAbsent(normalized, key -> new ArrayList<>());
            history.add(0, previous);
            if (history.size() > MAX_HISTORY) {
                history.remove(history.size() - 1);
            }
        }
        completeByUri.put(normalized, content);
        latestByUri.put(normalized, content);
        notifyListenersIfCurrentFile(normalized);
    }

    /**
     * Clears the analysis of the given file, together with its history:
     * without a current document the history entries are meaningless.
     */
    public static synchronized void clearAnalysisForUri(String uri) {
        String normalized = normalizeUri(uri);
        LOG.info("HintsService.clearAnalysisForUri: " + normalized);
        completeByUri.remove(normalized);
        latestByUri.remove(normalized);
        historyByUri.remove(normalized);
        notifyListenersIfCurrentFile(normalized);
    }

    /**
     * Returns the previous complete documents of the given file, newest
     * first, or an empty list when there are none (including a null uri).
     * A copy is returned, so callers can iterate safely while a new
     * analysis updates the history.
     */
    public static synchronized List<String> getHistoryForUri(String uri) {
        if (uri == null) {
            return List.of();
        }
        String normalized = normalizeUri(uri);
        List<String> history = historyByUri.get(normalized);
        return history == null ? List.of() : List.copyOf(history);
    }

    /**
     * Makes the history entry at the given index (0 = the newest previous
     * document, as returned by getHistoryForUri) the current complete
     * document again, for example after a bad regeneration. The document
     * it replaces is not lost: it becomes the newest history entry, so the
     * restore can be undone the same way.
     *
     * The restore is display-level: the analysis file on disk keeps the
     * document the server wrote, so the LSP server's completion context is
     * unaffected, and the next analysis of the file overwrites the panel
     * again. The restored document is the merge base that next analysis
     * streams into, though, so the panel never blanks while it generates.
     */
    public static synchronized void restoreFromHistory(String uri, int index) {
        if (uri == null) {
            return;
        }
        String normalized = normalizeUri(uri);
        List<String> history = historyByUri.get(normalized);
        if (history == null || index < 0 || index >= history.size()) {
            return;
        }
        String restored = history.remove(index);
        String current = completeByUri.get(normalized);
        if (current != null && !current.equals(restored)) {
            history.add(0, current);
            if (history.size() > MAX_HISTORY) {
                history.remove(history.size() - 1);
            }
        }
        completeByUri.put(normalized, restored);
        latestByUri.put(normalized, restored);
        LOG.info("HintsService.restoreFromHistory: restored entry " + index + " for " + normalized);
        notifyListenersIfCurrentFile(normalized);
    }

    /**
     * Registers the scanned import entries of a file and notifies the
     * listeners so the Imports pane shows them. A new entry keeps the
     * checked state the scan produced (found files are checked by
     * default; see ImportDetector), and the checked state of a scanned
     * entry whose specifier and resolved file match an existing one
     * survives the re-scan, so the user's unchecking is preserved.
     * Manually added entries are kept; everything else is replaced.
     */
    public static synchronized void setImportsForUri(String uri, List<ImportEntry> extracted) {
        String normalized = normalizeUri(uri);
        List<ImportEntry> previous = importsByUri.get(normalized);
        List<ImportEntry> merged = new ArrayList<>();
        for (ImportEntry entry : extracted) {
            // A new entry defaults to the checked state the scan produced;
            // an entry that also existed in the previous list keeps the
            // user's choice instead.
            boolean checked = entry.checked;
            if (previous != null) {
                for (ImportEntry old : previous) {
                    if (!old.manual && old.id().equals(entry.id())) {
                        checked = old.checked;
                        break;
                    }
                }
            }
            merged.add(new ImportEntry(entry.specifier, entry.uri, entry.resolvedPath, false, checked));
        }
        if (previous != null) {
            for (ImportEntry old : previous) {
                if (old.manual) {
                    merged.add(old);
                }
            }
        }
        importsByUri.put(normalized, List.copyOf(merged));
        LOG.info("HintsService.setImportsForUri: " + merged.size() + " entr(y/ies) for " + normalized);
        notifyListenersIfCurrentFile(normalized);
    }

    /**
     * Returns the import entries stored for the file, or null when the
     * imports were never scanned (which is distinct from an empty scan
     * result). A copy is returned, so callers can iterate safely while
     * a re-scan updates the list.
     */
    public static synchronized List<ImportEntry> getImportsForUri(String uri) {
        if (uri == null) {
            return null;
        }
        List<ImportEntry> entries = importsByUri.get(normalizeUri(uri));
        return entries == null ? null : List.copyOf(entries);
    }

    /**
     * Sets whether the entry identified by id() is attached to the
     * analysis. Storing is immediate, but no analysis is re-run: the
     * attachment applies from the next analysis on (Refresh, Reload, tab
     * switch, save), so checking several boxes costs no LLM tokens.
     */
    public static synchronized void setImportChecked(String uri, String id, boolean checked) {
        String normalized = normalizeUri(uri);
        List<ImportEntry> entries = importsByUri.get(normalized);
        if (entries == null) {
            return;
        }
        List<ImportEntry> updated = new ArrayList<>();
        boolean changed = false;
        for (ImportEntry entry : entries) {
            if (entry.id().equals(id) && entry.checked != checked) {
                updated.add(new ImportEntry(entry.specifier, entry.uri, entry.resolvedPath, entry.manual, checked));
                changed = true;
            } else {
                updated.add(entry);
            }
        }
        if (changed) {
            importsByUri.put(normalized, List.copyOf(updated));
            notifyListenersIfCurrentFile(normalized);
        }
    }

    /**
     * Adds a file the user picked manually to the file's import entries,
     * checked, so it is attached from the next analysis on. Adding the
     * same file twice is a no-op.
     */
    public static synchronized void addManualImport(String uri, String fileUri, String displayName, String path) {
        String normalized = normalizeUri(uri);
        List<ImportEntry> entries = new ArrayList<>(importsByUri.getOrDefault(normalized, List.of()));
        ImportEntry entry = new ImportEntry(displayName, fileUri, path, true, true);
        for (ImportEntry existing : entries) {
            if (existing.id().equals(entry.id())) {
                return;
            }
        }
        entries.add(entry);
        importsByUri.put(normalized, List.copyOf(entries));
        LOG.info("HintsService.addManualImport: " + fileUri + " for " + normalized);
        notifyListenersIfCurrentFile(normalized);
    }

    /**
     * Removes the entry identified by id() from the file's import entries.
     * Scanned entries reappear with the next scan; manual entries are
     * gone until added again.
     */
    public static synchronized void removeImport(String uri, String id) {
        String normalized = normalizeUri(uri);
        List<ImportEntry> entries = importsByUri.get(normalized);
        if (entries == null) {
            return;
        }
        List<ImportEntry> updated = new ArrayList<>();
        boolean changed = false;
        for (ImportEntry entry : entries) {
            if (entry.id().equals(id)) {
                changed = true;
            } else {
                updated.add(entry);
            }
        }
        if (changed) {
            importsByUri.put(normalized, List.copyOf(updated));
            notifyListenersIfCurrentFile(normalized);
        }
    }

    /**
     * Returns the URLs of the files attached to the file's analysis: the
     * checked entries' resolved files, de-duplicated and in list order.
     * HintsFilePoller.writeTriggerFile inlines their content into the
     * trigger file.
     */
    public static synchronized List<String> getAttachmentUris(String uri) {
        if (uri == null) {
            return List.of();
        }
        List<ImportEntry> entries = importsByUri.get(normalizeUri(uri));
        if (entries == null) {
            return List.of();
        }
        LinkedHashSet<String> uris = new LinkedHashSet<>();
        for (ImportEntry entry : entries) {
            if (entry.checked && entry.uri != null) {
                uris.add(entry.uri);
            }
        }
        return List.copyOf(uris);
    }

    /**
     * Sets the topic/goal the analyses are viewed through; an empty or
     * null value clears it. Does not notify the listeners: the topic
     * alone does not change the displayed document, the re-analysis it
     * triggers does.
     */
    public static void setTopic(String value) {
        topic = value == null ? "" : value;
        LOG.info("HintsService.setTopic: " + (topic.isBlank() ? "(none)" : topic));
    }

    /** Returns the topic/goal, or an empty string when none is set. */
    public static String getTopic() {
        return topic;
    }

    /**
     * Pauses or resumes the LSP server's analyses and completions by
     * writing the pause file the server polls for (see
     * HintsFilePoller.writePauseFile). While paused the server runs no
     * analysis ticks and returns no completion items, so no LLM tokens
     * are spent; the displayed analysis is left exactly as it is.
     */
    public static void setPaused(boolean value) {
        paused = value;
        LOG.info("HintsService.setPaused: " + (value ? "paused" : "resumed"));
        HintsFilePoller.writePauseFile(value);
    }

    /** Returns whether analyses and completions are paused. */
    public static boolean isPaused() {
        return paused;
    }

    /**
     * Stores an error message reported by the LSP server (the LLM service
     * failed) and notifies the listeners so the panel shows it in its
     * status line. An empty or null message clears the error. Unchanged
     * messages are ignored, so the periodic clear the server writes at the
     * start of every analysis attempt does not spam the listeners.
     */
    public static void setErrorMessage(String message) {
        String value = message == null ? "" : message;
        if (value.equals(errorMessage)) {
            return;
        }
        errorMessage = value;
        LOG.info("HintsService.setErrorMessage: " + (value.isBlank() ? "(none)" : value));
        notifyListeners();
    }

    /** Returns the last error message, or an empty string when none. */
    public static String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the Markdown analysis document for the currently selected
     * file, or an empty string if none is available. While an analysis is
     * streaming, the document is the merge of the last complete document
     * and the partial one; see the class comment.
     */
    public static synchronized String getMarkdown() {
        String currentUri = getCurrentFileUri();
        if (currentUri == null) {
            LOG.debug("HintsService.getMarkdown: no current file URI");
            return "";
        }
        String normalized = normalizeUri(currentUri);
        String latest = latestByUri.get(normalized);
        if (latest == null) {
            LOG.debug("HintsService.getMarkdown: current=" + currentUri + " normalized=" + normalized
                    + " found=0 keys=" + latestByUri.keySet());
            return "";
        }
        String base = completeByUri.get(normalized);
        String merged = mergeDocuments(base, latest);
        LOG.debug("HintsService.getMarkdown: current=" + currentUri + " normalized=" + normalized
                + " latest=" + latest.length() + " chars base="
                + (base == null ? "none" : base.length() + " chars")
                + " merged=" + merged.length() + " chars");
        return merged;
    }

    /**
     * Returns the Markdown analysis document for the currently selected
     * file, reduced to the sections the given names select - the variant
     * the tool window's tabs use, where each tab shows its own subset of
     * the same analysis (see HintsToolWindowFactory). The full merged
     * document is still what the merge, the history and the poller work
     * with, so the streaming behavior is unchanged; only the document
     * handed to the caller is filtered.
     */
    public static synchronized String getMarkdown(@NotNull List<String> sectionNames) {
        return filterSections(getMarkdown(), sectionNames);
    }

    /**
     * Reduces a Markdown document to the sections the given names select:
     * every ## section whose heading contains one of the names
     * (case-insensitive), in document order. When none of the named
     * sections is present, the text before the first ## heading is
     * returned instead, so a document that is still the "Generating
     * interpretation..." placeholder keeps the tabs showing the
     * placeholder rather than their empty-state card. Public so
     * HintsPanel filters the history entries exactly like the live
     * document (see getHistoryForUri). An empty or null name list returns
     * the document unchanged.
     */
    public static String filterSections(@Nullable String markdown, @NotNull List<String> sectionNames) {
        if (sectionNames == null || sectionNames.isEmpty()) {
            return markdown == null ? "" : markdown;
        }
        String document = markdown == null ? "" : markdown;
        Sections sections = splitSections(document);
        StringBuilder matched = new StringBuilder(document.length());
        for (Section section : sections.sections) {
            if (headingMatches(section.heading, sectionNames)) {
                matched.append(section.text);
            }
        }
        return matched.length() == 0 ? sections.preamble : matched.toString();
    }

    /**
     * Returns whether the heading line names one of the selected
     * sections. The match is a case-insensitive substring test, so the
     * tab definitions need not repeat the headings verbatim:
     * "Recommendations" also matches a "## Specific recommendations"
     * heading.
     */
    private static boolean headingMatches(@NotNull String heading, @NotNull List<String> sectionNames) {
        String lower = heading.toLowerCase(Locale.ROOT);
        for (String name : sectionNames) {
            if (lower.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static synchronized void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public static synchronized void notifyListeners() {
        LOG.debug("HintsService.notifyListeners: " + listeners.size() + " listener(s)");
        for (Runnable listener : new ArrayList<>(listeners)) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.warn("HintsService listener failed", e);
            }
        }
    }

    /**
     * Notifies the listeners only when the given normalized URI is the file
     * currently selected in the editor.
     *
     * The poller forwards every analysis file the server writes, including
     * files that are not open in the editor right now; notifying for those
     * made the panel refresh with unchanged content, which looked like the
     * page randomly updating on its own. The analysis is still stored
     * regardless, so it appears when the user switches to that file: the
     * selection listener registered in initForProject then triggers the
     * refresh.
     *
     * The check runs on the EDT because it queries the FileEditorManager,
     * while updateMarkdownForUri is called from the poller thread.
     */
    private static void notifyListenersIfCurrentFile(String normalizedUri) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String currentUri = normalizeUri(getCurrentFileUri());
            if (normalizedUri.equals(currentUri)) {
                notifyListeners();
            } else {
                LOG.debug("HintsService: analysis is for non-selected file " + normalizedUri
                        + ", skipping notification");
            }
        });
    }

    /**
     * Subscribes to project-level editor selection changes so the UI refreshes
     * when the user switches tabs. Must be called once per project.
     */
    public static void initForProject(@NotNull Project project) {
        LOG.info("HintsService.initForProject called for " + project.getName());
        project.getMessageBus().connect().subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                        LOG.debug("HintsService: editor selection changed, refreshing UI");
                        notifyListeners();
                    }
                }
        );
    }

    /**
     * Returns the URL of the file currently selected in the editor, or null
     * if no file is open. Public so HintsFilePoller can target the
     * custom/forceTick notification at the file the user is currently
     * looking at. Must be called on the EDT because it queries the
     * FileEditorManager.
     */
    @Nullable
    public static String getCurrentFileUri() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        for (Project project : projects) {
            if (project.isDisposed()) {
                continue;
            }
            VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
            if (selectedFiles.length > 0) {
                return selectedFiles[0].getUrl();
            }
        }
        return null;
    }

    /**
     * Returns the caret position of the currently selected text editor as
     * an LSP position (0-based line and column), or null when the selected
     * file has no text editor or no file is open. Used by the custom
     * didSwitchFile and forceTick notifications so the analysis can mark
     * the segment containing the cursor. Must be called on the EDT because
     * it queries the FileEditorManager.
     */
    @Nullable
    public static Position getCurrentCaretPosition() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        for (Project project : projects) {
            if (project.isDisposed()) {
                continue;
            }
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) {
                LogicalPosition caret = editor.getCaretModel().getLogicalPosition();
                return new Position(caret.line, caret.column);
            }
        }
        return null;
    }

    /**
     * Normalizes file URIs so that minor encoding/case differences between
     * LSP URIs and VirtualFile URLs do not break analysis lookup.
     *
     * LSP URIs look like "file:///C:/dir/File.txt", while IntelliJ VirtualFile
     * URLs on Windows look like "file://C:/dir/File.txt", where java.net.URI
     * puts the drive letter into the authority component. Both forms are
     * folded into the same canonical key so lookups always match.
     */
    @Nullable
    private static String normalizeUri(@Nullable String uri) {
        if (uri == null) {
            return null;
        }
        try {
            URI u = new URI(uri);
            if ("file".equalsIgnoreCase(u.getScheme())) {
                String path = u.getPath();
                if (path != null) {
                    // IntelliJ URLs ("file://C:/...") put the drive letter in
                    // the authority; fold it back into the path so both URI
                    // styles normalize to the same key.
                    String authority = u.getAuthority();
                    if (authority != null && authority.length() == 2 && authority.charAt(1) == ':') {
                        path = "/" + authority + path;
                    }
                    // Normalize Windows drive letter to uppercase.
                    if (path.length() >= 3 && path.charAt(0) == '/' && path.charAt(2) == ':') {
                        path = "/" + Character.toUpperCase(path.charAt(1)) + path.substring(3);
                    }
                    return new URI("file", null, path, null).toString();
                }
            }
            return u.normalize().toString();
        } catch (Exception e) {
            LOG.warn("Failed to normalize URI: " + uri, e);
            return uri;
        }
    }

    /**
     * Merges the last complete document of a file (the base) with the
     * latest partial one. The server streams a document append-only, so
     * every section of the partial document except the last one is
     * complete: those sections replace their old versions. The last
     * section is still streaming, so the old version is shown instead,
     * followed by the old versions of the sections that have not started
     * streaming yet. A section whose heading does not exist in the base
     * (e.g. the conditional Answered questions section) has no old
     * version, so it streams visibly, with the old document's not yet
     * replaced sections kept below it.
     */
    @NotNull
    private static String mergeDocuments(@Nullable String base, @NotNull String latest) {
        if (base == null) {
            // First analysis of this file: no old document to keep visible,
            // so show the partial document as it streams.
            return latest;
        }
        Sections baseSections = splitSections(base);
        Sections latestSections = splitSections(latest);
        if (latestSections.sections.isEmpty()) {
            // Not even the first heading has streamed in: no section of the
            // new document is complete, so keep showing the old document.
            return base;
        }

        StringBuilder merged = new StringBuilder(base.length() + latest.length());
        // The preamble before the first heading cannot grow anymore once
        // the first heading has arrived, so it is complete too.
        merged.append(latestSections.preamble);
        List<Section> complete = latestSections.sections.subList(0, latestSections.sections.size() - 1);
        for (Section section : complete) {
            merged.append(section.text);
        }

        Section trailing = latestSections.sections.get(latestSections.sections.size() - 1);
        int baseIndex = indexOfSection(baseSections.sections, trailing.heading);
        if (baseIndex < 0) {
            merged.append(trailing.text);
            for (Section section : baseSections.sections) {
                // Skip the old versions of sections the new document has
                // already replaced, so they do not appear twice.
                if (indexOfSection(complete, section.heading) < 0) {
                    merged.append(section.text);
                }
            }
        } else {
            merged.append(baseSections.sections.get(baseIndex).text);
            for (int i = baseIndex + 1; i < baseSections.sections.size(); i++) {
                merged.append(baseSections.sections.get(i).text);
            }
        }
        return merged.toString();
    }

    /**
     * Splits a Markdown document into the text before its first heading
     * (the preamble) and its ## sections. Heading detection ignores lines
     * inside fenced code blocks, so a "## " line inside a code snippet does
     * not split the document.
     */
    @NotNull
    private static Sections splitSections(@NotNull String markdown) {
        StringBuilder preamble = new StringBuilder();
        List<Section> sections = new ArrayList<>();
        StringBuilder current = preamble;
        String currentHeading = null;
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence && line.startsWith("## ")) {
                if (currentHeading != null) {
                    sections.add(new Section(currentHeading, current.toString()));
                }
                current = new StringBuilder();
                currentHeading = line.trim();
            }
            current.append(line).append('\n');
        }
        if (currentHeading != null) {
            sections.add(new Section(currentHeading, current.toString()));
        }
        return new Sections(preamble.toString(), sections);
    }

    /** Returns the index of the section with the given heading, or -1. */
    private static int indexOfSection(@NotNull List<Section> sections, @NotNull String heading) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).heading.equals(heading)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * One row of the Imports pane: a local file the scan found (manual =
     * false) or a file the user added manually (manual = true). Immutable;
     * the service methods replace whole entries.
     */
    public static final class ImportEntry {
        /** The import string exactly as written in the code, or the added file's name. */
        public final String specifier;
        /** The resolved file's URL. */
        public final String uri;
        /** The resolved file's path, shown in the pane. */
        public final String resolvedPath;
        /** Whether the user added this entry manually; manual entries survive re-scans. */
        public final boolean manual;
        /** Whether the file is attached to the analysis as context. */
        public final boolean checked;

        public ImportEntry(String specifier, String uri, String resolvedPath, boolean manual, boolean checked) {
            this.specifier = specifier;
            this.uri = uri;
            this.resolvedPath = resolvedPath;
            this.manual = manual;
            this.checked = checked;
        }

        /**
         * Stable identity of the entry within its file's list: the
         * specifier together with the resolved file, so two imports of
         * the same file are still distinct rows while re-scans can carry
         * the checked state over.
         */
        public String id() {
            return specifier + "\n" + uri;
        }
    }

    /** One ## section of a Markdown document. */
    private static final class Section {
        /** The trimmed heading line, e.g. "## Problems". */
        final String heading;
        /** The heading line plus the section body. */
        final String text;

        Section(String heading, String text) {
            this.heading = heading;
            this.text = text;
        }
    }

    /** The result of splitting a Markdown document into sections. */
    private static final class Sections {
        /** The text before the first ## heading. */
        final String preamble;
        final List<Section> sections;

        Sections(String preamble, List<Section> sections) {
            this.preamble = preamble;
            this.sections = sections;
        }
    }
}
