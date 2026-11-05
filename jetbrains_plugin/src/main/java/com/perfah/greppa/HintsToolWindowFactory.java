package com.perfah.greppa;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HintsToolWindowFactory implements ToolWindowFactory {

    private HintsFilePoller poller;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // The tabs render the same analysis document, each through its own
        // section filter (see HintsService.filterSections): Interpret shows
        // the understanding sections (Cursor, Explanation, Parts), Improve
        // the problems and recommendations, and Relate the relationships to
        // other files below the attached-files management. The Etc tab
        // shows the sections none of the three filters catches (see
        // HintsService.filterUnmatchedSections), so the headings the
        // server actually writes are visible when a tab seems to be
        // missing its section.
        List<String> interpretSections = List.of("Scope", "Cursor", "General understanding", "Current functionality", "Explanation", "Parts");
        HintsPanel interpretPanel = new HintsPanel(interpretSections);
        Content interpretContent = ContentFactory.getInstance()
                .createContent(interpretPanel, "Interpret", false);

        List<String> improveSections = List.of("Scope", "Suggested direction", "Problems", "Recommendations");
        HintsPanel improvePanel = new HintsPanel(improveSections);
        Content improveContent = ContentFactory.getInstance()
                .createContent(improvePanel, "Improve", false);

        ImportsPanel importsPanel = new ImportsPanel();
        // "Relationship", not "Relationships to other files": the analysis
        // prompt (prompts.rs) asks for the singular "## Relationship to
        // other files" heading, and the shorter name also matches a plural
        // heading in case the model drifts; no other section can match it.
        List<String> relateSections = List.of("Scope", "Relationship");
        HintsPanel relatePanel = new HintsPanel(relateSections);
        Content relateContent = ContentFactory.getInstance()
                .createContent(buildRelateTab(importsPanel, relatePanel), "Relate", false);

        // The Etc tab's filter is the complement of the three lists above,
        // derived from them so it cannot drift when a list is adjusted:
        // every ## section whose heading matches none of them appears
        // here. When the server writes a heading a tab's list does not
        // contain (the match is a substring test; see
        // HintsService.headingMatches), the section shows up in Etc
        // instead of the tab it was meant for.
        List<String> matchedSectionNames = new ArrayList<>(interpretSections);
        matchedSectionNames.addAll(improveSections);
        matchedSectionNames.addAll(relateSections);
        HintsPanel etcPanel = new HintsPanel(matchedSectionNames, true);
        Content etcContent = ContentFactory.getInstance().createContent(etcPanel, "Etc", false);

        toolWindow.getContentManager().addContent(interpretContent);
        toolWindow.getContentManager().addContent(improveContent);
        toolWindow.getContentManager().addContent(relateContent);
        //toolWindow.getContentManager().addContent(etcContent);

        // The title bar holds only the settings action. The reload action
        // was removed as redundant with the panels' Refresh button (next
        // to Pause/Unpause), which also forces a re-analysis through
        // HintsFilePoller.pollNow; the imports action was removed because
        // the Relate tab it opened is directly clickable in the tab strip.
        List<AnAction> titleActions = Arrays.asList(
                new OpenGreppaSettingsAction()
        );
        toolWindow.setTitleActions(titleActions);

        // Ensure the panels refresh whenever hints are updated or the active file changes.
        // These are the single listener registrations for the panels, and the
        // references are stored so contentRemoved below removes these exact
        // instances: every panel::refresh evaluation creates a new lambda,
        // so calling removeListener(panel::refresh) there (as before)
        // removed nothing and the panels kept receiving notifications
        // forever after their content was removed. The panels do not
        // register themselves either, so each update refreshes them exactly once.
        List<Runnable> refreshListeners = List.of(
                interpretPanel::refresh,
                importsPanel::refresh,
                improvePanel::refresh,
                relatePanel::refresh,
                etcPanel::refresh);
        for (Runnable listener : refreshListeners) {
            HintsService.addListener(listener);
        }

        // Register the per-project listeners here instead of from
        // GreppaProjectManagerListener. That class hooked
        // ProjectManagerListener.projectOpened, which this IDE build no
        // longer calls (the compiler already warns it is deprecated and
        // marked for removal), so its registrations never ran: switching
        // editor tabs sent no custom/didSwitchFile notification, and the
        // panel did not refresh on tab switches either - only closing and
        // reopening a file did anything, because didOpen is driven by the
        // platform directly. createToolWindowContent runs exactly once per
        // project, which makes it the per-project initialization point:
        // initForProject refreshes the panel when the selected editor
        // changes, and LspTabSelectionListener asks the LSP server to
        // re-analyze the newly selected file.
        HintsService.initForProject(project);
        project.getMessageBus().connect().subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new LspTabSelectionListener()
        );

        poller = new HintsFilePoller();
        poller.start();

        toolWindow.getContentManager().addContentManagerListener(new com.intellij.ui.content.ContentManagerListener() {
            @Override
            public void contentRemoved(com.intellij.ui.content.ContentManagerEvent event) {
                if (poller != null) {
                    poller.stop();
                }
                for (Runnable listener : refreshListeners) {
                    HintsService.removeListener(listener);
                }
            }
        });
    }

    /**
     * Builds the Relate tab: the attached-files management (the former
     * Imports pane) above the relationships to other files markdown,
     * separated by a draggable divider instead of a fixed-height strip.
     * The divider is the IDE's standard one-pixel splitter, so it looks
     * and drags like every other divider in the IDE, and the imports
     * list takes exactly as much room as the user drags it to - a
     * sliver when the relationships are what matters, most of the tab
     * while the attached files are being managed. The imports list
     * scrolls within whatever height it gets.
     */
    private static JComponent buildRelateTab(ImportsPanel importsPanel, HintsPanel relatePanel) {
        // A vertical splitter stacks its two components with a horizontal
        // divider. The initial proportion gives the imports list about a
        // quarter of the tab, roughly the fixed 220 px it occupied before
        // the divider became draggable; dragging the divider changes it.
        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.25f);
        splitter.setFirstComponent(importsPanel);
        splitter.setSecondComponent(relatePanel);
        return splitter;
    }
}
