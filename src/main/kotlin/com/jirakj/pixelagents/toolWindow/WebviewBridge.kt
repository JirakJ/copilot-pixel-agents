package com.jirakj.pixelagents.toolWindow

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.CefSettings
import com.intellij.openapi.application.ApplicationManager

class WebviewBridge(
    private val browser: JBCefBrowser,
    private val project: Project
) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(WebviewBridge::class.java)
        private val gson = Gson()
    }

    private val messageHandler = object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: org.cef.callback.CefQueryCallback
        ): Boolean {
            try {
                val message = gson.fromJson(request, JsonObject::class.java)
                handleWebviewMessage(message)
                callback.success("")
            } catch (e: Exception) {
                LOG.error("Error handling webview message: ${e.message}", e)
                callback.failure(0, e.message ?: "Unknown error")
            }
            return true
        }
    }

    init {
        val config = org.cef.CefApp.getInstance().createClient()
        // Message router will be set up after JCEF initialization
        LOG.info("WebviewBridge initialized for project: ${project.name}")
    }

    /**
     * Send a message from Kotlin to the webview (equivalent to VS Code webview.postMessage)
     */
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

    /**
     * Send a typed message with type field
     */
    fun postMessage(type: String, data: Map<String, Any?> = emptyMap()) {
        val message = mutableMapOf<String, Any?>("type" to type)
        message.putAll(data)
        postMessage(message as Any)
    }

    private fun handleWebviewMessage(message: JsonObject) {
        val type = message.get("type")?.asString ?: return

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

    // TODO: Implement all message handlers
    private fun onWebviewReady() {
        LOG.info("Webview ready")
    }

    private fun onOpenClaude(message: JsonObject) {
        LOG.info("Open Claude requested")
    }

    private fun onFocusAgent(message: JsonObject) {}
    private fun onCloseAgent(message: JsonObject) {}
    private fun onSaveLayout(message: JsonObject) {}
    private fun onSaveAgentSeats(message: JsonObject) {}
    private fun onSetSoundEnabled(message: JsonObject) {}
    private fun onSetLastSeenVersion(message: JsonObject) {}
    private fun onSetAlwaysShowLabels(message: JsonObject) {}
    private fun onSetWatchAllSessions(message: JsonObject) {}
    private fun onRequestDiagnostics() {}
    private fun onOpenSessionsFolder() {}
    private fun onExportLayout() {}
    private fun onImportLayout() {}
    private fun onAddExternalAssetDirectory() {}
    private fun onRemoveExternalAssetDirectory(message: JsonObject) {}

    override fun dispose() {
        LOG.info("WebviewBridge disposed")
    }
}
