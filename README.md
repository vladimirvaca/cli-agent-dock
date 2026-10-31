# CLI Agent Dock

**Your coding agent's CLI, docked right inside the IDE.**

[![Build](https://github.com/vladimirvaca/cli-agent-dock/workflows/Build/badge.svg)](https://github.com/vladimirvaca/cli-agent-dock/actions)
[![Version](https://img.shields.io/jetbrains/plugin/v/32765.svg)](https://plugins.jetbrains.com/plugin/32765)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32765.svg)](https://plugins.jetbrains.com/plugin/32765)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/32765.svg)](https://plugins.jetbrains.com/plugin/32765/reviews)
[![License](https://img.shields.io/github/license/vladimirvaca/cli-agent-dock.svg)](./LICENSE)

<!-- The Marketplace plugin description is maintained directly in src/main/resources/META-INF/plugin.xml -->

No more alt-tabbing between your IDE and a terminal tab to talk to your agent.
**CLI Agent Dock** adds a dedicated tool window to your JetBrains IDE — right next to
Database, Gradle, and friends — with an embedded terminal that launches straight into a
coding agent CLI, already sitting in your project directory.

By default it opens with **Claude Code**; **GitHub Copilot CLI**, **OpenAI Codex CLI**
and **OpenCode** are supported too. Pick an agent from the dropdown and open as many
sessions as you like — each runs in its own closeable tab, so you can drive several
agents side by side. Your preferred agent is **remembered** across projects and restarts.

CLI Agent Dock is **cross-platform** and works on **Windows, macOS, and Linux**.

<!-- TODO: add a screenshot or GIF of the tool window in action -->

---

## Features

| | |
|---|---|
| 🧭 **Right-side tool window** | Lives alongside Database, Gradle, and other IDE panels — your agent is always one click away. |
| 🖥️ **Embedded agent terminal** | A real terminal inside the IDE, opened in your project directory and pre-launched with your agent CLI. |
| 🗂️ **Multiple sessions** | Open several agent tabs at once, each independent and closeable. |
| 📝 **Live "files changed" panel** | See what the agent created, modified, or deleted as it works, with VCS-style coloring and one-click jumps to the file or the commit view. |
| 📜 **Always-visible scrollbar** | Keep track of scrollback length in the agent terminal at a glance. |
| 🎯 **Send context to the agent** | Drop the current file and selected line range into the running agent's prompt, ready for your instruction — no copy-pasting paths. |
| 🔔 **Nudge when it's waiting** | Get told when an agent goes quiet in a tab you're not watching, instead of finding out minutes later. |
| ⚡ **Keyboard-first** | Every dock action is in Find Action and rebindable in Keymap, with shortcuts for sending context and focusing the terminal. |
| ⌨️ **Esc stays with the agent** | Esc interrupts the agent instead of leaving the terminal; double-tap Esc to jump back to the editor, or Shift+Esc to hide the dock. |
| 🤖 **Claude Code by default** | Zero configuration to get started — plus Copilot CLI, Codex CLI, and OpenCode. |
| ⚙️ **Choose your agent** | Set a preferred agent; the choice is persisted globally and remembered everywhere. |
| 🌍 **Cross-platform** | Windows, macOS, and Linux. |

## Supported agents

| Agent | CLI | Status |
|---|---|---|
| [Claude Code](https://docs.claude.com/en/docs/claude-code) | `claude` | ✅ Supported (default) |
| [GitHub Copilot CLI](https://docs.github.com/en/copilot) | `copilot` | ✅ Supported |
| [OpenAI Codex CLI](https://developers.openai.com/codex/cli) | `codex` | ✅ Supported |
| [OpenCode](https://opencode.ai/docs/) | `opencode` | ✅ Supported |
| Custom / user-defined agents | | 🗺️ Roadmap |

## Requirements

- A JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, etc.).
- The CLI for the agent you want to use, installed and available on your `PATH`. For
  example, for the default agent:
  - **Claude Code** — install from the
    [Claude Code documentation](https://docs.claude.com/en/docs/claude-code) and verify
    with `claude --version` in your terminal.

If the selected agent's CLI can't be found, CLI Agent Dock tells you instead of opening
a broken terminal.

## Usage

1. Open the **CLI Agent Dock** tool window from the **right** side bar.
2. A terminal opens in your project directory and launches your preferred agent
   (Claude Code by default). Open more sessions from new tabs as needed.
3. To put the agent on the code you're reading, select some lines and run
   **Send File Reference to Agent** (editor right-click, or <kbd>Ctrl+Alt+Shift+A</kbd> /
   <kbd>Cmd+Alt+Shift+A</kbd>). The file and line range land in the agent's prompt without
   being submitted, so you just type what you want done and press Enter.
4. As the agent edits files, a **Files changed** panel appears below the terminal —
   click a row to open the file, or the header to jump to the commit view.
5. If an agent goes quiet in a tab you're not watching — usually because it wants
   approval — a notification offers to jump back to it.
6. To change the agent, go to **Settings/Preferences ▸ Tools ▸ CLI Agent Dock** and pick
   your preferred agent. Your choice is remembered for next time.

All of the dock's actions are available in **Find Action** (<kbd>Ctrl+Shift+A</kbd>) and can
be rebound under **Settings ▸ Keymap ▸ Plugins ▸ CLI Agent Dock**.

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