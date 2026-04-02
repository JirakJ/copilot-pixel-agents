package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.jirakj.pixelagents.Constants
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Service(Service.Level.APP)
class ConfigPersistenceService : Disposable {

    companion object {
        private val LOG = Logger.getInstance(ConfigPersistenceService::class.java)

        fun getInstance(): ConfigPersistenceService =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ConfigPersistenceService::class.java)
    }

    private val configDir = File(System.getProperty("user.home"), Constants.LAYOUT_FILE_DIR)
    private val configFile = File(configDir, Constants.CONFIG_FILE_NAME)

    data class Config(
        val externalAssetDirectories: List<String> = emptyList()
    )

    fun readConfig(): Config {
        if (!configFile.exists()) return Config()
        return try {
            val json = configFile.readText()
            com.google.gson.Gson().fromJson(json, Config::class.java) ?: Config()
        } catch (e: Exception) {
            LOG.warn("Failed to read config: ${e.message}")
            Config()
        }
    }

    fun writeConfig(config: Config) {
        try {
            configDir.mkdirs()
            val tmpFile = File(configDir, "${Constants.CONFIG_FILE_NAME}.tmp")
            tmpFile.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config))
            Files.move(tmpFile.toPath(), configFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            LOG.error("Failed to write config: ${e.message}", e)
        }
    }

    override fun dispose() {}
}
