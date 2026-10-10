package com.example.antiviber;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class AntiviberSettingsConfigurable implements Configurable {

    private AntiviberSettingsComponent settingsComponent;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Antiviber LSP Server";
    }

    @Override
    public @Nullable JComponent createComponent() {
        settingsComponent = new AntiviberSettingsComponent();
        return settingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        AntiviberSettingsState settings = AntiviberSettingsState.getInstance();
        return !settingsComponent.getLspServerPath().equals(settings.lspServerPath)
                || !settingsComponent.getLlmBaseUrl().equals(settings.llmBaseUrl)
                || !settingsComponent.getLlmModel().equals(settings.llmModel)
                || !settingsComponent.getLlmApiKey().equals(settings.getLlmApiKey())
                || !settingsComponent.getGuideline().equals(settings.guideline)
                || settingsComponent.getAutoUpdateMinChars() != settings.autoUpdateMinChars;
    }

    @Override
    public void apply() {
        AntiviberSettingsState settings = AntiviberSettingsState.getInstance();
        settings.lspServerPath = settingsComponent.getLspServerPath();
        settings.llmBaseUrl = settingsComponent.getLlmBaseUrl();
        settings.llmModel = settingsComponent.getLlmModel();
        settings.setLlmApiKey(settingsComponent.getLlmApiKey());
        settings.guideline = settingsComponent.getGuideline();
        settings.autoUpdateMinChars = settingsComponent.getAutoUpdateMinChars();
    }

    @Override
    public void reset() {
        AntiviberSettingsState settings = AntiviberSettingsState.getInstance();
        settingsComponent.setLspServerPath(settings.lspServerPath);
        settingsComponent.setLlmBaseUrl(settings.llmBaseUrl);
        settingsComponent.setLlmModel(settings.llmModel);
        settingsComponent.setLlmApiKey(settings.getLlmApiKey());
        settingsComponent.setGuideline(settings.guideline);
        // Math.max guards a hand-edited negative value in the XML: the
        // spinner's model rejects values below 0.
        settingsComponent.setAutoUpdateMinChars(Math.max(0, settings.autoUpdateMinChars));
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}
