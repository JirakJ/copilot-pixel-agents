# Copilot Pixel Agents — JetBrains Plugin

Port of [pixel-agents](https://github.com/pablodelucca/pixel-agents) VS Code extension to JetBrains IDEs (IntelliJ IDEA, WebStorm, PyCharm, etc.). Pixel art office where GitHub Copilot / Claude Code agents are animated characters.

## Architecture Overview

```
src/main/
  kotlin/com/jirakj/pixelagents/
    PixelAgentsPlugin.kt              — Plugin lifecycle (projectOpened/projectClosed)
    Constants.kt                      — All backend magic numbers/strings
    types/
      AgentState.kt                   — Agent data class (id, processHandler, projectDir, jsonlFile, etc.)
      PersistedAgent.kt               — Serializable agent snapshot for persistence
      OfficeLayout.kt                 — Layout data model (version, cols, rows, tiles, furniture)
      Messages.kt                     — Sealed class hierarchy for extension↔webview protocol
    services/
      AgentManagerService.kt          — Terminal lifecycle: launch, remove, restore, persist (@Service project-level)
      ConfigPersistenceService.kt     — User-level config (~/.pixel-agents/config.json) (@Service application-level)
      LayoutPersistenceService.kt     — User-level layout (~/.pixel-agents/layout.json), migration, cross-window watch
      FileWatcherService.kt           — JSONL monitoring: VFS listener + polling fallback
      TranscriptParserService.kt      — JSONL parsing: tool_use/tool_result → webview messages
      TimerManagerService.kt          — Waiting/permission timer logic
      AssetLoaderService.kt           — PNG parsing, sprite conversion, catalog building
    toolWindow/
      PixelAgentsToolWindowFactory.kt — ToolWindowFactory, creates JCEF browser panel
      WebviewBridge.kt                — JCEF ↔ Kotlin message dispatch (postMessage equivalent)
    actions/
      ShowPanelAction.kt              — Open/focus the Pixel Agents tool window
      ExportDefaultLayoutAction.kt    — Export current layout as bundled default
  resources/
    META-INF/plugin.xml               — Plugin descriptor
    assets/                           — Sprites, catalog, default layout (copied from original)
    webview/                          — Built React app (Vite output served via JCEF)
    icons/                            — Plugin icon SVGs

webview-ui/                           — React + TypeScript (Vite) — shared with VS Code version
  src/
    bridge/
      extensionBridge.ts              — IExtensionBridge interface (abstracts postMessage)
      vscodeBridge.ts                 — VS Code adapter (acquireVsCodeApi)
      jcefBridge.ts                   — JCEF adapter (window.__jcefBridge)
      bridgeContext.tsx               — React context provider
    constants.ts                      — All webview magic numbers
    App.tsx                           — Composition root
    hooks/                            — useExtensionMessages, useEditorActions, useEditorKeyboard
    office/                           — Game engine (100% platform-agnostic)
      engine/                         — gameLoop, renderer, characters FSM, officeState
      editor/                         — Layout editor tools, undo/redo
      layout/                         — Furniture catalog, serializer, tileMap/pathfinding
      sprites/                        — Sprite data, cache, colorization
      components/                     — OfficeCanvas, ToolOverlay
    components/                       — BottomToolbar, ZoomControls, SettingsModal, DebugView
```

## Core Concepts

**Vocabulary**: Terminal = JetBrains terminal running Claude Code CLI. Session = JSONL conversation file. Agent = webview character bound 1:1 to a terminal.

**JetBrains ↔ Webview**: JCEF bridge using `CefMessageRouter` + `CefMessageRouterHandler`. Kotlin side calls `browser.executeJavaScript()` to push messages. JS side calls `window.cefQuery()` to send messages to Kotlin.

**One-agent-per-terminal**: Each "+ Agent" click → new terminal process (`claude --session-id <uuid>`) → agent creation → poll for JSONL → file watching starts.

## VS Code → JetBrains API Mapping

### Terminal Management
| VS Code | JetBrains |
|---------|-----------|
| `vscode.window.createTerminal({ name, cwd })` | `TerminalView.createLocalShellWidget(project, name)` or `GeneralCommandLine` + `ProcessHandler` |
| `terminal.sendText(cmd)` | `processHandler.processInput.write(cmd.toByteArray())` or `ShellTerminalWidget.executeCommand(cmd)` |
| `terminal.show()` | `ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.show()` |
| `vscode.window.onDidCloseTerminal` | `ProcessHandler.addProcessListener(object : ProcessAdapter() { ... })` |
| `vscode.window.terminals` | `TerminalView.getWidgets(project)` |

### UI & Webview
| VS Code | JetBrains |
|---------|-----------|
| `WebviewViewProvider` | `ToolWindowFactory` + JCEF `JBCefBrowser` |
| `webview.postMessage(msg)` | `browser.cefBrowser.executeJavaScript("window.__onMessage(${json})")` |
| `webview.onDidReceiveMessage` | `CefMessageRouterHandlerAdapter.onQuery()` |
| `webview.html = ...` | `browser.loadURL("file:///path/to/index.html")` |

### State Persistence
| VS Code | JetBrains |
|---------|-----------|
| `context.workspaceState` | `PropertiesComponent.getInstance(project)` |
| `context.globalState` | `PropertiesComponent.getInstance()` (application scope) |
| File-based `~/.pixel-agents/` | Same — `System.getProperty("user.home") + "/.pixel-agents/"` |

### File System
| VS Code | JetBrains |
|---------|-----------|
| `fs.watch()` + polling | `VirtualFileManager.getInstance().addVirtualFileListener()` + `AppExecutorUtil.getAppScheduledExecutorService()` |
| `fs.readFileSync()` | `java.io.File.readText()` or `VfsUtil.loadText()` |
| `fs.writeFileSync()` | Atomic write via `.tmp` + `Files.move(ATOMIC_MOVE)` |

### Commands & Actions
| VS Code | JetBrains |
|---------|-----------|
| `vscode.commands.registerCommand()` | `AnAction` subclass registered in `plugin.xml` |
| `vscode.window.showSaveDialog()` | `FileChooserFactory.getInstance().createSaveFileDialog()` |
| `vscode.window.showOpenDialog()` | `FileChooserFactory.getInstance().createFileChooser()` |
| `vscode.window.showInformationMessage()` | `Messages.showInfoMessage()` or `NotificationGroup.balloon()` |

## Message Protocol (Extension ↔ Webview)

The message protocol is **identical** to the VS Code version. The bridge layer translates transport only.

### Extension → Webview (Kotlin → JS)
| Message | Payload | Purpose |
|---------|---------|---------|
| `agentCreated` | `{ id, folderName?, isExternal? }` | New agent detected |
| `agentClosed` | `{ id }` | Agent removed |
| `existingAgents` | `{ agents, agentMeta, folderNames, externalAgents }` | Restore on reconnect |
| `agentToolStart` | `{ id, toolId, status }` | Tool usage begins |
| `agentToolDone` | `{ id, toolId }` | Tool completed (300ms delay) |
| `agentToolsClear` | `{ id }` | All tools cleared (turn end) |
| `agentStatus` | `{ id, status: 'active'|'waiting' }` | Agent state change |
| `agentToolPermission` | `{ id, toolId?, permission?, toolNames? }` | Permission needed |
| `agentToolPermissionClear` | `{ id }` | Permission dismissed |
| `subagentToolStart` | `{ id, parentToolId, toolId, status }` | Sub-agent tool start |
| `subagentToolDone` | `{ id, parentToolId, toolId }` | Sub-agent tool done |
| `subagentToolPermission` | `{ id, parentToolId, toolId?, toolNames? }` | Sub-agent permission |
| `subagentClear` | `{ id, parentToolId }` | Sub-agent done |
| `layoutLoaded` | `{ layout, wasReset }` | Layout data |
| `settingsLoaded` | `{ soundEnabled, lastSeenVersion, extensionVersion, ... }` | Settings on init |
| `furnitureAssetsLoaded` | `{ catalog, sprites }` | Furniture assets |
| `characterSpritesLoaded` | `{ sprites }` | Character sprites |
| `floorTilesLoaded` | `{ tiles }` | Floor tile sprites |
| `wallTilesLoaded` | `{ tiles }` | Wall tile sprites |
| `externalAssetDirectoriesUpdated` | `{ dirs }` | Asset paths changed |
| `workspaceFolders` | `{ folders }` | Multi-project roots |

### Webview → Extension (JS → Kotlin)
| Message | Payload | Purpose |
|---------|---------|---------|
| `openClaude` | `{ folderPath?, bypassPermissions? }` | Create new agent |
| `focusAgent` | `{ id }` | Focus terminal |
| `closeAgent` | `{ id }` | Remove agent |
| `saveAgentSeats` | `{ seats }` | Persist character palettes |
| `saveLayout` | `{ layout }` | Persist layout |
| `setSoundEnabled` | `{ enabled }` | Toggle sound |
| `setLastSeenVersion` | `{ version }` | Track changelog |
| `setAlwaysShowLabels` | `{ enabled }` | Toggle labels |
| `setWatchAllSessions` | `{ enabled }` | Toggle session scanning |
| `webviewReady` | `{}` | Webview initialized |
| `requestDiagnostics` | `{}` | Debug info request |
| `openSessionsFolder` | `{}` | Open ~/.claude/projects |
| `exportLayout` | `{}` | Export layout file |
| `importLayout` | `{}` | Import layout file |
| `addExternalAssetDirectory` | `{}` | Add asset pack |
| `removeExternalAssetDirectory` | `{ path }` | Remove asset pack |

## JSONL Transcript Processing

JSONL files at `~/.claude/projects/<project-hash>/<session-id>.jsonl`. Project hash = workspace path with `:`/`\`/`/` → `-`.

**Record types**: `assistant` (tool_use blocks), `user` (tool_result), `system` with `subtype: "turn_duration"` (turn-end signal), `progress` with `data.type: "agent_progress"` (sub-agent), `progress` with `data.type: "bash_progress"` (long-running bash), `progress` with `data.type: "mcp_progress"` (MCP tool).

**File watching**: Hybrid VFS listener + 2s polling backup. Partial line buffering for mid-write reads. Tool done messages delayed 300ms to prevent flicker.

**Idle detection**: Two signals: (1) `system` + `subtype: "turn_duration"` — reliable ~98%. (2) Text-idle timer (5s) — for text-only turns.

## JCEF Bridge Implementation

### Kotlin side (WebviewBridge.kt)
```kotlin
class WebviewBridge(private val browser: JBCefBrowser) : Disposable {
    private val router = CefMessageRouter.create()
    
    init {
        router.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser, frame: CefFrame, queryId: Long,
                request: String, persistent: Boolean, callback: CefQueryCallback
            ): Boolean {
                handleMessage(Gson().fromJson(request, JsonObject::class.java))
                callback.success("")
                return true
            }
        }, true)
        browser.jbCefClient.cefClient.addMessageRouter(router)
    }
    
    fun postMessage(message: Any) {
        val json = Gson().toJson(message)
        browser.cefBrowser.executeJavaScript(
            "window.__onExtensionMessage($json)", "", 0
        )
    }
}
```

### JavaScript side (jcefBridge.ts)
```typescript
export class JcefBridge implements IExtensionBridge {
    send(msg: ExtensionMessage): void {
        window.cefQuery({ request: JSON.stringify(msg) });
    }
    
