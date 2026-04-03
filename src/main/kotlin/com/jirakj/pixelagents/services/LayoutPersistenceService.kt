package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirakj.pixelagents.Constants
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Service(Service.Level.PROJECT)
class LayoutPersistenceService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(LayoutPersistenceService::class.java)

        fun getInstance(project: Project): LayoutPersistenceService =
            project.getService(LayoutPersistenceService::class.java)
    }

    private val layoutDir = File(System.getProperty("user.home"), Constants.LAYOUT_FILE_DIR)
    private val layoutFile = File(layoutDir, Constants.LAYOUT_FILE_NAME)

    fun readLayoutFromFile(): String? {
        if (!layoutFile.exists()) return null
        return try {
            layoutFile.readText()
        } catch (e: Exception) {
            LOG.warn("Failed to read layout: ${e.message}")
            null
        }
    }

    fun writeLayoutToFile(layoutJson: String) {
        try {
            layoutDir.mkdirs()
            val tmpFile = File(layoutDir, "${Constants.LAYOUT_FILE_NAME}.tmp")
            tmpFile.writeText(layoutJson)
            Files.move(tmpFile.toPath(), layoutFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            LOG.error("Failed to write layout: ${e.message}", e)
        }
    }

    override fun dispose() {}
}
