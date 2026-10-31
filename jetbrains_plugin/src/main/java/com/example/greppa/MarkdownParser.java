package com.example.greppa;

import org.intellij.markdown.ast.ASTNode;
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor;
import org.intellij.markdown.html.HtmlGenerator;

import java.awt.Color;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the Markdown analysis document produced by the LSP server into
 * HTML for the tool window, using JetBrains' own Markdown parser library
 * (org.intellij.markdown, bundled with the IDE - the same parser the IDE's
 * Markdown support uses).
 *
 * The document is rendered as-is: the library builds the AST and generates
 * the HTML, and the JTextPane's HTMLEditorKit displays it with the style
 * sheet configured in HintsPanel. Three presentation-only adjustments are
 * made on top, because neither Markdown nor Swing's HTML renderer can
 * express them:
 * - the "(file-level)" and "(segment-level)" suffixes of the section
 *   headings are stripped (see stripLevelSuffixes),
 * - the two "Suggested actions" category headings, "Critical for
 *   understanding" and "Automatable", are wrapped in colored font tags
 *   (see colorizeCategories), using the colors passed in by HintsPanel so
 *   they follow the IDE theme, and
 * - code blocks are rewritten from pre elements into ordinary block
 *   content (see wrapCodeBlocks), because Swing's HTML renderer never
 *   wraps preformatted content, which made code lines wider than the tool
 *   window give the panel a horizontal scroll bar. Inside the rewritten
 *   lines, runs of multiple spaces are kept as non-breaking entities so
 *   the columns of the ASCII flowchart of the analysis stay aligned,
 *   while single spaces stay breakable so long code lines still wrap
 *   (see preserveWhitespace). Every rewritten block also gets a "Copy"
 *   button at its top right that copies the block's plain text to the
 *   clipboard (see COPY_LINK_PREFIX); the plain text is collected during
 *   the rewrite and handed to the caller through toHtml's codeBlockSink
 *   parameter, so HintsPanel can serve the clicks.
 *
 * Constraints that shaped this implementation:
 * - The library's parser class is referenced by its fully qualified name
 *   because this class is itself named MarkdownParser: a single-type import
 *   may not import a type whose simple name is also declared as a top-level
 *   class in the same compilation unit ("MarkdownParser is already defined
 *   in this compilation unit").
 * - The flavour is used through its concrete type GFMFlavourDescriptor
 *   because that is the type the IDE's bundled copy of the library
 *   resolves.
 * - The library's generateHtml() has no Java-visible no-argument overload:
 *   its TagRenderer parameter is a Kotlin default parameter, which javac
 *   cannot see (the same reason the 3-argument HtmlGenerator constructor was
 *   not visible). A renderer must therefore be passed explicitly.
 * - TagRenderer is a nested interface of HtmlGenerator, not a top-level
 *   class: the compiler rejected the top-level import
 *   org.intellij.markdown.html.TagRenderer, yet it resolved the
 *   generateHtml(TagRenderer) overload, so the type is visible from
 *   HtmlGenerator itself. The bundled library version also does not expose
 *   its default renderer class to Java (org.intellij.markdown.html.SimpleTagRenderer
 *   and HtmlGenerator.SimpleTagRenderer were both rejected with "cannot
 *   find symbol"), so the renderer below is this plugin's own
 *   implementation of the nested interface.
 */
public final class MarkdownParser {

    private MarkdownParser() {
    }

    /**
     * A complete pre block: the opening tag (optionally with attributes),
     * its content and the closing tag. Code blocks cannot nest, so the
     * content match stops at the first closing tag.
     */
    private static final Pattern PRE_BLOCK =
            Pattern.compile("<pre(?:\\s[^>]*)?>(.*?)</pre>", Pattern.DOTALL);

    /**
     * Prefix of the synthetic links that copy a code block to the
     * clipboard. The full href is greppa://copy/N, where N is the
     * 0-based index of the code block in document order. MarkdownParser
     * embeds one such link as the "Copy" button at the top right of each
     * code block and collects each block's plain text into the
     * codeBlockSink list of toHtml; HintsPanel keeps that list and serves
     * the clicks by copying the indexed entry.
     */
    public static final String COPY_LINK_PREFIX = "greppa://copy/";

    /**
     * Converts a Markdown document to a full HTML document for a JTextPane.
     * The GFM flavour is used so tables and strikethrough render correctly
     * in addition to the common Markdown subset. The two colors are used
     * for the "Critical for understanding" and "Automatable" category
     * headings of the Suggested actions section and are passed in by the
     * caller (HintsPanel) so they follow the IDE theme.
     *
     * The plain-text content of every code block, in document order, is
     * added to codeBlockSink. The "Copy" link embedded above each code
     * block references its entry by index, so the caller can serve the
     * link clicks; see COPY_LINK_PREFIX.
     */
    public static String toHtml(String markdown, Color criticalColor, Color automatableColor, List<String> codeBlockSink) {
        String source = stripLevelSuffixes(markdown == null ? "" : markdown);
        GFMFlavourDescriptor flavour = new GFMFlavourDescriptor();
        ASTNode tree = new org.intellij.markdown.parser.MarkdownParser(flavour)
                .buildMarkdownTreeFromString(source);
        String body = new HtmlGenerator(source, tree, flavour, false)
                .generateHtml(new SimpleTagRenderer());
        return "<html><body>"
                + colorizeCategories(wrapCodeBlocks(body, codeBlockSink), criticalColor, automatableColor)
                + "</body></html>";
    }

    /**
     * Removes the "(file-level)" and "(segment-level)" suffixes that older
     * analysis prompts asked the model to append to the section headings.
     * They add no information for the reader, so they are stripped before
     * rendering.
     *
     * Only lines that start with '#' are touched, so the same words inside
     * code snippets or body text are left alone.
     */
    private static String stripLevelSuffixes(String markdown) {
        if (!markdown.contains("(file-level)") && !markdown.contains("(segment-level)")) {
            return markdown;
        }
        StringBuilder result = new StringBuilder(markdown.length());
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#")) {
                line = line.replace(" (file-level)", "").replace(" (segment-level)", "");
            }
            result.append(line);
            if (i < lines.length - 1) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    /**
     * Wraps the two "Suggested actions" category headings in colored font
     * tags: "Critical for understanding" in the critical color (red),
     * "Automatable" in the automatable color (green). Markdown has no
     * color syntax, so this is done on the generated HTML.
     *
     * The colors are emitted as font color attributes - the HTML 3.2 form
     * that Swing's built-in HTML renderer fully supports - instead of CSS
     * classes, because Swing's CSS engine is too limited to rely on: it
     * does not even understand the rgb() notation, which is why the
     * earlier style-sheet colors never showed up.
     *
     * The match is anchored on the tag delimiters (">Text<"), so only the
     * exact heading text content is colored - the strings the analysis
     * prompt mandates - and never the same words inside body text or code
     * snippets. If the model deviates from the mandated heading text, the
     * heading simply renders uncolored.
     */
    private static String colorizeCategories(String html, Color critical, Color automatable) {
        return html
                .replace(">Critical for understanding<",
                        ">" + categoryHtml("Critical for understanding", critical) + "<")
                .replace(">Automatable<",
                        ">" + categoryHtml("Automatable", automatable) + "<");
    }

    private static String categoryHtml(String label, Color color) {
        return "<font color=\"" + toHex(color) + "\">" + label + "</font>";
    }

    /**
     * Rewrites every pre block into a div whose lines are separated by br
     * tags. Swing's HTML renderer lays preformatted content out in a view
     * that never breaks lines, so a code line wider than the tool window
     * made the whole document that wide and gave the panel a horizontal
     * scroll bar. As ordinary block content the same code wraps at the
     * viewport width like the rest of the analysis, which is the expected
     * behaviour. Swing's HTML views have no white-space support that could
     * make pre itself wrap, so the element is replaced instead of styled;
     * the div carries the code-block class that HintsPanel's style sheet
     * styles the way it styled pre before.
     *
     * Every rewritten block opens with a right-aligned row holding the
     * "Copy" button (see COPY_LINK_PREFIX), and the block's plain text is
     * added to codeBlockSink in document order, so each button's index
     * matches the sink entry it copies. The button is a link dressed up by
     * HintsPanel's style sheet (class copy-button inside a copy-row div):
     * Swing's HTML renderer supports neither absolute positioning nor
     * floats, so the button cannot overlay the code GitHub-style - a
     * right-aligned row at the block's top is the closest it can get. The
     * nbsp entities around the label pad it horizontally, because Swing's
     * CSS has no padding support for inline elements.
     */
    private static String wrapCodeBlocks(String html, List<String> codeBlockSink) {
        Matcher matcher = PRE_BLOCK.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        StringBuilder result = new StringBuilder(html.length() + 64);
        int last = 0;
        do {
            result.append(html, last, matcher.start());
            String content = matcher.group(1);
            // The plain text is collected before the link is emitted, so
            // the link's index always matches the sink entry.
            codeBlockSink.add(plainTextOfCodeBlock(content));
            result.append("<div class=\"code-block\">");
            result.append("<div class=\"copy-row\"><a class=\"copy-button\" href=\"")
                    .append(COPY_LINK_PREFIX)
                    .append(codeBlockSink.size() - 1)
                    .append("\">&nbsp;Copy&nbsp;</a></div>");
            result.append(wrapCodeBlockLines(content));
            result.append("</div>");
            last = matcher.end();
        } while (matcher.find());
        result.append(html, last, html.length());
        return result.toString();
    }

    /**
     * Splits the content of one code block into lines and joins them with
     * br tags, keeping each line's indentation and interior space runs
     * intact (see preserveWhitespace).
     */
    private static String wrapCodeBlockLines(String content) {
        String normalized = content.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder(normalized.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append("<br>");
            }
            result.append(preserveWhitespace(lines[i]));
        }
        return result.toString();
    }

    /**
     * Returns the plain-text content of one code block: the raw pre
     * content with the library's code wrapper tags stripped and the HTML
     * entities unescaped, so the clipboard receives the code exactly as
     * the model wrote it. Derived from the raw pre content before
     * preserveWhitespace rewrites spaces as nbsp entities, so the
     * original spacing survives. Trailing newlines are dropped: the
     * library ends the block's last line with a newline before the
     * closing tag, and a pasted snippet should not end with blank lines.
     */
    private static String plainTextOfCodeBlock(String preContent) {
        String content = preContent;
        // The library wraps the block in a code element; strip both tags.
        // Angle brackets in the code itself are escaped, so a literal
        // "<code" or "</code>" can only be the wrapper.
        if (content.startsWith("<code")) {
            int tagEnd = content.indexOf('>');
            if (tagEnd >= 0) {
                content = content.substring(tagEnd + 1);
            }
        }
        if (content.endsWith("</code>")) {
            content = content.substring(0, content.length() - "</code>".length());
        }
        content = content.replace("\r\n", "\n");
        // Unescape the entities the library emits; &amp; goes last so that
        // entities the code itself contains (escaped by the library)
        // round-trip back to their literal text.
        content = content
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        while (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }
        return content;
    }

    /**
     * Replaces the whitespace of one code line with nbsp entities so it
     * survives HTML whitespace collapsing, which Swing's HTML view applies
     * to all ordinary whitespace. Two kinds of whitespace are preserved:
     * the line's leading indentation in full, and every run of two or more
     * spaces (or any tab) inside the line. Those interior runs are what
     * aligns the columns of the ASCII flowchart the analysis prompt asks
     * for; collapsing them to a single space misaligned the diagram's boxes
     * and arrows even when the model drew them perfectly. A single space
     * inside the line is left as an ordinary space: single spaces double
     * as the break opportunities the renderer wraps long code lines at,
     * and making them non-breaking too would bring back the horizontal
     * scroll bar the pre-to-div rewrite removed. A tab becomes four nbsp
     * entities, the width it is conventionally shown with.
     *
     * The library opens a code block with a code tag on the same line as
     * the first code line, so a leading tag is skipped before the
     * whitespace is replaced. Everything inside a code block is
     * HTML-escaped, so a raw '<' can only start that wrapper tag.
     */
    private static String preserveWhitespace(String line) {
        int start = 0;
        if (line.startsWith("<")) {
            int tagEnd = line.indexOf('>');
            if (tagEnd < 0) {
                return line;
            }
            start = tagEnd + 1;
        }
        StringBuilder result = new StringBuilder(line.length() + 32);
        result.append(line, 0, start);

        int i = start;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                result.append(c);
                i++;
                continue;
            }
            int runEnd = i;
            while (runEnd < line.length()
                    && (line.charAt(runEnd) == ' ' || line.charAt(runEnd) == '\t')) {
                runEnd++;
            }
            // The leading indentation is preserved in full; inside the line
            // only runs of more than one whitespace character (or a tab)
            // are, so a single space stays a break opportunity.
            boolean preserve = i == start || runEnd - i > 1 || line.charAt(i) == '\t';
            if (preserve) {
                for (int j = i; j < runEnd; j++) {
                    result.append(line.charAt(j) == '\t' ? "&nbsp;&nbsp;&nbsp;&nbsp;" : "&nbsp;");
                }
            } else {
                result.append(' ');
            }
            i = runEnd;
        }
        return result.toString();
    }

    /**
     * Formats a color as #rrggbb. Swing's CSS engine and its font tag
     * support both accept this notation, unlike rgb().
     */
    private static String toHex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }

    /**
     * TagRenderer implementation reproducing the library's default
     * renderer, which the bundled library version does not expose to Java.
     *
     * The parameter types match the library's Kotlin interface exactly as
     * seen from Java: CharSequence (not String) for tag names, the vararg
     * attributes as a CharSequence array followed by the autoClose flag.
     * Java requires exact parameter matches when implementing an interface,
     * and the library's own generateHtml(Function3) overload confirms that
     * tag names are CharSequence throughout this API.
     *
     * openTag receives already-quoted attribute strings (e.g. href="..."),
     * which are joined with spaces; printHtml passes content through
     * unchanged because the library performs its own text escaping and
     * Markdown inline HTML must survive unmodified.
     */
    private static final class SimpleTagRenderer implements HtmlGenerator.TagRenderer {

        @Override
        public CharSequence openTag(ASTNode node, CharSequence tagName, CharSequence[] attributes, boolean autoClose) {
            StringBuilder builder = new StringBuilder();
            builder.append('<').append(tagName);
            for (CharSequence attribute : attributes) {
                builder.append(' ').append(attribute);
            }
            builder.append(autoClose ? "/>" : ">");
            return builder;
        }

        @Override
        public CharSequence closeTag(CharSequence tagName) {
            return "</" + tagName + ">";
        }

        @Override
        public CharSequence printHtml(CharSequence html) {
            return html;
        }
    }
}
