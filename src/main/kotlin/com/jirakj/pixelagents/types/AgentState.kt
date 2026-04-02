package com.jirakj.pixelagents.types

data class AgentState(
    val id: Int,
    val isExternal: Boolean = false,
    val projectDir: String,
    val jsonlFile: String? = null,
    var fileOffset: Long = 0,
    var lineBuffer: String = "",
    val activeToolIds: MutableSet<String> = mutableSetOf(),
    val activeToolStatuses: MutableMap<String, String> = mutableMapOf(),
    val activeSubagentToolNames: MutableMap<String, MutableMap<String, String>> = mutableMapOf(),
    var isWaiting: Boolean = false,
    var hadToolsInTurn: Boolean = false,
    var palette: Int? = null,
    var hueShift: Int = 0,
    var seatId: String? = null,
    var folderName: String? = null
)

data class PersistedAgent(
    val id: Int,
    val isExternal: Boolean,
    val projectDir: String,
    val jsonlFile: String?,
    val palette: Int?,
    val hueShift: Int,
    val seatId: String?
)
