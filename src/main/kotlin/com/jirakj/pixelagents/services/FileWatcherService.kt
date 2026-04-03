package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirakj.pixelagents.Constants
import com.jirakj.pixelagents.toolWindow.WebviewBridge
import com.jirakj.pixelagents.types.AgentState
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class FileWatcherService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(FileWatcherService::class.java)
        private const val MAX_READ_BYTES = 65536L

        fun getInstance(project: Project): FileWatcherService =
            project.getService(FileWatcherService::class.java)
    }

    private val scheduler = Executors.newScheduledThreadPool(1)
    private val pollingTimers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private val knownJsonlFiles = ConcurrentHashMap.newKeySet<String>()
    private var bridge: WebviewBridge? = null
    private var agents: MutableMap<Int, AgentState>? = null
    private var timerManager: TimerManagerService? = null
    private var transcriptParser: TranscriptParserService? = null

    fun initialize(
        bridge: WebviewBridge,
        agents: MutableMap<Int, AgentState>,
        timerManager: TimerManagerService,
        transcriptParser: TranscriptParserService
    ) {
        this.bridge = bridge
        this.agents = agents
        this.timerManager = timerManager
        this.transcriptParser = transcriptParser
    }

    fun startFileWatching(agentId: Int, filePath: String) {
        knownJsonlFiles.add(filePath)
        val future = scheduler.scheduleAtFixedRate({
            try {
                val agentMap = agents ?: return@scheduleAtFixedRate
                if (!agentMap.containsKey(agentId)) {
                    stopFileWatching(agentId)
                    return@scheduleAtFixedRate
                }
                readNewLines(agentId)
            } catch (e: Exception) {
                LOG.warn("Error in file watcher for agent $agentId: ${e.message}")
            }
        }, 0, Constants.FILE_WATCHER_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        pollingTimers[agentId] = future
    }

    fun stopFileWatching(agentId: Int) {
        pollingTimers.remove(agentId)?.cancel(false)
    }

    fun readNewLines(agentId: Int) {
        val agentMap = agents ?: return
        val agent = agentMap[agentId] ?: return
        val tm = timerManager ?: return
        val tp = transcriptParser ?: return
        val br = bridge ?: return

        try {
            val file = File(agent.jsonlFile)
            if (!file.exists()) return
            val fileSize = file.length()
            if (fileSize <= agent.fileOffset) return

            // Handle file truncation (e.g. /clear creates new file)
            if (fileSize < agent.fileOffset) {
                agent.fileOffset = 0
                agent.lineBuffer = ""
            }

            val bytesToRead = minOf(fileSize - agent.fileOffset, MAX_READ_BYTES)
            val buf = ByteArray(bytesToRead.toInt())

            val bytesRead: Int
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(agent.fileOffset)
                bytesRead = raf.read(buf)
            }
            if (bytesRead <= 0) return
            agent.fileOffset += bytesRead

            val text = agent.lineBuffer + String(buf, 0, bytesRead, Charsets.UTF_8)
            val lines = text.split('\n').toMutableList()
            agent.lineBuffer = lines.removeLastOrNull() ?: ""

            val hasLines = lines.any { it.isNotBlank() }
            if (hasLines) {
                tm.cancelWaitingTimer(agentId)
                tm.cancelPermissionTimer(agentId)
                if (agent.permissionSent) {
                    agent.permissionSent = false
                    br.postMessage("agentToolPermissionClear", mapOf("id" to agentId))
                }
            }

            for (line in lines) {
                if (line.isBlank()) continue
                tp.processTranscriptLine(agentId, line, agentMap, tm)
            }
        } catch (e: Exception) {
            LOG.info("Read error for agent $agentId: ${e.message}")
        }
    }

    /**
     * Scan project directory for JSONL files to seed known files list.
     */
    fun seedProjectDir(projectDir: String) {
        try {
            val dir = File(projectDir)
            if (!dir.exists()) return
            dir.listFiles()?.filter { it.extension == "jsonl" }?.forEach {
                knownJsonlFiles.add(it.absolutePath)
            }
        } catch (e: Exception) {
            LOG.warn("Error seeding project dir: ${e.message}")
        }
    }

    override fun dispose() {
        scheduler.shutdownNow()
        pollingTimers.values.forEach { it.cancel(true) }
        pollingTimers.clear()
    }
}
