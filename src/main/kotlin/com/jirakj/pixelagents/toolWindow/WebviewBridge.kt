package com.jirakj.pixelagents.toolWindow

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.jirakj.pixelagents.Constants
import com.jirakj.pixelagents.services.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.io.File

class WebviewBridge(
    private val browser: JBCefBrowser,
    private val project: Project
) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(WebviewBridge::class.java)
        private val gson = Gson()
    }

    private val agentManager = AgentManagerService.getInstance(project)
    private val layoutPersistence = LayoutPersistenceService.getInstance(project)
    private val timerManager = TimerManagerService.getInstance(project)
    private val transcriptParser = TranscriptParserService.getInstance(project)
    private val fileWatcher = FileWatcherService.getInstance(project)
    private val assetLoader = AssetLoaderService.getInstance(project)
    private val configPersistence = ConfigPersistenceService.getInstance()

    private val jsQuery: JBCefJSQuery

    init {
        // Wire up services
        agentManager.setBridge(this)
        timerManager.setBridge(this)
        transcriptParser.setBridge(this)
        assetLoader.setBridge(this)
        fileWatcher.initialize(this, agentManager.agents, timerManager, transcriptParser)

        // Set up JBCefJSQuery-based communication
        jsQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)

        jsQuery.addHandler { request: String ->
            try {
                val message = gson.fromJson(request, JsonObject::class.java)
                handleWebviewMessage(message)
                JBCefJSQuery.Response("")
            } catch (e: Exception) {
                LOG.error("Error handling webview message: ${e.message}", e)
                JBCefJSQuery.Response(null, 0, e.message ?: "Unknown error")
            }
        }

        // Inject the cefQuery function after page load
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    val jsFunction = jsQuery.inject("request")
                    cefBrowser.executeJavaScript(
                        """
                        window.cefQuery = function(params) {
                            var request = params.request;
                            $jsFunction
                        };
                        """.trimIndent(),
                        "", 0
                    )
                }
            }
        }, browser.cefBrowser)

        LOG.info("WebviewBridge initialized for project: ${project.name}")
    }

    fun postMessage(message: Any) {
        val json = gson.toJson(message)
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if (window.__onExtensionMessage) { window.__onExtensionMessage($json); }",
                "",
                0
            )
        }
    }

    fun postMessage(type: String, data: Map<String, Any?> = emptyMap()) {
        val message = mutableMapOf<String, Any?>("type" to type)
        message.putAll(data)
        postMessage(message as Any)
    }

    private fun handleWebviewMessage(message: JsonObject) {
        val type = message.get("type")?.asString ?: return
        LOG.info("Received webview message: $type")

        when (type) {
            "webviewReady" -> onWebviewReady()
            "openClaude" -> onOpenClaude(message)
            "focusAgent" -> onFocusAgent(message)
            "closeAgent" -> onCloseAgent(message)
            "saveLayout" -> onSaveLayout(message)
            "saveAgentSeats" -> onSaveAgentSeats(message)
            "setSoundEnabled" -> onSetSoundEnabled(message)
            "setLastSeenVersion" -> onSetLastSeenVersion(message)
            "setAlwaysShowLabels" -> onSetAlwaysShowLabels(message)
            "setWatchAllSessions" -> onSetWatchAllSessions(message)
            "requestDiagnostics" -> onRequestDiagnostics()
            "openSessionsFolder" -> onOpenSessionsFolder()
            "exportLayout" -> onExportLayout()
            "importLayout" -> onImportLayout()
            "addExternalAssetDirectory" -> onAddExternalAssetDirectory()
            "removeExternalAssetDirectory" -> onRemoveExternalAssetDirectory(message)
            else -> LOG.warn("Unknown webview message type: $type")
        }
    }

    private fun onWebviewReady() {
        LOG.info("Webview ready — sending settings and assets")

        // Send settings
        val globalProps = PropertiesComponent.getInstance()
        val soundEnabled = globalProps.getBoolean(Constants.KEY_SOUND_ENABLED, true)
        val lastSeenVersion = globalProps.getValue(Constants.KEY_LAST_SEEN_VERSION, "")
        val alwaysShowLabels = globalProps.getBoolean(Constants.KEY_ALWAYS_SHOW_LABELS, false)
        val watchAllSessions = globalProps.getBoolean(Constants.KEY_WATCH_ALL_SESSIONS, false)
        val config = configPersistence.readConfig()

        postMessage("settingsLoaded", mapOf(
            "soundEnabled" to soundEnabled,
            "lastSeenVersion" to lastSeenVersion,
            "extensionVersion" to "0.1.0",
            "alwaysShowLabels" to alwaysShowLabels,
            "watchAllSessions" to watchAllSessions,
            "externalAssetDirectories" to config.externalAssetDirectories
        ))

        // Load and send assets in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            assetLoader.loadAndSendAllAssets()

            // Send layout
            val layoutJson = layoutPersistence.readLayoutFromFile()
            if (layoutJson != null) {
                try {
                    val layout = gson.fromJson(layoutJson, Any::class.java)
                    postMessage("layoutLoaded", mapOf("layout" to layout, "wasReset" to false))
                } catch (e: Exception) {
                    LOG.warn("Failed to parse layout: ${e.message}")
                    sendDefaultLayout()
                }
            } else {
                sendDefaultLayout()
            }

            // Restore and send existing agents
            agentManager.restoreAgents()
            agentManager.sendExistingAgents()
        }
    }

    private fun sendDefaultLayout() {
        val defaultLayout = assetLoader.loadDefaultLayout()
        if (defaultLayout != null) {
            layoutPersistence.writeLayoutToFile(defaultLayout)
            val layout = gson.fromJson(defaultLayout, Any::class.java)
            postMessage("layoutLoaded", mapOf("layout" to layout, "wasReset" to false))
        }
    }

    private fun onOpenClaude(message: JsonObject) {
        val folderPath = message.get("folderPath")?.asString
        val bypassPermissions = message.get("bypassPermissions")?.asBoolean ?: false
        agentManager.launchNewAgent(folderPath, bypassPermissions)
    }

    private fun onFocusAgent(message: JsonObject) {
        val id = message.get("id")?.asInt ?: return
        // Focus the terminal associated with this agent
        LOG.info("Focus agent $id requested")
    }

    private fun onCloseAgent(message: JsonObject) {
        val id = message.get("id")?.asInt ?: return
        agentManager.removeAgent(id)
    }

    private fun onSaveLayout(message: JsonObject) {
        val layout = message.get("layout")
        if (layout != null) {
            layoutPersistence.writeLayoutToFile(gson.toJson(layout))
        }
    }

    private fun onSaveAgentSeats(message: JsonObject) {
        val seats = message.get("seats")
        if (seats != null) {
            PropertiesComponent.getInstance(project)
                .setValue(Constants.KEY_AGENT_SEATS, gson.toJson(seats))
        }
    }

    private fun onSetSoundEnabled(message: JsonObject) {
        val enabled = message.get("enabled")?.asBoolean ?: return
        PropertiesComponent.getInstance().setValue(Constants.KEY_SOUND_ENABLED, enabled)
    }

    private fun onSetLastSeenVersion(message: JsonObject) {
        val version = message.get("version")?.asString ?: return
        PropertiesComponent.getInstance().setValue(Constants.KEY_LAST_SEEN_VERSION, version)
    }

    private fun onSetAlwaysShowLabels(message: JsonObject) {
        val enabled = message.get("enabled")?.asBoolean ?: return
        PropertiesComponent.getInstance().setValue(Constants.KEY_ALWAYS_SHOW_LABELS, enabled)
    }

    private fun onSetWatchAllSessions(message: JsonObject) {
        val enabled = message.get("enabled")?.asBoolean ?: return
        PropertiesComponent.getInstance().setValue(Constants.KEY_WATCH_ALL_SESSIONS, enabled)
    }

    private fun onRequestDiagnostics() {
        val diagnostics = agentManager.agents.values.map { agent ->
            mapOf(
                "id" to agent.id,
                "isExternal" to agent.isExternal,
                "jsonlFile" to agent.jsonlFile,
                "fileOffset" to agent.fileOffset,
                "linesProcessed" to agent.linesProcessed,
                "lastDataAt" to agent.lastDataAt
            )
        }
        postMessage("agentDiagnostics", mapOf("agents" to diagnostics))
    }

    private fun onOpenSessionsFolder() {
        val homeDir = System.getProperty("user.home")
        val sessionsDir = File("$homeDir/.claude/projects")
        if (sessionsDir.exists()) {
            com.intellij.ide.BrowserUtil.browse(sessionsDir.toURI())
        }
    }

    private fun onExportLayout() {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
            val layoutJson = layoutPersistence.readLayoutFromFile() ?: return@invokeLater
            // Use a file chooser to pick destination, then write
            val files = chooser.choose(project)
            if (files.isNotEmpty()) {
                File(files[0].path).writeText(layoutJson)
            }
        }
    }

    private fun onImportLayout() {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
            val files = chooser.choose(project)
            if (files.isNotEmpty()) {
                val file = File(files[0].path)
                try {
                    val content = file.readText()
                    val json = gson.fromJson(content, JsonObject::class.java)
                    if (json.get("version")?.asInt == 1 && json.has("tiles")) {
                        layoutPersistence.writeLayoutToFile(content)
                        val layout = gson.fromJson(content, Any::class.java)
                        postMessage("layoutLoaded", mapOf("layout" to layout, "wasReset" to false))
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to import layout: ${e.message}")
                }
            }
        }
    }

    private fun onAddExternalAssetDirectory() {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
            val files = chooser.choose(project)
            if (files.isNotEmpty()) {
                val dirPath = files[0].path
                val config = configPersistence.readConfig()
                if (dirPath !in config.externalAssetDirectories) {
                    val updated = config.copy(
                        externalAssetDirectories = config.externalAssetDirectories + dirPath
                    )
                    configPersistence.writeConfig(updated)
                    postMessage("externalAssetDirectoriesUpdated", mapOf("dirs" to updated.externalAssetDirectories))
                    // Reload furniture assets
                    ApplicationManager.getApplication().executeOnPooledThread {
                        assetLoader.loadAndSendAllAssets()
                    }
                }
            }
        }
    }

    private fun onRemoveExternalAssetDirectory(message: JsonObject) {
        val dirPath = message.get("path")?.asString ?: return
        val config = configPersistence.readConfig()
        val updated = config.copy(
            externalAssetDirectories = config.externalAssetDirectories.filter { it != dirPath }
        )
        configPersistence.writeConfig(updated)
        postMessage("externalAssetDirectoriesUpdated", mapOf("dirs" to updated.externalAssetDirectories))
        ApplicationManager.getApplication().executeOnPooledThread {
            assetLoader.loadAndSendAllAssets()
        }
    }

    override fun dispose() {
        jsQuery.dispose()
        LOG.info("WebviewBridge disposed")
    }
}
