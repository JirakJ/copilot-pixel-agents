package com.jirakj.pixelagents.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirakj.pixelagents.Constants
import com.jirakj.pixelagents.toolWindow.WebviewBridge
import com.jirakj.pixelagents.types.AgentState
import com.jirakj.pixelagents.types.PersistedAgent
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class AgentManagerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentManagerService::class.java)
        private val gson = Gson()

        fun getInstance(project: Project): AgentManagerService =
            project.getService(AgentManagerService::class.java)
    }

    val agents = ConcurrentHashMap<Int, AgentState>()
    private var nextAgentId = 1
    private var nextTerminalIndex = 1
    private var bridge: WebviewBridge? = null
    private val scheduler = Executors.newScheduledThreadPool(1)
    private val jsonlPollingFutures = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    fun setBridge(bridge: WebviewBridge) {
        this.bridge = bridge
    }

    /**
     * Get the project hash directory path for JSONL files.
     * Claude Code stores transcripts at ~/.claude/projects/<project-hash>/
     * where project-hash = workspace path with :, \, / replaced by -
     */
    fun getProjectDir(): String {
        val projectPath = project.basePath ?: return ""
        val hash = projectPath.replace(":", "-").replace("\\", "-").replace("/", "-")
        val homeDir = System.getProperty("user.home")
        return "$homeDir/.claude/projects/$hash"
    }

    fun launchNewAgent(folderPath: String? = null, bypassPermissions: Boolean = false): Int {
        val agentId = nextAgentId++
        val sessionId = UUID.randomUUID().toString()
        val projectDir = getProjectDir()
        val jsonlFile = "$projectDir/$sessionId.jsonl"

        val agent = AgentState(
            id = agentId,
            projectDir = projectDir,
            jsonlFile = jsonlFile
        )
        agents[agentId] = agent

        // Build the Claude command
        val cmd = buildString {
            append("claude --session-id $sessionId")
            if (bypassPermissions) append(" --dangerously-skip-permissions")
        }

        // Launch terminal with the command
        launchTerminal(agentId, cmd, folderPath)

        bridge?.postMessage("agentCreated", mapOf(
            "id" to agentId,
            "folderName" to agent.folderName
        ))

        // Start polling for JSONL file creation
        startJsonlPolling(agentId, jsonlFile)

        persistAgents()
        return agentId
    }

    fun launchCopilotAgent(): Int {
        // Check if there's already a Copilot agent
        val existing = agents.values.find { it.isCopilot }
        if (existing != null) return existing.id

        val agentId = nextAgentId++
        val agent = AgentState(
            id = agentId,
            isCopilot = true,
            projectDir = "",
            jsonlFile = "",
            folderName = "Copilot"
        )
        agents[agentId] = agent

        bridge?.postMessage("agentCreated", mapOf(
            "id" to agentId,
            "folderName" to "Copilot"
        ))

        persistAgents()
        return agentId
    }

    fun getCopilotAgentId(): Int? = agents.values.find { it.isCopilot }?.id

    fun isCopilotAgentActive(): Boolean = agents.values.any { it.isCopilot }

    fun removeCopilotAgent() {
        val copilotAgent = agents.values.find { it.isCopilot } ?: return
        agents.remove(copilotAgent.id)
        bridge?.postMessage("agentClosed", mapOf("id" to copilotAgent.id))
        persistAgents()
    }

    private fun launchTerminal(agentId: Int, command: String, folderPath: String?) {
        val terminalIndex = nextTerminalIndex++
        val terminalName = "${Constants.TERMINAL_NAME_PREFIX} $terminalIndex"

        ApplicationManager.getApplication().invokeLater {
            try {
                val workDir = folderPath ?: project.basePath ?: System.getProperty("user.home")

                // Try to use the Terminal plugin API for a visible terminal
                try {
                    val terminalManager = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
                    @Suppress("DEPRECATION")
                    val widget = terminalManager.createLocalShellWidget(workDir, terminalName)
                    widget.executeCommand(command)
                    LOG.info("Launched terminal '$terminalName' for agent $agentId")
                } catch (e: Exception) {
                    // Fallback if terminal plugin not available
                    LOG.warn("Terminal plugin not available, using ProcessBuilder: ${e.message}")
                    val processBuilder = ProcessBuilder("bash", "-c", command)
                    processBuilder.directory(File(workDir))
                    processBuilder.redirectErrorStream(true)
                    processBuilder.start()
                }
            } catch (e: Exception) {
                LOG.error("Failed to launch terminal for agent $agentId: ${e.message}", e)
            }
        }
    }

    private fun startJsonlPolling(agentId: Int, jsonlFile: String) {
        val future = scheduler.scheduleAtFixedRate({
            val file = File(jsonlFile)
            if (file.exists()) {
                LOG.info("Agent $agentId: JSONL file found")
                val fileWatcher = FileWatcherService.getInstance(project)
                fileWatcher.startFileWatching(agentId, jsonlFile)
                // Cancel this polling task now that file watching is active
                jsonlPollingFutures.remove(agentId)?.cancel(false)
            }
        }, 0, Constants.JSONL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        jsonlPollingFutures[agentId] = future
    }

    fun removeAgent(agentId: Int) {
        val agent = agents.remove(agentId) ?: return
        LOG.info("Removing agent $agentId")

        // Cancel any active JSONL polling for this agent
        jsonlPollingFutures.remove(agentId)?.cancel(false)

        // Skip file watcher / timer cleanup for Copilot agents (no terminal or JSONL)
        if (!agent.isCopilot) {
            val timerManager = TimerManagerService.getInstance(project)
            timerManager.cancelAllTimers(agentId)

            val fileWatcher = FileWatcherService.getInstance(project)
            fileWatcher.stopFileWatching(agentId)
        }

        bridge?.postMessage("agentClosed", mapOf("id" to agentId))
        persistAgents()
    }

    fun persistAgents() {
        val persisted = agents.values.map { agent ->
            PersistedAgent(
                id = agent.id,
                isExternal = agent.isExternal,
                isCopilot = agent.isCopilot,
                jsonlFile = agent.jsonlFile,
                projectDir = agent.projectDir,
                folderName = agent.folderName
            )
        }
        val json = gson.toJson(persisted)
        PropertiesComponent.getInstance(project).setValue(Constants.KEY_AGENTS, json)
    }

    fun restoreAgents() {
        val json = PropertiesComponent.getInstance(project).getValue(Constants.KEY_AGENTS) ?: return
        try {
            val type = object : TypeToken<List<PersistedAgent>>() {}.type
            val persisted: List<PersistedAgent> = gson.fromJson(json, type)

            for (pa in persisted) {
                if (pa.isCopilot) {
                    // Restore Copilot agent without file watching
                    val agent = AgentState(
                        id = pa.id,
                        isCopilot = true,
                        projectDir = "",
                        jsonlFile = "",
                        folderName = pa.folderName
                    )
                    agents[pa.id] = agent
                    if (pa.id >= nextAgentId) nextAgentId = pa.id + 1
                    continue
                }

                val jsonlFile = File(pa.jsonlFile)
                if (!jsonlFile.exists()) continue

                val agent = AgentState(
                    id = pa.id,
                    isExternal = pa.isExternal,
                    projectDir = pa.projectDir,
                    jsonlFile = pa.jsonlFile,
                    folderName = pa.folderName
                )
                agents[pa.id] = agent

                if (pa.id >= nextAgentId) nextAgentId = pa.id + 1

                val fileWatcher = FileWatcherService.getInstance(project)
                fileWatcher.startFileWatching(pa.id, pa.jsonlFile)
            }

            LOG.info("Restored ${agents.size} agents")
        } catch (e: Exception) {
            LOG.warn("Failed to restore agents: ${e.message}")
        }
    }

    fun sendExistingAgents() {
        val agentIds = agents.keys.toList()
        val agentMeta = mutableMapOf<String, Map<String, Any?>>()
        val folderNames = mutableMapOf<String, String>()
        val externalAgents = mutableMapOf<String, Boolean>()

        // Load persisted seat info
        val seatsJson = PropertiesComponent.getInstance(project).getValue(Constants.KEY_AGENT_SEATS)
        val seats: Map<String, Map<String, Any>>? = if (seatsJson != null) {
            try {
                val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
                gson.fromJson(seatsJson, type)
            } catch (_: Exception) { null }
        } else null

        for (agent in agents.values) {
            val idStr = agent.id.toString()
            val seatInfo = seats?.get(idStr)
            agentMeta[idStr] = mapOf(
                "palette" to seatInfo?.get("palette"),
                "hueShift" to seatInfo?.get("hueShift"),
                "seatId" to seatInfo?.get("seatId")
            )
            if (agent.folderName != null) {
                folderNames[idStr] = agent.folderName!!
            }
            if (agent.isExternal) {
                externalAgents[idStr] = true
            }
        }

        bridge?.postMessage("existingAgents", mapOf(
            "agents" to agentIds,
            "agentMeta" to agentMeta,
            "folderNames" to folderNames,
            "externalAgents" to externalAgents
        ))
    }

    override fun dispose() {
        scheduler.shutdownNow()
        jsonlPollingFutures.values.forEach { it.cancel(true) }
        jsonlPollingFutures.clear()
        agents.clear()
    }
}
