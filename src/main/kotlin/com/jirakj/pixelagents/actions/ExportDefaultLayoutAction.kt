package com.jirakj.pixelagents.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

class ExportDefaultLayoutAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(ExportDefaultLayoutAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // TODO: Export current layout to src/main/resources/assets/default-layout.json
        LOG.info("Export default layout requested")
    }
}
