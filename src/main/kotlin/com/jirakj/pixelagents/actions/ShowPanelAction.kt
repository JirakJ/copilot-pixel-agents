package com.jirakj.pixelagents.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.jirakj.pixelagents.Constants

class ShowPanelAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(Constants.TOOL_WINDOW_ID)
        toolWindow?.show()
    }
}