    subscribe(handler: (msg: ExtensionMessage) => void): () => void {
        (window as any).__onExtensionMessage = (data: any) => {
            handler(data);
        };
        return () => { delete (window as any).__onExtensionMessage; };
    }
}
```

## Constants

All magic numbers centralized — never add inline constants:

### Backend (Constants.kt)
```kotlin
// Timing (ms)
const val JSONL_POLL_INTERVAL_MS = 1000L
const val FILE_WATCHER_POLL_INTERVAL_MS = 500L
const val PROJECT_SCAN_INTERVAL_MS = 1000L
const val TOOL_DONE_DELAY_MS = 300L
const val PERMISSION_TIMER_DELAY_MS = 7000L
const val TEXT_IDLE_DELAY_MS = 5000L
const val CLEAR_IDLE_THRESHOLD_MS = 2000L
const val EXTERNAL_SCAN_INTERVAL_MS = 3000L
const val LAYOUT_FILE_POLL_INTERVAL_MS = 2000L

// Paths
const val LAYOUT_FILE_DIR = ".pixel-agents"
const val LAYOUT_FILE_NAME = "layout.json"
const val CONFIG_FILE_NAME = "config.json"
const val TERMINAL_NAME_PREFIX = "Claude Code"
```

### Webview (constants.ts) — reused from original
```typescript
TILE_SIZE = 16
DEFAULT_COLS = 20, DEFAULT_ROWS = 11
MAX_COLS = 64, MAX_ROWS = 64
WALK_SPEED_PX_PER_SEC = 48
MATRIX_EFFECT_DURATION_SEC = 0.3
ZOOM_MIN = 1, ZOOM_MAX = 10
UNDO_STACK_MAX_SIZE = 50
```

## Kotlin/JVM Conventions

- **Language**: Kotlin (JVM target 21)
- **Build**: Gradle with IntelliJ Platform Plugin 2.3+
- **Serialization**: Gson for JSON (JSONL parsing, message protocol, layout persistence)
- **Threading**: Use `ApplicationManager.getApplication().invokeLater()` for UI thread, `AppExecutorUtil` for background tasks. NEVER block EDT.
- **Disposable pattern**: All services implement `Disposable`. Use `Disposer.register()` for child disposables.
- **Project services**: `@Service(Service.Level.PROJECT)` for per-project state (agents, layout, file watchers)
- **Application services**: `@Service(Service.Level.APP)` for global state (config persistence)
- **Logging**: `com.intellij.openapi.diagnostic.Logger` — `LOG.info()`, `LOG.warn()`, `LOG.error()`
- **No enum classes for data** — use sealed classes or const val strings (matching TypeScript `as const` pattern)

## Build & Dev

```sh
# Initial setup
./gradlew wrapper                    # Ensure Gradle wrapper
cd webview-ui && npm install && cd ..

