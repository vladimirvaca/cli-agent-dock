# CLI Agent Dock for JetBrains

![Build](https://github.com/vladimirvaca/cli-agent-dock/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/32765.svg)](https://plugins.jetbrains.com/plugin/32765)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32765.svg)](https://plugins.jetbrains.com/plugin/32765)

<!-- The Marketplace plugin description is maintained directly in src/main/resources/META-INF/plugin.xml -->
**CLI Agent Dock** brings your favorite coding agent right into your JetBrains IDE. It
adds a dedicated tool window to the **right side bar** — next to Database, Gradle,
and friends — with an embedded terminal that automatically launches a coding agent
CLI, ready to work in your project.

By default it opens with **Claude Code**, and **GitHub Copilot CLI** is supported too.
Pick an agent from the dropdown and open as many sessions as you like — each runs in
its own closeable tab. Your preferred agent is **remembered** across projects and
restarts. Support for more agents (OpenAI Codex CLI, OpenCode, and more) is on the
roadmap.

CLI Agent Dock is **cross-platform** and works on **Windows, macOS, and Linux**.

---

## Features

- 🧭 **Right-side tool window** — lives alongside Database, Gradle, and other IDE
  panels, so your agent is always one click away.
- 🖥️ **Embedded agent terminal** — a real terminal inside the IDE, opened in your
  project directory and pre-launched with your agent CLI.
- 🤖 **Claude Code by default** — zero configuration to get started.
- ⚙️ **Choose your agent** — set a preferred agent; the choice is persisted globally.
- 💾 **Remembers your preference** — pick once, and it's used everywhere.
- 🌍 **Cross-platform** — Windows, macOS, and Linux.

## Roadmap

CLI Agent Dock is meant to be a *hub* for many coding agents. Planned:

- Additional agents: GitHub Copilot CLI, OpenAI Codex CLI, OpenCode, and others.
- Multiple concurrent agent sessions / terminal tabs.
- Per-agent configuration (model, flags, environment variables).
- User-defined custom agents.
- Quick actions (restart agent, new session, open agent docs).

> The current release focuses on getting Claude Code working end-to-end, with the
> architecture already generalized so new agents are easy to add.

## Requirements

- A JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, etc.).
- The CLI for the agent you want to use must be installed and available on your
  `PATH`. For the default:
  - **Claude Code** — install from the
    [Claude Code documentation](https://docs.claude.com/en/docs/claude-code)
    and verify with `claude --version` in your terminal.

If the selected agent's CLI can't be found, CLI Agent Dock will tell you instead of
opening a broken terminal.

## Usage

1. Open the **CLI Agent Dock** tool window from the **right** side bar.
2. A terminal opens in your project directory and launches your preferred agent
   (Claude Code by default).
3. To change the agent, go to **Settings/Preferences > Tools > CLI Agent Dock** and pick
   your preferred agent. Your choice is remembered for next time.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "CLI Agent Dock"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32765) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/32765/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/vladimirvaca/cli-agent-dock/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Development

This plugin is built with Kotlin and the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).

```bash
./gradlew runIde          # launch a sandbox IDE with the plugin loaded
./gradlew build           # compile and build
./gradlew test            # run tests
./gradlew buildPlugin     # produce a distributable ZIP (build/distributions)
```

See [AGENTS.md](./AGENTS.md) for the architecture, conventions, and contribution
guidelines, and [RELEASING.md](./RELEASING.md) for the versioning strategy and
release process.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
