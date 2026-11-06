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
                || !settingsComponent.getDisabledSections().equals(settings.disabledSections)
                || settingsComponent.getAutoUpdateMinChars() != settings.autoUpdateMinChars
                || settingsComponent.isRefreshOnSave() != settings.refreshOnSave;
    }

    @Override
    public void apply() {
        GreppaSettingsState settings = GreppaSettingsState.getInstance();
        settings.llmBaseUrl = settingsComponent.getLlmBaseUrl();
        settings.llmModel = settingsComponent.getLlmModel();
        settings.setLlmApiKey(settingsComponent.getLlmApiKey());
        settings.guideline = settingsComponent.getGuideline();
        settings.disabledSections = settingsComponent.getDisabledSections();
        settings.autoUpdateMinChars = settingsComponent.getAutoUpdateMinChars();
        settings.refreshOnSave = settingsComponent.isRefreshOnSave();
    }

    @Override
    public void reset() {
        GreppaSettingsState settings = GreppaSettingsState.getInstance();
        settingsComponent.setLlmBaseUrl(settings.llmBaseUrl);
        settingsComponent.setLlmModel(settings.llmModel);
        settingsComponent.setLlmApiKey(settings.getLlmApiKey());
        settingsComponent.setGuideline(settings.guideline);
        settingsComponent.setDisabledSections(settings.disabledSections);
        // Math.max guards a hand-edited negative value in the XML: the
        // spinner's model rejects values below 0.
        settingsComponent.setAutoUpdateMinChars(Math.max(0, settings.autoUpdateMinChars));
        settingsComponent.setRefreshOnSave(settings.refreshOnSave);
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}