# Build webview (React → src/main/resources/webview/)
cd webview-ui && npm run build && cd ..

# Build & run plugin
./gradlew buildPlugin                # Build plugin ZIP
./gradlew runIde                     # Launch sandbox IDE with plugin

# Development
./gradlew runIde --debug             # Debug mode
```

Webview build output must go to `src/main/resources/webview/` so it's bundled into the plugin JAR.

## Asset System

Assets are loaded from plugin resources (`/assets/` in JAR). Same PNG format as VS Code version:
- `assets/characters/char_0.png`–`char_5.png` — 6 pre-colored character spritesheets
- `assets/floors.png` — 7 floor tile patterns (112×16)
- `assets/walls.png` — Wall auto-tile (64×128, 4×4 grid)
- `assets/furniture/` — Individual furniture PNGs
- `assets/furniture-catalog.json` — Furniture metadata
- `assets/default-layout.json` — Default office layout

PNG → SpriteData conversion uses Java AWT `ImageIO` + `BufferedImage` instead of pngjs.

**Load order**: `characterSpritesLoaded` → `floorTilesLoaded` → `wallTilesLoaded` → `furnitureAssetsLoaded` → `layoutLoaded`.

## Key Implementation Notes

- JCEF is built into IntelliJ 2021.1+ — no external dependency needed
- `JBCefBrowser` must be created on EDT
- File operations on `~/.claude/projects/` use standard `java.io.File` (not VFS) since they're outside project
- Terminal widget approach: prefer `ShellTerminalWidget` from `org.jetbrains.plugins.terminal` for native terminal integration
- Agent IDs: positive for main agents, negative (from -1 down) for sub-agents
- Sub-agents share parent's palette + hueShift, spawn at closest free seat
- `/clear` creates NEW JSONL file — detect by scanning project directory for new files
- Layout shared across all IDE windows via `~/.pixel-agents/layout.json` with atomic write + file watching
- Sound notifications via webview Web Audio API (works in JCEF)

## Webview Abstraction Strategy

The webview code is designed to be platform-agnostic. Only the bridge layer changes:

1. **`IExtensionBridge`** interface abstracts `postMessage`/`onMessage`
2. **`BridgeContext`** React context provides the bridge to all components
3. **Runtime detection**: `typeof window.cefQuery !== 'undefined'` → JCEF
4. All `vscode.postMessage()` calls replaced with `bridge.send()`
5. All `window.addEventListener('message', ...)` replaced with `bridge.subscribe()`

Files requiring bridge refactor (18 call sites):
- `App.tsx` (6), `useExtensionMessages.ts` (2), `useEditorActions.ts` (2)
- `OfficeCanvas.tsx` (1), `SettingsModal.tsx` (5), `BottomToolbar.tsx` (2)

## Plugin Distribution

- Package as `.zip` via `./gradlew buildPlugin`
- Publish to JetBrains Marketplace via `./gradlew publishPlugin`
- Compatible with all IntelliJ-based IDEs (IC, IU, WS, PS, PC, RM, CL, GO, RD)
- Minimum IDE version: 2024.3 (for stable JCEF APIs)
