package com.jirakj.pixelagents.toolWindow

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.openapi.diagnostic.Logger
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import java.io.File
import java.util.jar.JarFile

class PixelAgentsToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private val LOG = Logger.getInstance(PixelAgentsToolWindowFactory::class.java)
        private const val WEBVIEW_RESOURCE_PREFIX = "webview/"
        private const val WEBVIEW_CACHE_DIR = "pixel-agents-webview"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()

        // Log JS console messages for diagnostics
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser?,
                level: org.cef.CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                LOG.info("JCEF Console [$level] $source:$line - $message")
                return false
            }
        }, browser.cefBrowser)

        @Suppress("UNUSED_VARIABLE")
        val bridge = WebviewBridge(browser, project)

        val webviewDir = findWebviewDir()
        if (webviewDir != null) {
            // Inject a cefQuery stub into index.html so runtime detection picks up JCEF
            // before the deferred main script runs. The real cefQuery is injected in onLoadEnd.
            val indexFile = File(webviewDir, "index.html")
            val html = indexFile.readText()
            val stubScript = """<script>window.cefQuery=function(p){window.__pendingCefQ=window.__pendingCefQ||[];window.__pendingCefQ.push(p);};</script>"""
            val modifiedHtml = html.replace("<script defer", "$stubScript\n    <script defer")
            val modifiedFile = File(webviewDir, "index_jcef.html")
            modifiedFile.writeText(modifiedHtml)
            browser.loadURL(modifiedFile.toURI().toString())
            LOG.info("Loaded webview from: ${modifiedFile.absolutePath}")
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
        // Dev mode: look relative to working directory
        val devPath = File("webview-ui/dist")
        if (devPath.exists() && File(devPath, "index.html").exists()) {
            return devPath
        }

        // Production: extract webview resources from JAR to a cache directory
        val resourceUrl = javaClass.classLoader.getResource("webview/index.html") ?: return null

        if (resourceUrl.protocol == "file") {
            return File(resourceUrl.toURI()).parentFile
        }

        if (resourceUrl.protocol == "jar") {
            return extractWebviewFromJar(resourceUrl)
        }

        return null
    }

    private fun extractWebviewFromJar(resourceUrl: java.net.URL): File? {
        val targetDir = File(PathManager.getPluginTempPath(), WEBVIEW_CACHE_DIR)

        try {
            // Parse jar path from URL like "jar:file:/path/to/plugin.jar!/webview/index.html"
            val jarPath = resourceUrl.path.substringBefore("!").removePrefix("file:")
            val jarFile = File(jarPath)

            // Re-extract if JAR is newer than the cached index.html
            val cachedIndex = File(targetDir, "index.html")
            if (cachedIndex.exists() && cachedIndex.lastModified() >= jarFile.lastModified()) {
                return targetDir
            }

            // Clean stale cache and re-extract
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            val jar = JarFile(jarPath)
            jar.entries().asSequence()
                .filter { it.name.startsWith(WEBVIEW_RESOURCE_PREFIX) && !it.isDirectory }
                .forEach { entry ->
                    val relativeName = entry.name.removePrefix(WEBVIEW_RESOURCE_PREFIX)
                    val outFile = File(targetDir, relativeName)
                    outFile.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

            jar.close()
            LOG.info("Extracted webview resources to: ${targetDir.absolutePath}")
            return targetDir
        } catch (e: Exception) {
            LOG.error("Failed to extract webview from JAR: ${e.message}", e)
            return null
        }
    }
}
