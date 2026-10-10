package com.example.antiviber;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupElementWeigher;
import org.jetbrains.annotations.NotNull;

/**
 * Ranks the Antiviber LSP server's completion item to the top of the
 * completion popup.
 *
 * Why a weigher is needed: the Rust server already marks its single
 * completion item with preselect and sortText "0" (see main.rs), but this
 * IDE's LSP client ignores both fields - it merges LSP items into the
 * ordinary completion pipeline, where the platform's built-in weighers
 * decide the ranking. The lookup.elementWeigher extension point is the
 * platform's sanctioned way for a plugin to influence that ranking, and
 * it works on the lookup elements after the LSP client has created them,
 * so it needs no LSP API at all (an earlier getLspCompletionSupport
 * override did not compile against this IDE build).
 *
 * How the Antiviber item is recognized: the Rust server sets the item's
 * detail to "Antiviber suggestion", and the LSP client renders that text
 * into the popup row. The weigher renders every element into a fresh
 * LookupElementPresentation and compares that text, because the lookup
 * string itself - the first line of the inserted code - contains nothing
 * Antiviber-specific, and no user data can be attached to the element
 * (the platform creates the elements internally). Both the type text and
 * the tail text are compared, so the check does not depend on which of
 * the two the LSP client uses for the detail. Every other element weighs
 * the same constant, so the relative order of the IDE's own completions
 * is untouched.
 *
 * Java, not Kotlin: the Maven build compiles only src/main/java (the pom
 * configures no Kotlin compiler), so the class must be Java to end up in
 * the plugin jar.
 */
public final class AntiviberCompletionWeigher extends LookupElementWeigher {

    /** The detail text the Rust server sets on its completion item (main.rs). */
    private static final String ANTIVIBER_DETAIL = "Antiviber suggestion";

    public AntiviberCompletionWeigher() {
        // id, dependsOnReplacableText, negated. The weight does not depend
        // on the prefix typed so far, so false lets the platform cache the
        // weights for the lifetime of one lookup; the second false keeps
        // the comparison uninverted, so the lower value (0) sorts to the
        // top.
        super("antiviberCompletion", false, false);
    }

    @Override
    public Comparable weigh(@NotNull LookupElement element) {
        LookupElementPresentation presentation = new LookupElementPresentation();
        element.renderElement(presentation);
        boolean isAntiviber = ANTIVIBER_DETAIL.equals(presentation.getTypeText())
                || ANTIVIBER_DETAIL.equals(presentation.getTailText());
        return isAntiviber ? 0 : 1;
    }
}
