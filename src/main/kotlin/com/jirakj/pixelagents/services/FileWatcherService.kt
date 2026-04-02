package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class FileWatcherService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(FileWatcherService::class.java)

        fun getInstance(project: Project): FileWatcherService =
            project.getService(FileWatcherService::class.java)
    }

    // TODO: Implement JSONL file monitoring
    // - Hybrid VFS listener + polling backup (2s interval)
    // - readNewLines(): Parse JSONL incrementally with partial line buffering
    // - startFileWatching(): Set up watcher per agent
    // - External session detection (IDE terminal sessions)
    // - /clear detection via project-level scan

    override fun dispose() {
        LOG.info("FileWatcherService disposed for project: ${project.name}")
    }
}
