# Copilot Pixel Agents

A JetBrains plugin that brings your AI coding agents to life as animated pixel art characters in a charming virtual office.

Port of [pixel-agents](https://github.com/pablodelucca/pixel-agents) VS Code extension to JetBrains IDEs.

## Features

- 🏢 **Pixel art office** — retro-style workspace rendered on HTML5 canvas via JCEF
- 🤖 **Claude Code integration** — each terminal session gets a unique animated character
- 🧑‍💻 **GitHub Copilot Chat integration** — monitor Copilot activity with a dedicated character
- 🎨 **Layout editor** — paint floors, place walls, arrange 38+ furniture items, customize colors (HSBC)
- 🎭 **6 unique character skins** with diverse palette assignment and hue shifting
- ✨ **Matrix-style effects** — digital rain spawn/despawn animations
- 🔔 **Sound notifications** — audio alerts when agents need attention (permission requests, idle)
- 📦 **Custom asset packs** — load external furniture and decoration directories
- 💾 **Persistent layout** — shared across all IDE windows (`~/.pixel-agents/layout.json`)
- 🔄 **Layout import/export** — share office layouts as JSON files

## Supported IDEs

Any JetBrains IDE **2024.3+** with JCEF support:

- IntelliJ IDEA (Community & Ultimate)
- WebStorm
- PyCharm
- GoLand
- Rider
- PhpStorm
- CLion
- RubyMine
- DataGrip

## Requirements

- **JetBrains IDE 2024.3+**
- **[Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code)** — for terminal agents (install via `npm install -g @anthropic-ai/claude-code`)
- **GitHub Copilot plugin** (optional) — for Copilot Chat monitoring
- **Node.js 18+** — required only when building from source

## Installation

### From JetBrains Marketplace

*Coming soon — the plugin will be published to the JetBrains Marketplace.*

### Install from ZIP

1. Download the latest release ZIP from [GitHub Releases](https://github.com/JirakJ/copilot-pixel-agents/releases)
2. In your IDE: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded ZIP file
4. Restart the IDE

### Build from Source

```bash
git clone https://github.com/JirakJ/copilot-pixel-agents.git
cd copilot-pixel-agents
cd webview-ui && npm install && cd ..
./gradlew buildPlugin
```

The plugin ZIP will be at `build/distributions/copilot-pixel-agents-*.zip`.

## Usage

### Getting Started

1. Open the **Pixel Agents** tool window from the bottom panel (or via **View → Tool Windows → Pixel Agents**)
2. The pixel art office loads with the default layout

### Adding Agents

- **Claude Code**: Click **+ Agent** to launch a new Claude Code terminal. A character spawns in the office and begins animating based on real-time activity:
  - **Typing** when writing/editing files or running bash commands
  - **Reading** when searching files (grep/glob) or fetching web content
  - **Walking** when idle (wandering around the office)
  - **Permission bubble** ("...") when Claude needs your approval
  - **Waiting bubble** (✓) when a turn completes

- **GitHub Copilot**: Click **+ Copilot** to start monitoring Copilot Chat. The plugin detects when Copilot Chat is visible and tracks activity changes.

### Customizing the Office

Click **Layout** to enter edit mode:

- **Floor tool**: Paint floor tiles with 7 patterns, customize colors via HSBC sliders
- **Wall tool**: Click to add/remove walls, customize wall color
- **Erase tool**: Remove tiles (set to void)
- **Furniture**: Place 38+ items (desks, chairs, monitors, bookshelves, plants, etc.)
  - **R** key to rotate, **T** key to toggle state (on/off)
  - Drag to move placed furniture
  - HSBC color sliders for per-item colorization
- **Expand grid**: Click the dashed border to grow the office (up to 64×64)
- **Undo/Redo**: Ctrl+Z / Ctrl+Y (50 levels)
- **Save/Reset**: Top action bar when changes are pending

### Settings

Click **Settings** for:
- Sound notification toggle
- Debug view toggle
- Always show activity labels
- Watch all Claude sessions (not just ones started from this window)
- External asset directory management
- Layout import/export

## Development

```bash
# One-time setup
cd webview-ui && npm install && cd ..

# Build and launch sandbox IDE (with hot reload)
./gradlew runIde

# Fast compile check
./gradlew compileKotlin

# Full build with plugin ZIP
./gradlew buildPlugin
```

### Project Structure

```
src/main/kotlin/         — Kotlin backend (IntelliJ Platform SDK)
  toolWindow/            — JCEF browser setup, WebviewBridge message dispatch
  services/              — Agent lifecycle, file watching, transcript parsing,
                           timer management, asset loading, layout persistence,
                           Copilot monitoring, config management
  actions/               — IDE actions (Show Panel, Export Default Layout)
  types/                 — AgentState, PersistedAgent data classes

webview-ui/              — React + TypeScript frontend (Vite build)
  src/office/            — Game engine (canvas renderer, character FSM, pathfinding)
  src/components/        — React UI (toolbar, settings, zoom, debug)
  src/hooks/             — Extension message handling, editor state
  src/bridge/            — Platform abstraction (VS Code / JCEF / Browser)
```

### Architecture

| Layer | Technology | Details |
|-------|-----------|---------|
| Backend | Kotlin + IntelliJ Platform SDK | Services, terminal management, file I/O |
| Frontend | React + TypeScript + Vite | Canvas game engine, UI overlays |
| Rendering | JCEF (Chromium) | `file://` protocol with cefQuery stub pattern |
| Communication | JBCefJSQuery | Bidirectional JSON messages (equivalent to VS Code `postMessage`) |
| Game Engine | HTML5 Canvas | Pixel-perfect rendering, z-sorted entities, BFS pathfinding |
| Agent Tracking | JSONL file watching | Claude Code transcripts at `~/.claude/projects/` |

See [.github/copilot-instructions.md](.github/copilot-instructions.md) for detailed architecture documentation.

## Credits

Based on [pixel-agents](https://github.com/pablodelucca/pixel-agents) by Pablo De Lucca.

## License

MIT
