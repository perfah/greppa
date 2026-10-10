package com.example.antiviber

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Application-level listener that registers the tab-selection listener for
 * every opened project. This is the most general JetBrains-platform approach
 * and works in IntelliJ IDEA, RustRover, PyCharm, WebStorm, CLion, etc.
 */
class AntiviberProjectManagerListener : ProjectManagerListener {

    private val LOG = Logger.getInstance(AntiviberProjectManagerListener::class.java)

    override fun projectOpened(project: Project) {
        LOG.info("AntiviberProjectManagerListener: project opened, registering listeners for ${project.name}")
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            LspTabSelectionListener()
        )
        HintsService.initForProject(project)
    }

    override fun projectClosed(project: Project) {
        LOG.info("AntiviberProjectManagerListener: project closed for ${project.name}")
    }
}
