package com.jirakj.pixelagents.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
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

    private fun launchTerminal(agentId: Int, command: String, folderPath: String?) {
        val terminalIndex = nextTerminalIndex++
        val terminalName = "${Constants.TERMINAL_NAME_PREFIX} $terminalIndex"

        try {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                try {
                    // Use ProcessBuilder as a portable approach that works without terminal plugin dependency
                    val workingDir = folderPath ?: project.basePath ?: System.getProperty("user.home")
                    val processBuilder = ProcessBuilder("bash", "-c", command)
                    processBuilder.directory(java.io.File(workingDir))
                    processBuilder.redirectErrorStream(true)
                    processBuilder.start()
                    LOG.info("Launched process '$terminalName' for agent $agentId: $command")
                } catch (e: Exception) {
                    LOG.error("Failed to launch process for agent $agentId: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to launch terminal for agent $agentId: ${e.message}", e)
        }
    }

    private fun startJsonlPolling(agentId: Int, jsonlFile: String) {
        val future = scheduler.scheduleAtFixedRate({
            val file = File(jsonlFile)
            if (file.exists()) {
                LOG.info("JSONL file found for agent $agentId: ${file.name}")
                val fileWatcher = FileWatcherService.getInstance(project)
                fileWatcher.startFileWatching(agentId, jsonlFile)
            }
        }, 0, Constants.JSONL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun removeAgent(agentId: Int) {
        val agent = agents.remove(agentId) ?: return
        LOG.info("Removing agent $agentId")

        val timerManager = TimerManagerService.getInstance(project)
        timerManager.cancelAllTimers(agentId)

        val fileWatcher = FileWatcherService.getInstance(project)
        fileWatcher.stopFileWatching(agentId)

        bridge?.postMessage("agentClosed", mapOf("id" to agentId))
        persistAgents()
    }

    fun persistAgents() {
        val persisted = agents.values.map { agent ->
            PersistedAgent(
                id = agent.id,
                isExternal = agent.isExternal,
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
        agents.clear()
    }
}
