package com.jirakj.pixelagents.types

data class AgentState(
    val id: Int,
    var isExternal: Boolean = false,
    val projectDir: String,
    var jsonlFile: String,
    var fileOffset: Long = 0,
    var lineBuffer: String = "",
    val activeToolIds: MutableSet<String> = mutableSetOf(),
    val activeToolStatuses: MutableMap<String, String> = mutableMapOf(),
    val activeToolNames: MutableMap<String, String> = mutableMapOf(),
    val activeSubagentToolIds: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val activeSubagentToolNames: MutableMap<String, MutableMap<String, String>> = mutableMapOf(),
    val backgroundAgentToolIds: MutableSet<String> = mutableSetOf(),
    var isWaiting: Boolean = false,
    var permissionSent: Boolean = false,
    var hadToolsInTurn: Boolean = false,
    var folderName: String? = null,
    var lastDataAt: Long = 0,
    var linesProcessed: Long = 0,
    val seenUnknownRecordTypes: MutableSet<String> = mutableSetOf()
)

data class PersistedAgent(
    val id: Int,
    val isExternal: Boolean = false,
    val jsonlFile: String,
    val projectDir: String,
    val terminalName: String = "",
    val folderName: String? = null
)
