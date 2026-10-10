package com.example.antiviber

import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project

/**
 * Registers project-level listeners when the project is opened.
 * Plugin.xml listener registration for FileEditorManagerListener is not
 * always reliable across IntelliJ versions, so we subscribe programmatically.
 */
class AntiviberProjectComponent(private val project: Project) : ProjectComponent {

    private val LOG = Logger.getInstance(AntiviberProjectComponent::class.java)

    override fun projectOpened() {
        LOG.info("AntiviberProjectComponent: project opened, registering listeners")
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            LspTabSelectionListener()
        )
        HintsService.initForProject(project)
    }

    override fun projectClosed() {
        LOG.info("AntiviberProjectComponent: project closed")
    }
}
