package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class TranscriptParserService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TranscriptParserService::class.java)

        fun getInstance(project: Project): TranscriptParserService =
            project.getService(TranscriptParserService::class.java)
    }

    // TODO: Implement JSONL transcript parsing
    // - processTranscriptLine(): Parse each JSONL record
    // - Handle record types: assistant, user, progress, system, queue-operation
    // - Tool lifecycle: tool_use → tool_result
    // - Sub-agent progress via data.type: agent_progress
    // - Permission timer triggering on non-exempt tools
    // - Waiting status via text-idle or turn_duration

    override fun dispose() {}
}
