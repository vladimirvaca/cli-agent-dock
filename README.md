# Agent Hub for JetBrains

![Build](https://github.com/vladimirvaca/agent-hub-jetbrains-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
**Agent Hub** brings your favorite coding agent right into your JetBrains IDE. It
adds a dedicated tool window to the **right side bar** — next to Database, Gradle,
and friends — with an embedded terminal that automatically launches a coding agent
CLI, ready to work in your project.

By default it opens with **Claude Code**. Prefer a different agent? Pick it in the
settings and Agent Hub **remembers your choice** across projects and restarts.
Support for more agents (GitHub Copilot CLI, OpenAI Codex CLI, OpenCode, and more)
is on the roadmap.

Agent Hub is **cross-platform** and works on **Windows, macOS, and Linux**.
<!-- Plugin description end -->

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

Agent Hub is meant to be a *hub* for many coding agents. Planned:

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

If the selected agent's CLI can't be found, Agent Hub will tell you instead of
opening a broken terminal.

## Usage

1. Open the **Agent Hub** tool window from the **right** side bar.
2. A terminal opens in your project directory and launches your preferred agent
   (Claude Code by default).
3. To change the agent, go to **Settings/Preferences > Tools > Agent Hub** and pick
   your preferred agent. Your choice is remembered for next time.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Agent Hub"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/vladimirvaca/agent-hub-jetbrains-plugin/releases/latest) and install it manually using
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
guidelines.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
