package com.jirakj.pixelagents.services

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirakj.pixelagents.Constants
import com.jirakj.pixelagents.toolWindow.WebviewBridge
import com.jirakj.pixelagents.types.AgentState
import java.io.File

@Service(Service.Level.PROJECT)
class TranscriptParserService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TranscriptParserService::class.java)
        private val gson = Gson()
        val PERMISSION_EXEMPT_TOOLS = setOf("Task", "Agent", "AskUserQuestion")

        fun getInstance(project: Project): TranscriptParserService =
            project.getService(TranscriptParserService::class.java)
    }

    private var bridge: WebviewBridge? = null

    fun setBridge(bridge: WebviewBridge) {
        this.bridge = bridge
    }

    fun formatToolStatus(toolName: String, input: JsonObject?): String {
        val inp = input ?: JsonObject()
        fun baseName(key: String): String {
            val p = inp.get(key)?.asString ?: return ""
            return File(p).name
        }
        return when (toolName) {
            "Read" -> "Reading ${baseName("file_path")}"
            "Edit" -> "Editing ${baseName("file_path")}"
            "Write" -> "Writing ${baseName("file_path")}"
            "Bash" -> {
                val cmd = inp.get("command")?.asString ?: ""
                val display = if (cmd.length > Constants.BASH_COMMAND_DISPLAY_MAX_LENGTH)
                    cmd.take(Constants.BASH_COMMAND_DISPLAY_MAX_LENGTH) + "\u2026" else cmd
                "Running: $display"
            }
            "Glob" -> "Searching files"
            "Grep" -> "Searching code"
            "WebFetch" -> "Fetching web content"
            "WebSearch" -> "Searching the web"
            "Task", "Agent" -> {
                val desc = inp.get("description")?.asString ?: ""
                if (desc.isNotEmpty()) {
                    val display = if (desc.length > Constants.TASK_DESCRIPTION_DISPLAY_MAX_LENGTH)
                        desc.take(Constants.TASK_DESCRIPTION_DISPLAY_MAX_LENGTH) + "\u2026" else desc
                    "Subtask: $display"
                } else "Running subtask"
            }
            "AskUserQuestion" -> "Waiting for your answer"
            "EnterPlanMode" -> "Planning"
            "NotebookEdit" -> "Editing notebook"
            else -> "Using $toolName"
        }
    }

    fun processTranscriptLine(
        agentId: Int,
        line: String,
        agents: MutableMap<Int, AgentState>,
        timerManager: TimerManagerService
    ) {
        val agent = agents[agentId] ?: return
        agent.lastDataAt = System.currentTimeMillis()
        agent.linesProcessed++

        try {
            val record = gson.fromJson(line, JsonObject::class.java) ?: return
            val recordType = record.get("type")?.asString ?: return

            // Resilient content extraction
            val assistantContent = record.getAsJsonObject("message")?.get("content")
                ?: record.get("content")

            when (recordType) {
                "assistant" -> processAssistantRecord(agentId, agent, assistantContent, record, agents, timerManager)
                "progress" -> processProgressRecord(agentId, record, agents, timerManager)
                "user" -> processUserRecord(agentId, agent, record, agents, timerManager)
                "queue-operation" -> {
                    if (record.get("operation")?.asString == "enqueue") {
                        processQueueOperation(agentId, agent, record, timerManager)
                    }
                }
                "system" -> {
                    if (record.get("subtype")?.asString == "turn_duration") {
                        processTurnDuration(agentId, agent, timerManager)
                    }
                }
                else -> {
                    if (!agent.seenUnknownRecordTypes.contains(recordType)) {
                        val knownSkippable = setOf("file-history-snapshot", "system", "queue-operation")
                        if (recordType !in knownSkippable) {
                            agent.seenUnknownRecordTypes.add(recordType)
                            LOG.info("Agent $agentId: unrecognized record type '$recordType'")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore malformed lines
        }
    }

    private fun processAssistantRecord(
        agentId: Int,
        agent: AgentState,
        content: JsonElement?,
        record: JsonObject,
        agents: MutableMap<Int, AgentState>,
        timerManager: TimerManagerService
    ) {
        if (content is JsonArray) {
            val hasToolUse = content.any { it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_use" }

            if (hasToolUse) {
                timerManager.cancelWaitingTimer(agentId)
                agent.isWaiting = false
                agent.hadToolsInTurn = true
                bridge?.postMessage("agentStatus", mapOf("id" to agentId, "status" to "active"))

                var hasNonExemptTool = false
                for (element in content) {
                    if (!element.isJsonObject) continue
                    val block = element.asJsonObject
                    if (block.get("type")?.asString != "tool_use") continue
                    val toolId = block.get("id")?.asString ?: continue
                    val toolName = block.get("name")?.asString ?: ""
                    val input = block.getAsJsonObject("input")
                    val status = formatToolStatus(toolName, input)

                    LOG.info("Agent $agentId tool start: $toolId $status")
                    agent.activeToolIds.add(toolId)
                    agent.activeToolStatuses[toolId] = status
                    agent.activeToolNames[toolId] = toolName

                    if (toolName !in PERMISSION_EXEMPT_TOOLS) hasNonExemptTool = true

                    bridge?.postMessage("agentToolStart", mapOf(
                        "id" to agentId, "toolId" to toolId, "status" to status
                    ))
                }

                if (hasNonExemptTool) {
                    timerManager.startPermissionTimer(agentId, agents)
                }
            } else if (content.any { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" } && !agent.hadToolsInTurn) {
                timerManager.startWaitingTimer(agentId, Constants.TEXT_IDLE_DELAY_MS, agents)
            }
        } else if (content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            if (!agent.hadToolsInTurn) {
                timerManager.startWaitingTimer(agentId, Constants.TEXT_IDLE_DELAY_MS, agents)
            }
        } else if (content == null) {
            LOG.warn("Agent $agentId: assistant record has no content")
        }
    }

    private fun processUserRecord(
        agentId: Int,
        agent: AgentState,
        record: JsonObject,
        agents: MutableMap<Int, AgentState>,
        timerManager: TimerManagerService
    ) {
        val content = record.getAsJsonObject("message")?.get("content") ?: record.get("content")

        if (content is JsonArray) {
            val hasToolResult = content.any { it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_result" }

            if (hasToolResult) {
                for (element in content) {
                    if (!element.isJsonObject) continue
                    val block = element.asJsonObject
                    if (block.get("type")?.asString != "tool_result") continue
                    val completedToolId = block.get("tool_use_id")?.asString ?: continue
                    val completedToolName = agent.activeToolNames[completedToolId]

                    // Detect background agent launches
                    if ((completedToolName == "Task" || completedToolName == "Agent") && isAsyncAgentResult(block)) {
                        LOG.info("Agent $agentId background agent launched: $completedToolId")
                        agent.backgroundAgentToolIds.add(completedToolId)
                        continue
                    }

                    LOG.info("Agent $agentId tool done: $completedToolId")

                    if (completedToolName == "Task" || completedToolName == "Agent") {
                        agent.activeSubagentToolIds.remove(completedToolId)
                        agent.activeSubagentToolNames.remove(completedToolId)
                        bridge?.postMessage("subagentClear", mapOf(
                            "id" to agentId, "parentToolId" to completedToolId
                        ))
                    }

                    agent.activeToolIds.remove(completedToolId)
                    agent.activeToolStatuses.remove(completedToolId)
                    agent.activeToolNames.remove(completedToolId)

                    // Delayed tool done message (300ms)
                    val finalToolId = completedToolId
                    java.util.Timer().schedule(object : java.util.TimerTask() {
                        override fun run() {
                            bridge?.postMessage("agentToolDone", mapOf(
                                "id" to agentId, "toolId" to finalToolId
                            ))
                        }
                    }, Constants.TOOL_DONE_DELAY_MS)
                }

                if (agent.activeToolIds.isEmpty()) {
                    agent.hadToolsInTurn = false
                }
            } else {
                // New user text prompt
                timerManager.cancelWaitingTimer(agentId)
                timerManager.clearAgentActivity(agent, agentId)
                agent.hadToolsInTurn = false
            }
        } else if (content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            val text = content.asString
            if (text.isNotBlank()) {
                timerManager.cancelWaitingTimer(agentId)
                timerManager.clearAgentActivity(agent, agentId)
                agent.hadToolsInTurn = false
            }
        }
    }

    private fun processQueueOperation(
        agentId: Int,
        agent: AgentState,
        record: JsonObject,
        timerManager: TimerManagerService
    ) {
        val content = record.get("content")?.asString ?: return
        val match = Regex("<tool-use-id>(.*?)</tool-use-id>").find(content) ?: return
        val completedToolId = match.groupValues[1]

        if (agent.backgroundAgentToolIds.contains(completedToolId)) {
            LOG.info("Agent $agentId background agent done: $completedToolId")
            agent.backgroundAgentToolIds.remove(completedToolId)
            agent.activeSubagentToolIds.remove(completedToolId)
            agent.activeSubagentToolNames.remove(completedToolId)
            bridge?.postMessage("subagentClear", mapOf(
                "id" to agentId, "parentToolId" to completedToolId
            ))
            agent.activeToolIds.remove(completedToolId)
            agent.activeToolStatuses.remove(completedToolId)
            agent.activeToolNames.remove(completedToolId)

            java.util.Timer().schedule(object : java.util.TimerTask() {
                override fun run() {
                    bridge?.postMessage("agentToolDone", mapOf(
                        "id" to agentId, "toolId" to completedToolId
                    ))
                }
            }, Constants.TOOL_DONE_DELAY_MS)
        }
    }

    private fun processTurnDuration(
        agentId: Int,
        agent: AgentState,
        timerManager: TimerManagerService
    ) {
        timerManager.cancelWaitingTimer(agentId)
        timerManager.cancelPermissionTimer(agentId)

        val hasForegroundTools = agent.activeToolIds.size > agent.backgroundAgentToolIds.size
        if (hasForegroundTools) {
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
            bridge?.postMessage("agentToolsClear", mapOf("id" to agentId))
            for (toolId in agent.backgroundAgentToolIds) {
                val status = agent.activeToolStatuses[toolId]
                if (status != null) {
                    bridge?.postMessage("agentToolStart", mapOf(
                        "id" to agentId, "toolId" to toolId, "status" to status
                    ))
                }
            }
        } else if (agent.activeToolIds.isNotEmpty() && agent.backgroundAgentToolIds.isEmpty()) {
            agent.activeToolIds.clear()
            agent.activeToolStatuses.clear()
            agent.activeToolNames.clear()
            agent.activeSubagentToolIds.clear()
            agent.activeSubagentToolNames.clear()
            bridge?.postMessage("agentToolsClear", mapOf("id" to agentId))
        }

        agent.isWaiting = true
        agent.permissionSent = false
        agent.hadToolsInTurn = false
        bridge?.postMessage("agentStatus", mapOf("id" to agentId, "status" to "waiting"))
    }

    private fun processProgressRecord(
        agentId: Int,
        record: JsonObject,
        agents: MutableMap<Int, AgentState>,
        timerManager: TimerManagerService
    ) {
        val agent = agents[agentId] ?: return
        val parentToolId = record.get("parentToolUseID")?.asString ?: return
        val data = record.getAsJsonObject("data") ?: return
        val dataType = data.get("type")?.asString

        if (dataType == "bash_progress" || dataType == "mcp_progress") {
            if (agent.activeToolIds.contains(parentToolId)) {
                timerManager.startPermissionTimer(agentId, agents)
            }
            return
        }

        val parentToolName = agent.activeToolNames[parentToolId]
        if (parentToolName != "Task" && parentToolName != "Agent") return

        val msg = data.getAsJsonObject("message") ?: return
        val msgType = msg.get("type")?.asString ?: return
        val innerMsg = msg.getAsJsonObject("message") ?: return
        val content = innerMsg.get("content")
        if (content !is JsonArray) return

        when (msgType) {
            "assistant" -> {
                var hasNonExemptSubTool = false
                for (element in content) {
                    if (!element.isJsonObject) continue
                    val block = element.asJsonObject
                    if (block.get("type")?.asString != "tool_use") continue
                    val toolId = block.get("id")?.asString ?: continue
                    val toolName = block.get("name")?.asString ?: ""
                    val status = formatToolStatus(toolName, block.getAsJsonObject("input"))

                    LOG.info("Agent $agentId subagent tool start: $toolId $status (parent: $parentToolId)")

                    agent.activeSubagentToolIds.getOrPut(parentToolId) { mutableSetOf() }.add(toolId)
                    agent.activeSubagentToolNames.getOrPut(parentToolId) { mutableMapOf() }[toolId] = toolName

                    if (toolName !in PERMISSION_EXEMPT_TOOLS) hasNonExemptSubTool = true

                    bridge?.postMessage("subagentToolStart", mapOf(
                        "id" to agentId, "parentToolId" to parentToolId,
                        "toolId" to toolId, "status" to status
                    ))
                }
                if (hasNonExemptSubTool) {
                    timerManager.startPermissionTimer(agentId, agents)
                }
            }
            "user" -> {
                for (element in content) {
                    if (!element.isJsonObject) continue
                    val block = element.asJsonObject
                    if (block.get("type")?.asString != "tool_result") continue
                    val toolId = block.get("tool_use_id")?.asString ?: continue

                    LOG.info("Agent $agentId subagent tool done: $toolId (parent: $parentToolId)")

                    agent.activeSubagentToolIds[parentToolId]?.remove(toolId)
                    agent.activeSubagentToolNames[parentToolId]?.remove(toolId)

                    val finalToolId = toolId
                    java.util.Timer().schedule(object : java.util.TimerTask() {
                        override fun run() {
                            bridge?.postMessage("subagentToolDone", mapOf(
                                "id" to agentId, "parentToolId" to parentToolId, "toolId" to finalToolId
                            ))
                        }
                    }, 300L)
                }

                // Check for remaining non-exempt sub-agent tools
                var stillHasNonExempt = false
                for ((_, subNames) in agent.activeSubagentToolNames) {
                    for ((_, toolName) in subNames) {
                        if (toolName !in PERMISSION_EXEMPT_TOOLS) {
                            stillHasNonExempt = true
                            break
                        }
                    }
                    if (stillHasNonExempt) break
                }
                if (stillHasNonExempt) {
                    timerManager.startPermissionTimer(agentId, agents)
                }
            }
        }
    }

    private fun isAsyncAgentResult(block: JsonObject): Boolean {
        val content = block.get("content")
        if (content is JsonArray) {
            for (item in content) {
                if (item.isJsonObject) {
                    val text = item.asJsonObject.get("text")?.asString
                    if (text?.startsWith("Async agent launched successfully.") == true) return true
                }
            }
        } else if (content?.isJsonPrimitive == true && content.asJsonPrimitive.isString) {
            return content.asString.startsWith("Async agent launched successfully.")
        }
        return false
    }

    override fun dispose() {}
}
