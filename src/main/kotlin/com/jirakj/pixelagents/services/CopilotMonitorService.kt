package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.jirakj.pixelagents.Constants
import com.jirakj.pixelagents.toolWindow.WebviewBridge
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class CopilotMonitorService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(CopilotMonitorService::class.java)

        fun getInstance(project: Project): CopilotMonitorService =
            project.getService(CopilotMonitorService::class.java)
    }

    private var bridge: WebviewBridge? = null
    private val agentManager by lazy { AgentManagerService.getInstance(project) }
    private val scheduler = Executors.newScheduledThreadPool(1)
    private var pollingFuture: ScheduledFuture<*>? = null
    private val isMonitoring = AtomicBoolean(false)

    // Activity tracking
    private var currentToolId: String? = null
    private val lastActivityAt = AtomicLong(0)
    private val isActive = AtomicBoolean(false)
    private var lastContentHash: Int = 0

    fun setBridge(bridge: WebviewBridge) {
        this.bridge = bridge
        subscribeToToolWindowEvents()
    }

    private fun subscribeToToolWindowEvents() {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: com.intellij.openapi.wm.ToolWindow) {
                    if (isCopilotChatWindow(toolWindow.id)) {
                        LOG.info("Copilot Chat tool window shown")
                        onCopilotChatOpened()
                    }
                }

                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType
                ) {
                    // Check if Copilot Chat window was hidden/closed
                    val tw = findCopilotToolWindow()
                    if (tw == null || !tw.isVisible) {
                        if (agentManager.isCopilotAgentActive()) {
                            onCopilotChatClosed()
                        }
                    }
                }
            }
        )
    }

    private fun isCopilotChatWindow(windowId: String): Boolean {
        return windowId == Constants.COPILOT_CHAT_TOOL_WINDOW_ID ||
            windowId == "CopilotChat" ||
            windowId.contains("Copilot") && windowId.contains("Chat")
    }

    private fun findCopilotToolWindow(): com.intellij.openapi.wm.ToolWindow? {
        val twm = ToolWindowManager.getInstance(project)
        return twm.getToolWindow(Constants.COPILOT_CHAT_TOOL_WINDOW_ID)
            ?: twm.getToolWindow("CopilotChat")
    }

    fun startMonitoring() {
        if (isMonitoring.getAndSet(true)) return
        LOG.info("Starting Copilot Chat monitoring")

        // Create the Copilot agent character
        if (!agentManager.isCopilotAgentActive()) {
            agentManager.launchCopilotAgent()
        }

        // Start polling for activity
        pollingFuture = scheduler.scheduleAtFixedRate(
            { pollCopilotActivity() },
            0,
            Constants.COPILOT_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    fun stopMonitoring() {
        if (!isMonitoring.getAndSet(false)) return
        LOG.info("Stopping Copilot Chat monitoring")

        pollingFuture?.cancel(false)
        pollingFuture = null

        // Clear any active tool state
        finishActiveToolIfNeeded()

        // Remove the Copilot agent character
        agentManager.removeCopilotAgent()
    }

    private fun onCopilotChatOpened() {
        startMonitoring()
    }

    private fun onCopilotChatClosed() {
        stopMonitoring()
    }

    private fun pollCopilotActivity() {
        try {
            val tw = findCopilotToolWindow() ?: return

            ApplicationManager.getApplication().invokeLater {
                try {
                    if (!tw.isVisible) return@invokeLater

                    // Detect activity by hashing the content component's structure
                    val contentHash = computeContentHash(tw)
                    val hasChanged = contentHash != lastContentHash && lastContentHash != 0
                    lastContentHash = contentHash

                    if (hasChanged) {
                        onActivityDetected()
                    } else {
                        checkIdleTimeout()
                    }
                } catch (e: Exception) {
                    LOG.debug("Error polling Copilot activity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error in Copilot poll: ${e.message}")
        }
    }

    private fun computeContentHash(tw: com.intellij.openapi.wm.ToolWindow): Int {
        // Hash the component tree depth and child counts as a lightweight change indicator
        val component = tw.component ?: return 0
        return computeComponentHash(component, 0)
    }

    private fun computeComponentHash(component: java.awt.Component, depth: Int): Int {
        if (depth > 5) return 0
        var hash = component.bounds.hashCode()
        if (component is java.awt.Container) {
            hash = hash * 31 + component.componentCount
            for (i in 0 until minOf(component.componentCount, 10)) {
                hash = hash * 31 + computeComponentHash(component.getComponent(i), depth + 1)
            }
        }
        return hash
    }

    private fun onActivityDetected() {
        val agentId = agentManager.getCopilotAgentId() ?: return
        lastActivityAt.set(System.currentTimeMillis())

        if (!isActive.getAndSet(true)) {
            // Transition from idle → active
            currentToolId = UUID.randomUUID().toString()
            bridge?.postMessage("agentToolStart", mapOf(
                "id" to agentId,
                "toolId" to currentToolId!!,
                "status" to Constants.COPILOT_TOOL_NAME
            ))
        }
    }

    private fun checkIdleTimeout() {
        val lastActivity = lastActivityAt.get()
        if (lastActivity == 0L) return
        if (!isActive.get()) return

        val elapsed = System.currentTimeMillis() - lastActivity
        if (elapsed >= Constants.COPILOT_IDLE_THRESHOLD_MS) {
            finishActiveToolIfNeeded()
        }
    }

    private fun finishActiveToolIfNeeded() {
        if (!isActive.getAndSet(false)) return
        val agentId = agentManager.getCopilotAgentId() ?: return
        val toolId = currentToolId ?: return

        bridge?.postMessage("agentToolDone", mapOf(
            "id" to agentId,
            "toolId" to toolId
        ))
        bridge?.postMessage("agentStatus", mapOf(
            "id" to agentId,
            "status" to "waiting"
        ))
        currentToolId = null
    }

    override fun dispose() {
        pollingFuture?.cancel(true)
        scheduler.shutdownNow()
    }
}
