package com.jirakj.pixelagents

object Constants {
    // Timing (ms)
    const val JSONL_POLL_INTERVAL_MS = 1000L
    const val FILE_WATCHER_POLL_INTERVAL_MS = 500L
    const val PROJECT_SCAN_INTERVAL_MS = 1000L
    const val TOOL_DONE_DELAY_MS = 300L
    const val PERMISSION_TIMER_DELAY_MS = 7000L
    const val TEXT_IDLE_DELAY_MS = 5000L
    const val CLEAR_IDLE_THRESHOLD_MS = 2000L

    // External Session Detection
    const val EXTERNAL_SCAN_INTERVAL_MS = 3000L
    const val EXTERNAL_ACTIVE_THRESHOLD_MS = 120_000L
    const val EXTERNAL_STALE_TIMEOUT_MS = 300_000L
    const val EXTERNAL_STALE_CHECK_INTERVAL_MS = 30_000L

    // Global Session Scanning
    const val GLOBAL_SCAN_ACTIVE_MIN_SIZE = 3_072L
    const val GLOBAL_SCAN_ACTIVE_MAX_AGE_MS = 600_000L

    // Display Truncation
    const val BASH_COMMAND_DISPLAY_MAX_LENGTH = 30
    const val TASK_DESCRIPTION_DISPLAY_MAX_LENGTH = 40

    // User-Level Persistence Paths
    const val LAYOUT_FILE_DIR = ".pixel-agents"
    const val LAYOUT_FILE_NAME = "layout.json"
    const val CONFIG_FILE_NAME = "config.json"
    const val LAYOUT_FILE_POLL_INTERVAL_MS = 2000L

    // Settings Persistence Keys
    const val KEY_SOUND_ENABLED = "pixel-agents.soundEnabled"
    const val KEY_LAST_SEEN_VERSION = "pixel-agents.lastSeenVersion"
    const val KEY_ALWAYS_SHOW_LABELS = "pixel-agents.alwaysShowLabels"
    const val KEY_WATCH_ALL_SESSIONS = "pixel-agents.watchAllSessions"
    const val KEY_AGENTS = "pixel-agents.agents"
    const val KEY_AGENT_SEATS = "pixel-agents.agentSeats"

    // Terminal
    const val TERMINAL_NAME_PREFIX = "Claude Code"

    // Plugin Identifiers
    const val TOOL_WINDOW_ID = "Pixel Agents"

    // PNG Parsing
    const val PNG_ALPHA_THRESHOLD = 2

    // Dismissed Cooldown
    const val DISMISSED_COOLDOWN_MS = 180_000L

    // Copilot Chat Integration
    const val COPILOT_CHAT_TOOL_WINDOW_ID = "Copilot Chat"
    const val COPILOT_POLL_INTERVAL_MS = 2000L
    const val COPILOT_IDLE_THRESHOLD_MS = 5000L
    const val COPILOT_TOOL_NAME = "Copilot Chat"
}
