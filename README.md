# Copilot Pixel Agents

Pixel art office where your GitHub Copilot / Claude Code agents come to life as animated characters — JetBrains plugin.

Port of [pixel-agents](https://github.com/pablodelucca/pixel-agents) VS Code extension to JetBrains IDEs (IntelliJ IDEA, WebStorm, PyCharm, GoLand, Rider, etc.).

![Pixel Agents](https://raw.githubusercontent.com/pablodelucca/pixel-agents/main/icon.png)

## Features

- 🏢 Pixel art office with animated AI agent characters
- 🤖 Each Claude Code terminal gets a unique character that types, reads, and walks around
- 🎨 Full layout editor — paint floors, place walls, arrange furniture
- 🔔 Sound notifications when agents need attention
- 🎭 6 unique character skins with automatic diverse palette assignment
- ✨ Matrix-style spawn/despawn effects

## Requirements

- JetBrains IDE 2024.3+
- [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) installed

## Installation

### From JetBrains Marketplace
*Coming soon*

### Manual Build
```bash
git clone https://github.com/JirakJ/copilot-pixel-agents.git
cd copilot-pixel-agents
cd webview-ui && npm install && npm run build && cd ..
./gradlew buildPlugin
```

The plugin ZIP will be in `build/distributions/`. Install via IDE Settings → Plugins → ⚙️ → Install Plugin from Disk.

## Development

```bash
# Build and launch sandbox IDE
./gradlew runIde

# Debug
./gradlew runIde --debug
```

## Architecture

- **Backend**: Kotlin plugin using IntelliJ Platform SDK
- **Frontend**: React + TypeScript webview via JCEF (JetBrains Chromium Embedded Framework)
- **Communication**: CefMessageRouter bridge (equivalent to VS Code postMessage)
- **Game Engine**: Canvas-based pixel art renderer with character FSM, BFS pathfinding, z-sorted rendering

See [.github/copilot-instructions.md](.github/copilot-instructions.md) for detailed architecture documentation.

## Credits

Based on [pixel-agents](https://github.com/pablodelucca/pixel-agents) by Pablo De Lucca.

## License

MIT
