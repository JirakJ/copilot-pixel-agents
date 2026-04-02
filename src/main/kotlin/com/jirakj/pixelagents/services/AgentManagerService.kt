package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class AgentManagerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentManagerService::class.java)

        fun getInstance(project: Project): AgentManagerService =
            project.getService(AgentManagerService::class.java)
    }

    // TODO: Implement agent lifecycle management
    // - launchNewTerminal(): Create terminal, send `claude --session-id <uuid>` command
    // - removeAgent(): Cleanup timers, file watchers
    // - persistAgents(): Serialize to PropertiesComponent
    // - restoreAgents(): Deserialize and reconnect to terminals
    // - sendExistingAgents(): Broadcast current agents to webview

    override fun dispose() {
        LOG.info("AgentManagerService disposed for project: ${project.name}")
    }
}
