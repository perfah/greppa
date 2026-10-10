package com.example.antiviber

import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.project.Project

/**
 * No longer used. The tab-selection listener is registered via
 * AntiviberProjectManagerListener on the ProjectManagerListener topic.
 * This class is kept only to avoid a stale/malformed file breaking compilation.
 */
class AntiviberProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Intentionally empty.
    }
}
