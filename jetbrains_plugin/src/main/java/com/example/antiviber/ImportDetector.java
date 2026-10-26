package com.example.antiviber;

import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the local files a file imports, without an LLM inference: the
 * file's text is scanned for references that could denote a file, and
 * every candidate is resolved relative to the file's directory (see
 * ImportResolver). Three shapes of reference are scanned:
 *
 * - quoted path-like strings, the import paths of most languages
 *   (e.g. "./prompts", "foo.h", '../lib/util');
 * - module declarations, `mod name;` and `pub mod name;`, which name
 *   a file without quoting a path (name.rs or name/mod.rs);
 * - `use` declarations, `use path::to::item;`, whose separator-delimited
 *   module path is resolved generically (see addUseCandidate): the
 *   separators become path separators and progressively shorter
 *   prefixes of the path are tried, because the last segment of such
 *   a path is usually an item name (a type, a function) rather than
 *   a file.
 *
 * The statement shapes are the Rust forms, but nothing else about the
 * scan or the resolution assumes a language: a file of any language is
 * scanned the same way, and a candidate is only ever verified against
 * the filesystem, never interpreted. Dotted imports that quote no path
 * and declare no module (e.g. Java's `import com.example.Foo;`)
 * produce no candidate; the Imports pane's Add file button covers them.
 *
 * A candidate that resolves to an existing file becomes a viable entry,
 * checked by default so the found files attach to the next analysis
 * without extra clicks; the user can uncheck them in the Imports pane,
 * and a re-scan preserves that choice (see
 * HintsService.setImportsForUri). A candidate that does not resolve is
 * kept as an unresolved entry (a null uri) instead of being discarded,
 * so the Imports pane can show it greyed out at the bottom of the list
 * as tried-but-not-found: a `mod foo;` without a foo.rs, a `use` path
 * naming no local file (standard-library and third-party paths end up
 * here), or a quoted path that does not exist. The existence check is
 * also what keeps standard-library and third-party imports out of the
 * viable rows: those are not files next to the analyzed one.
 *
 * What the scan cannot see, the user adds manually in the Imports pane,
 * and the analysis itself relies on the model's trained understanding
 * of standard and third-party libraries anyway.
 */
public final class ImportDetector {

    /**
     * Quoted strings in the text, double- or single-quoted, up to 260
     * characters: the import paths of most languages appear verbatim
     * in quotes (e.g. "./prompts", "foo.h", '../lib/util'). The length
     * cap keeps a missing closing quote from swallowing a whole file.
     */
    private static final Pattern QUOTED_STRING =
            Pattern.compile("\"([^\"\\n]{1,260})\"|'([^'\\n]{1,260})'");

    /**
     * Module declarations: `mod name;` and `pub mod name;`. The name is
     * a plain identifier that Rust resolves to name.rs or name/mod.rs
     * next to the declaring file, which ImportResolver's extension and
     * mod-file variants cover. The statement shape is Rust's, but the
     * pattern only ever adds candidates: a file of another language that
     * happens to contain this shape is at worst one extra unresolved
     * row.
     */
    private static final Pattern MOD_DECLARATION =
            Pattern.compile("\\b(?:pub\\s+)?mod\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*;");

    /**
     * `use` declarations: `use path::to::item;`, optionally `pub use`,
     * optionally renamed with `as`, and optionally ending in a brace
     * group (`use path::to::{A, B};`) - the group is consumed so the
     * declaration still matches and the path before it resolves, but
     * it is not expanded into one path per item. At least one
     * `::`-separated segment is required, so a plain `use foo;` (no
     * separator) is not picked up: without a separator there is no
     * path to resolve, and prose in comments would match far too
     * easily.
     */
    private static final Pattern USE_DECLARATION =
            Pattern.compile("\\buse\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:::[a-zA-Z_][a-zA-Z0-9_]*)+)(?:\\s*::\\s*\\{[^}]*\\})?(?:\\s+as\\s+[a-zA-Z_][a-zA-Z0-9_]*)?\\s*;");

    private ImportDetector() {
    }

    /**
     * Returns the import entries of the file: one per distinct
     * reference, in first-mention order. Resolved references become
     * viable, checked entries; unresolved ones carry a null uri and
     * are rendered greyed out at the bottom of the Imports pane. An
     * empty list when the file's content cannot be read or nothing is
     * referenced at all.
     */
    public static List<HintsService.ImportEntry> detect(VirtualFile file) {
        String content = HintsFilePoller.readText(file);
        if (content == null) {
            return List.of();
        }
        // Resolved references are keyed by the file they resolved to, so
        // two references to the same file (e.g. a mod declaration and a
        // use path for it) collapse into one row; unresolved references
        // have no file, so they are keyed by their specifier.
        Map<String, HintsService.ImportEntry> byKey = new LinkedHashMap<>();
        Matcher quoted = QUOTED_STRING.matcher(content);
        while (quoted.find()) {
            String candidate = quoted.group(1) != null ? quoted.group(1) : quoted.group(2);
            candidate = candidate.trim();
            if (looksPathLike(candidate)) {
                addCandidate(file, candidate, byKey);
            }
        }
        Matcher mod = MOD_DECLARATION.matcher(content);
        while (mod.find()) {
            addCandidate(file, mod.group(1), byKey);
        }
        Matcher use = USE_DECLARATION.matcher(content);
        while (use.find()) {
            addUseCandidate(file, use.group(1), byKey);
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Resolves one candidate and records the result. A candidate that
     * resolves to a file other than the analyzed file itself becomes a
     * viable, checked entry; a candidate that resolves to nothing is
     * recorded with a null uri, so the pane can show that it was looked
     * up but not found.
     */
    private static void addCandidate(
            VirtualFile file,
            String candidate,
            Map<String, HintsService.ImportEntry> byKey
    ) {
        VirtualFile resolved = ImportResolver.resolve(file, candidate);
        // The file itself is not its own import.
        if (resolved != null && resolved.getUrl().equals(file.getUrl())) {
            return;
        }
        String key = resolved != null ? resolved.getUrl() : "?" + candidate;
        byKey.putIfAbsent(
                key,
                new HintsService.ImportEntry(
                        candidate,
                        resolved == null ? null : resolved.getUrl(),
                        resolved == null ? null : resolved.getPath(),
                        false,
                        resolved != null));
    }

    /**
     * Resolves one `use` declaration's module path and records the
     * result, generically: the path is turned into file-path candidates
     * without assuming the language's module semantics beyond the
     * separators. The leading crate/self keywords are dropped (the
     * crate root is approximated by the file's own directory and the
     * project roots, both of which ImportResolver.resolve tries), super
     * maps to the parent directory, and the remaining separators
     * become path separators. Because the last segment of a use path is
     * usually an item name (a type, a function) rather than a file,
     * progressively shorter prefixes of the path are tried and the
     * first one that resolves to a file wins: `path::to::item` tries
     * `path/to/item`, then `path/to`, then `path`. When no prefix
     * resolves, the declaration is kept as an unresolved entry so the
     * pane shows that it was looked up but not found -
     * standard-library and third-party paths end up there.
     */
    private static void addUseCandidate(
            VirtualFile file,
            String specifier,
            Map<String, HintsService.ImportEntry> byKey
    ) {
        List<String> segments = new ArrayList<>();
        for (String segment : specifier.split("::")) {
            if (segments.isEmpty() && (segment.equals("crate") || segment.equals("self"))) {
                // Path keywords, not directories: crate/self root the
                // path at the module tree's root, which the file's own
                // directory and the project roots approximate.
                continue;
            }
            if (segment.equals("super")) {
                segments.add("..");
                continue;
            }
            segments.add(segment);
        }
        for (int end = segments.size(); end >= 1; end--) {
            String candidate = String.join("/", segments.subList(0, end));
            VirtualFile resolved = ImportResolver.resolve(file, candidate);
            if (resolved != null && !resolved.getUrl().equals(file.getUrl())) {
                byKey.putIfAbsent(
                        resolved.getUrl(),
                        new HintsService.ImportEntry(
                                specifier, resolved.getUrl(), resolved.getPath(), false, true));
                return;
            }
        }
        byKey.putIfAbsent(
                "?" + specifier,
                new HintsService.ImportEntry(specifier, null, null, false, false));
    }

    /**
     * Whether a quoted string could denote a file path at all: something
     * with a directory separator, or a name with a file extension. Bare
     * identifiers are excluded from the quoted scan - they are
     * overwhelmingly package names and prose, and too ambiguous to
     * resolve; the mod and use scans cover the bare-identifier forms
     * that genuinely name a file. Glob patterns (a '*') are excluded
     * too: they are not file paths.
     */
    private static boolean looksPathLike(String candidate) {
        if (candidate.contains("*")) {
            return false;
        }
        if (candidate.contains("/") || candidate.contains("\\")) {
            return true;
        }
        int dot = candidate.lastIndexOf('.');
        if (dot <= 0 || dot == candidate.length() - 1) {
            return false;
        }
        String extension = candidate.substring(dot + 1);
        if (extension.length() > 10) {
            return false;
        }
        for (int i = 0; i < extension.length(); i++) {
            if (!Character.isLetterOrDigit(extension.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
