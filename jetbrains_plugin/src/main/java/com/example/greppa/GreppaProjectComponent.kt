package com.example.greppa

import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project

/**
 * Registers project-level listeners when the project is opened.
 * Plugin.xml listener registration for FileEditorManagerListener is not
 * always reliable across IntelliJ versions, so we subscribe programmatically.
 */
class GreppaProjectComponent(private val project: Project) : ProjectComponent {

    private val LOG = Logger.getInstance(GreppaProjectComponent::class.java)

    override fun projectOpened() {
        LOG.info("GreppaProjectComponent: project opened, registering listeners")
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            LspTabSelectionListener()
        )
        HintsService.initForProject(project)
    }

    override fun projectClosed() {
        LOG.info("GreppaProjectComponent: project closed")
    }
}
