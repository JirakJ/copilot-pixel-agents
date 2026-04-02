package com.jirakj.pixelagents.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.openapi.diagnostic.Logger
import java.io.File

class PixelAgentsToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private val LOG = Logger.getInstance(PixelAgentsToolWindowFactory::class.java)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()
        val bridge = WebviewBridge(browser, project)

        val webviewDir = findWebviewDir()
        if (webviewDir != null) {
            val indexFile = File(webviewDir, "index.html")
            browser.loadURL(indexFile.toURI().toString())
            LOG.info("Loaded webview from: ${indexFile.absolutePath}")
        } else {
            browser.loadHTML("<html><body><h2>Pixel Agents</h2><p>Webview not found. Please build the webview-ui first.</p></body></html>")
            LOG.warn("Webview directory not found")
        }

        val content = toolWindow.contentManager.factory.createContent(
            browser.component,
            "Pixel Agents",
            false
        )
        toolWindow.contentManager.addContent(content)
    }

    private fun findWebviewDir(): File? {
        // Look for webview resources in plugin JAR resources
        val resourceUrl = javaClass.classLoader.getResource("webview/index.html")
        if (resourceUrl != null) {
            return File(resourceUrl.toURI()).parentFile
        }

        // Fallback: look relative to working directory (dev mode)
        val devPath = File("webview-ui/dist")
        if (devPath.exists()) {
            return devPath
        }

        return null
    }
}
