package com.perfah.greppa;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class GreppaSettingsConfigurable implements Configurable {

    private GreppaSettingsComponent settingsComponent;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Greppa";
    }

    @Override
    public @Nullable JComponent createComponent() {
        settingsComponent = new GreppaSettingsComponent();
        return settingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        GreppaSettingsState settings = GreppaSettingsState.getInstance();
        return !settingsComponent.getLlmBaseUrl().equals(settings.llmBaseUrl)
                || !settingsComponent.getLlmModel().equals(settings.llmModel)
                || !settingsComponent.getLlmApiKey().equals(settings.getLlmApiKey())
                || !settingsComponent.getGuideline().equals(settings.guideline)
                || settingsComponent.getLlmMaxTokens() != settings.llmMaxTokens
                || !settingsComponent.getDisabledSections().equals(settings.disabledSections)
                || settingsComponent.getAutoUpdateMinChars() != settings.autoUpdateMinChars
                || settingsComponent.isRefreshOnOpen() != settings.refreshOnOpen
                || settingsComponent.isRefreshOnSave() != settings.refreshOnSave
                || settingsComponent.isShowEtcTab() != settings.showEtcTab;
    }

    @Override
    public void apply() {
        GreppaSettingsState settings = GreppaSettingsState.getInstance();
        settings.llmBaseUrl = settingsComponent.getLlmBaseUrl();
        settings.llmModel = settingsComponent.getLlmModel();
        settings.setLlmApiKey(settingsComponent.getLlmApiKey());
        settings.guideline = settingsComponent.getGuideline();
        settings.llmMaxTokens = settingsComponent.getLlmMaxTokens();
        settings.disabledSections = settingsComponent.getDisabledSections();
        settings.autoUpdateMinChars = settingsComponent.getAutoUpdateMinChars();
        settings.refreshOnOpen = settingsComponent.isRefreshOnOpen();
        settings.refreshOnSave = settingsComponent.isRefreshOnSave();
        settings.showEtcTab = settingsComponent.isShowEtcTab();
    }

    @Override
    public void reset() {
        GreppaSettingsState settings = GreppaSettingsState.getInstance();
        settingsComponent.setLlmBaseUrl(settings.llmBaseUrl);
        settingsComponent.setLlmModel(settings.llmModel);
        settingsComponent.setLlmApiKey(settings.getLlmApiKey());
        settingsComponent.setGuideline(settings.guideline);
        // A hand-edited non-positive value in the XML means "leave the
        // variable unset" (see GreppaSettingsState.llmMaxTokens), which
        // applies the server's built-in default of 16384; the spinner's
        // model starts at 1, so that default is shown instead.
        settingsComponent.setLlmMaxTokens(settings.llmMaxTokens > 0 ? settings.llmMaxTokens : 16384);
        settingsComponent.setDisabledSections(settings.disabledSections);
        // Math.max guards a hand-edited negative value in the XML: the
        // spinner's model rejects values below 0.
        settingsComponent.setAutoUpdateMinChars(Math.max(0, settings.autoUpdateMinChars));
        settingsComponent.setRefreshOnOpen(settings.refreshOnOpen);
        settingsComponent.setRefreshOnSave(settings.refreshOnSave);
        settingsComponent.setShowEtcTab(settings.showEtcTab);
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}
