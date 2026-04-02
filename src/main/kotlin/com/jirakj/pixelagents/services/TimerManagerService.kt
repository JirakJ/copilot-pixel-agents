package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirakj.pixelagents.Constants
import com.jirakj.pixelagents.toolWindow.WebviewBridge
import com.jirakj.pixelagents.types.AgentState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class TimerManagerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TimerManagerService::class.java)

        fun getInstance(project: Project): TimerManagerService =
            project.getService(TimerManagerService::class.java)
    }

    private val scheduler = Executors.newScheduledThreadPool(2)
    private val waitingTimers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private val permissionTimers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private var bridge: WebviewBridge? = null

    fun setBridge(bridge: WebviewBridge) {
        this.bridge = bridge
    }

    fun clearAgentActivity(agent: AgentState, agentId: Int) {
        if (agent.backgroundAgentToolIds.isNotEmpty()) {
            val toRemove = agent.activeToolIds.filter { !agent.backgroundAgentToolIds.contains(it) }
            for (toolId in toRemove) {
                agent.activeToolIds.remove(toolId)
                agent.activeToolStatuses.remove(toolId)
                val toolName = agent.activeToolNames.remove(toolId)
                if (toolName == "Task" || toolName == "Agent") {
                    agent.activeSubagentToolIds.remove(toolId)
                    agent.activeSubagentToolNames.remove(toolId)
                }
            }
        } else {
            agent.activeToolIds.clear()
            agent.activeToolStatuses.clear()
            agent.activeToolNames.clear()
            agent.activeSubagentToolIds.clear()
            agent.activeSubagentToolNames.clear()
        }

        agent.isWaiting = false
        agent.permissionSent = false
        cancelPermissionTimer(agentId)
        bridge?.postMessage("agentToolsClear", mapOf("id" to agentId))
        // Re-send background agent tools
        for (toolId in agent.backgroundAgentToolIds) {
            val status = agent.activeToolStatuses[toolId]
            if (status != null) {
                bridge?.postMessage("agentToolStart", mapOf(
                    "id" to agentId, "toolId" to toolId, "status" to status
                ))
            }
        }
        bridge?.postMessage("agentStatus", mapOf("id" to agentId, "status" to "active"))
    }

    fun cancelWaitingTimer(agentId: Int) {
        waitingTimers.remove(agentId)?.cancel(false)
    }

    fun startWaitingTimer(agentId: Int, delayMs: Long, agents: MutableMap<Int, AgentState>) {
        cancelWaitingTimer(agentId)
        val future = scheduler.schedule({
            waitingTimers.remove(agentId)
            val agent = agents[agentId]
            if (agent != null) {
                agent.isWaiting = true
            }
            bridge?.postMessage("agentStatus", mapOf("id" to agentId, "status" to "waiting"))
        }, delayMs, TimeUnit.MILLISECONDS)
        waitingTimers[agentId] = future
    }

    fun cancelPermissionTimer(agentId: Int) {
        permissionTimers.remove(agentId)?.cancel(false)
    }

    fun startPermissionTimer(agentId: Int, agents: MutableMap<Int, AgentState>) {
        cancelPermissionTimer(agentId)
        val future = scheduler.schedule({
            permissionTimers.remove(agentId)
            val agent = agents[agentId] ?: return@schedule

            var hasNonExempt = false
            for (toolId in agent.activeToolIds) {
                val toolName = agent.activeToolNames[toolId] ?: ""
                if (toolName !in TranscriptParserService.PERMISSION_EXEMPT_TOOLS) {
                    hasNonExempt = true
                    break
                }
            }

            val stuckSubagentParentToolIds = mutableListOf<String>()
            for ((parentToolId, subToolNames) in agent.activeSubagentToolNames) {
                for ((_, toolName) in subToolNames) {
                    if (toolName !in TranscriptParserService.PERMISSION_EXEMPT_TOOLS) {
                        stuckSubagentParentToolIds.add(parentToolId)
                        hasNonExempt = true
                        break
                    }
                }
            }

            if (hasNonExempt) {
                agent.permissionSent = true
                LOG.info("Agent $agentId: possible permission wait detected")
                bridge?.postMessage("agentToolPermission", mapOf("id" to agentId))
                for (parentToolId in stuckSubagentParentToolIds) {
                    bridge?.postMessage("subagentToolPermission", mapOf(
                        "id" to agentId, "parentToolId" to parentToolId
                    ))
                }
            }
        }, Constants.PERMISSION_TIMER_DELAY_MS, TimeUnit.MILLISECONDS)
        permissionTimers[agentId] = future
    }

    fun cancelAllTimers(agentId: Int) {
        cancelWaitingTimer(agentId)
        cancelPermissionTimer(agentId)
    }

    override fun dispose() {
        scheduler.shutdownNow()
        waitingTimers.values.forEach { it.cancel(true) }
        permissionTimers.values.forEach { it.cancel(true) }
        waitingTimers.clear()
        permissionTimers.clear()
    }
}
