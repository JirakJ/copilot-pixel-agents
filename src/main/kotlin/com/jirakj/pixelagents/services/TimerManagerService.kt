package com.jirakj.pixelagents.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class TimerManagerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TimerManagerService::class.java)

        fun getInstance(project: Project): TimerManagerService =
            project.getService(TimerManagerService::class.java)
    }

    // TODO: Implement waiting/permission timer logic
    // - Permission timer: 7s delay for non-exempt tools
    // - Text-idle timer: 5s silence → waiting status
    // - Timer cancellation on new data arrival
    // - Sub-agent permission forwarding to parent

    override fun dispose() {}
}
