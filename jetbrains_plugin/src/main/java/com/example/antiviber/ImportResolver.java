package com.example.antiviber;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves an import-like path found in a file's text (see
 * ImportDetector) to a file in the project, without assuming any
 * language: the candidate is only verified, never interpreted.
 *
 * Resolution is purely path-based and quick, which is what keeps
 * standard-library and third-party imports out of the Imports pane:
 * those are not files next to the analyzed one, so the existence
 * check fails fast and the candidate is discarded on the spot. The
 * candidate is tried as written - first as an absolute path, then
 * relative to the analyzed file's directory - and, when it has no
 * extension, with the common extensions and the index/mod file names
 * appended. A candidate with directories is also tried against the
 * open projects' roots, because some module systems root their
 * imports there. Anything the scan misses can still be attached with
 * the Imports pane's Add file button.
 */
public final class ImportResolver {

    /**
     * Extensions tried when a path has none, e.g. an extension-less
     * TypeScript import or a Rust module reference. Ordered by rough
     * popularity, so the common case is found first.
     */
    private static final String[] EXTENSIONS = {
            "rs", "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "go",
            "c", "h", "cpp", "cc", "hpp", "cs", "rb", "php", "swift", "m",
            "scala", "sh", "json", "xml", "yaml", "yml", "toml", "md", "txt",
            "html", "css"
    };

    private ImportResolver() {
    }

    /**
     * Resolves one candidate path to a project file, or null when it
     * cannot be resolved. Null is the discard: the caller drops the
     * candidate instead of listing it, which is what filters out
     * standard-library and third-party imports quickly.
     */
    @Nullable
    public static VirtualFile resolve(VirtualFile currentFile, String path) {
        String trimmed = path.trim();
        if (looksAbsolute(trimmed)) {
            VirtualFile file = LocalFileSystem.getInstance()
                    .findFileByPath(trimmed.replace('\\', '/'));
            if (isFile(file)) {
                return file;
            }
        }
        VirtualFile file = resolveRelative(currentFile.getParent(), trimmed);
        if (file != null) {
            return file;
        }
        // A candidate with directories may also be rooted at a project
        // root (e.g. TypeScript baseUrl imports); a bare file name is too
        // ambiguous to look up there.
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                file = resolveRelative(project.getBaseDir(), trimmed);
                if (file != null) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * Whether the candidate is an absolute path: a leading slash or a
     * Windows drive prefix.
     */
    private static boolean looksAbsolute(String path) {
        return path.startsWith("/") ||
                (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':');
    }

    /**
     * Resolves a relative path against a base directory: the path as
     * written, then with each common extension appended, then as a
     * directory with an index or mod file inside. Directories never
     * count as a resolution - an attachment must have content.
     */
    @Nullable
    private static VirtualFile resolveRelative(@Nullable VirtualFile base, String relativePath) {
        if (base == null) {
            return null;
        }
        String normalized = relativePath.trim().replace('\\', '/');
        VirtualFile file = walk(base, normalized);
        if (isFile(file)) {
            return file;
        }
        for (String extension : EXTENSIONS) {
            file = walk(base, normalized + "." + extension);
            if (isFile(file)) {
                return file;
            }
        }
        for (String extension : EXTENSIONS) {
            file = walk(base, normalized + "/index." + extension);
            if (isFile(file)) {
                return file;
            }
        }
        for (String extension : EXTENSIONS) {
            file = walk(base, normalized + "/mod." + extension);
            if (isFile(file)) {
                return file;
            }
        }
        return null;
    }

    /** Walks a relative path segment by segment; "." and ".." are honored. */
    @Nullable
    private static VirtualFile walk(@Nullable VirtualFile base, String relativePath) {
        VirtualFile current = base;
        for (String segment : relativePath.split("/")) {
            if (current == null) {
                return null;
            }
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                current = current.getParent();
                continue;
            }
            current = current.findChild(segment);
        }
        return current;
    }

    private static boolean isFile(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory();
    }
}
