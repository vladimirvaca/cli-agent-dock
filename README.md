# CLI Agent Dock

**Your coding agent's CLI, docked right inside the IDE.**

[![Build](https://github.com/vladimirvaca/cli-agent-dock/workflows/Build/badge.svg)](https://github.com/vladimirvaca/cli-agent-dock/actions)
[![Version](https://img.shields.io/jetbrains/plugin/v/32765.svg)](https://plugins.jetbrains.com/plugin/32765)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32765.svg)](https://plugins.jetbrains.com/plugin/32765)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/32765.svg)](https://plugins.jetbrains.com/plugin/32765/reviews)
[![License](https://img.shields.io/github/license/vladimirvaca/cli-agent-dock.svg)](./LICENSE)

<!-- The Marketplace plugin description is maintained directly in src/main/resources/META-INF/plugin.xml -->

Run **Claude Code**, **GitHub Copilot CLI**, **OpenAI Codex CLI** or **OpenCode** in a
dedicated tool window of any JetBrains IDE: no more alt-tabbing to a terminal to talk
to your agent.

**CLI Agent Dock** adds a tool window to the right side bar (next to Database, Gradle
and the rest) with an embedded terminal that launches straight into your coding agent's
CLI, already in your project directory. It also connects that agent to the IDE: send
it the file and lines you are reading, see every file it changes with one-click diffs,
and get a notification when it stops to wait for you.

- **One plugin for four agents.** Switch from the dropdown, or run several side by side
  in separate tabs.
- **Free and open source** (MIT), with no account, telemetry or extra service: the
  plugin only launches the agent CLI you already have.
- **Works in every JetBrains IDE** (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider,
  PhpStorm, RubyMine, CLion, …) on **Windows, macOS and Linux**.

<!-- TODO: add a screenshot or GIF of the tool window in action -->

---

## Features

| | |
|---|---|
| 🧭 **Right-side tool window** | Lives alongside Database, Gradle, and other IDE panels — your agent is always one click away. |
| 🖥️ **Embedded agent terminal** | A real terminal inside the IDE, opened in your project directory and pre-launched with your agent CLI. |
| 🗂️ **Multiple sessions** | Open several agent tabs at once, each independent and closeable. |
| 🏷️ **Named tabs** | Double-click a tab (or right-click ▸ Rename Session) to call it "Auth refactor" instead of "Claude Code (2)". The name is also used in notifications. |
| 📝 **Live "files changed" panel** | See what the agent created, modified, or deleted as it works, with VCS-style coloring: click a file to open its diff, or jump to the commit view. |
| 🎯 **Send context to the agent** | Drop the current file and selected line range into the running agent's prompt, ready for your instruction — no copy-pasting paths. |
| 🔔 **Nudge when it's waiting** | Get told when an agent goes quiet in a tab you're not watching — in the IDE, or as a system notification when you've switched to another app. |
| ⚡ **Keyboard-first** | Every dock action is in Find Action and rebindable in Keymap, with shortcuts for sending context and focusing the terminal. |
| ⌨️ **Esc stays with the agent** | Esc interrupts the agent instead of leaving the terminal; double-tap Esc to jump back to the editor, or Shift+Esc to hide the dock. |
| 🤖 **Claude Code by default** | Zero configuration to get started — plus Copilot CLI, Codex CLI, and OpenCode. |
| ⚙️ **Choose your agent** | Set a preferred agent; the choice is persisted globally and remembered everywhere. |
| 🌍 **Cross-platform** | Windows, macOS, and Linux. |

## Supported agents

| Agent | Command | Install (one option; see the agent's docs for others) | Status |
|---|---|---|---|
| [Claude Code](https://docs.claude.com/en/docs/claude-code) | `claude` | `npm install -g @anthropic-ai/claude-code` | ✅ Default |
| [GitHub Copilot CLI](https://docs.github.com/en/copilot) | `copilot` | `npm install -g @github/copilot` | ✅ Supported |
| [OpenAI Codex CLI](https://developers.openai.com/codex/cli) | `codex` | `npm install -g @openai/codex` | ✅ Supported |
| [OpenCode](https://opencode.ai/docs/) | `opencode` | `npm install -g opencode-ai` | ✅ Supported |
| Custom / user-defined agents | | | 🗺️ Roadmap |

Each agent keeps its own login, model and configuration: CLI Agent Dock launches the
CLI exactly as you would in a terminal, so whatever works there works in the dock.

## Requirements

- A JetBrains IDE, version **2025.2 or newer** (IntelliJ IDEA, PyCharm, WebStorm, GoLand,
  Rider, PhpStorm, RubyMine, CLion, …).
- At least one of the agent CLIs above, installed and signed in. Check it from a
  terminal first, for example `claude --version`.

You don't need to edit your `PATH` for the IDE: besides `PATH`, the plugin looks in the
usual install locations of npm, Homebrew, WinGet, Scoop, Chocolatey, Bun and each
agent's own installer. If the selected agent still can't be found, the dock tells you
instead of opening a broken terminal.

## Usage

1. Open the **CLI Agent Dock** tool window from the **right** side bar.
2. A terminal opens in your project directory and launches your preferred agent
   (Claude Code by default). Open more sessions from new tabs as needed, and
   double-click a tab to give it a name, such as the task or branch it is working on.
   Right-clicking a tab also offers Restart.
3. To put the agent on the code you're reading, select some lines and run
   **Send File Reference to Agent** (editor right-click, or <kbd>Ctrl+Alt+Shift+A</kbd> /
   <kbd>Cmd+Alt+Shift+A</kbd>). The file and line range land in the agent's prompt without
   being submitted, so you just type what you want done and press Enter.
4. As the agent edits files, a **Files changed** panel appears below the terminal.
   Click a file to open its diff, use the file icon to open the file itself, or click
   the header to jump to the commit view.
5. If an agent goes quiet in a tab you're not watching — usually because it wants
   approval — a notification offers to jump back to it. Away in another application, you
   get a system notification and a taskbar/Dock nudge instead, so it still reaches you.
6. To change the agent, go to **Settings/Preferences ▸ Tools ▸ CLI Agent Dock** and pick
   your preferred agent. Your choice is remembered for next time.

### Keyboard shortcuts

| Action | Windows / Linux | macOS |
|---|---|---|
| Send File Reference to Agent | <kbd>Ctrl+Alt+Shift+A</kbd> | <kbd>Cmd+Alt+Shift+A</kbd> |
| Focus Agent Terminal | <kbd>Ctrl+Alt+Shift+D</kbd> | <kbd>Cmd+Alt+Shift+D</kbd> |
| Back to the editor (from the agent terminal) | <kbd>Esc</kbd> <kbd>Esc</kbd> | <kbd>Esc</kbd> <kbd>Esc</kbd> |
| Hide the dock | <kbd>Shift+Esc</kbd> | <kbd>Shift+Esc</kbd> |

All of the dock's actions are available in **Find Action** (<kbd>Ctrl+Shift+A</kbd> /
<kbd>Cmd+Shift+A</kbd>) and can be rebound under
**Settings ▸ Keymap ▸ Plugins ▸ CLI Agent Dock**.

## FAQ

**How is this different from the official Claude Code or GitHub Copilot plugins?**
Those plugins integrate one vendor's agent. CLI Agent Dock gives you the same docked
workflow for several agents, so you can switch or run them side by side without
learning a different plugin for each one.

**Does the plugin send my code anywhere?**
No. The plugin doesn't make any network calls of its own. It starts the agent CLI in a
terminal, and the agent talks to its own service exactly as it does when you run it
yourself.

**My agent is installed but the dock says it can't be found.**
Make sure it runs from a regular terminal (`claude --version`, `codex --version`, …),
then click **Retry** in the dock. If it lives somewhere unusual, please
[open an issue](https://github.com/vladimirvaca/cli-agent-dock/issues) with the path.

## Installation

- **From the IDE:**

  <kbd>Settings/Preferences</kbd> ▸ <kbd>Plugins</kbd> ▸ <kbd>Marketplace</kbd> ▸ search for **"CLI Agent Dock"** ▸ <kbd>Install</kbd>

- **From JetBrains Marketplace:**

  Visit [the plugin page](https://plugins.jetbrains.com/plugin/32765) and click
  <kbd>Install to ...</kbd> while your IDE is running, or download the
  [latest release](https://plugins.jetbrains.com/plugin/32765/versions) and install it
  manually via
  <kbd>Settings/Preferences</kbd> ▸ <kbd>Plugins</kbd> ▸ <kbd>⚙️</kbd> ▸ <kbd>Install plugin from disk...</kbd>

- **Manually from GitHub:**

  Download the [latest release](https://github.com/vladimirvaca/cli-agent-dock/releases/latest)
  and install it via
  <kbd>Settings/Preferences</kbd> ▸ <kbd>Plugins</kbd> ▸ <kbd>⚙️</kbd> ▸ <kbd>Install plugin from disk...</kbd>

## Roadmap

CLI Agent Dock is meant to be a *hub* for many coding agents, and to make running one
inside the IDE genuinely better than running it in a terminal tab.

**Planned:** per-agent configuration (model, flags, environment variables), user-defined
custom agents, a per-project agent override, relaunching on an agent change in Settings,
and session restore across restarts.

> Four agents work end-to-end today, and the architecture is generalized enough that
> adding the next one is a registry entry rather than new plumbing.

## Development

This plugin is built with Kotlin and the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).

```bash
./gradlew runIde          # launch a sandbox IDE with the plugin loaded
./gradlew build           # compile and build
./gradlew test            # run tests
./gradlew buildPlugin     # produce a distributable ZIP (build/distributions)
```

Contributions are welcome — see [AGENTS.md](./AGENTS.md) for the architecture,
conventions, and contribution guidelines, and [RELEASING.md](./RELEASING.md) for the
versioning strategy and release process.

## License

CLI Agent Dock is licensed under the [MIT License](./LICENSE).

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template

> **Developed with ❤️ by vladimirvaca 👽**