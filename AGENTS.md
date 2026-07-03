# AGENTS.md

Engineering guide for the **Agent Hub** JetBrains plugin. This file is the source
of truth for AI coding assistants (Claude Code, Copilot, Codex, etc.) and human
contributors working on this repository. Read it before making changes.

---

## 1. What this plugin does

Agent Hub adds a **tool window to the right side bar** of any JetBrains IDE
(IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, etc.) that opens an embedded
terminal already running a **coding agent CLI**.

- **Default agent:** Claude Code (`claude`).
- **User-selectable:** the user can pick a preferred agent; the choice is
  **remembered** across restarts and projects.
- **Multiplatform:** must work on **Windows, macOS, and Linux**.

The long-term vision is a *hub* for many agents (Claude Code, GitHub Copilot CLI,
OpenAI Codex CLI, OpenCode, etc.). The current milestone focuses on Claude Code
working end-to-end with the architecture already generalized for more agents.

---

## 2. Current status

**Milestone 1 is implemented.** The template sample code (`My*`) has been removed and
replaced with the real feature: a right-anchored "Agent Hub" tool window that launches
Claude Code in an embedded terminal, with a preferred-agent setting persisted globally,
cross-platform executable resolution, and a startup loader. See §4 for the actual
layout and §7 for what's done vs. next.

---

## 3. Tech stack & key coordinates

| Item | Value |
| --- | --- |
| Language | Kotlin (JVM) |
| Build | Gradle (Kotlin DSL) + IntelliJ Platform Gradle Plugin `2.x` |
| Target platform | IntelliJ IDEA `2025.2.6.2` (see `build.gradle.kts`) |
| Kotlin plugin | `2.1.20` (see `settings.gradle.kts`) |
| Plugin id | `com.github.vladimirvaca.agenthubjetbrainsplugin` |
| Base package | `com.github.vladimirvaca.agenthubjetbrainsplugin` |
| Min IDE build | `since-build` in `plugin.xml` (keep in sync with target) |

### Useful commands
```bash
./gradlew build              # compile + build the plugin
./gradlew runIde             # launch a sandbox IDE with the plugin loaded
./gradlew test               # run tests
./gradlew verifyPlugin       # IntelliJ plugin verifier
./gradlew buildPlugin        # produce distributable ZIP in build/distributions
```
On Windows use `gradlew.bat` or the `.run/` run configurations.

---

## 4. Architecture (as built)

The design is **agent-agnostic**: everything specific to Claude Code is data on the
`Agent` model, so adding Copilot/Codex/OpenCode later is a registry entry, not new
plumbing. Actual layout:

```
AgentHubBundle.kt                i18n bundle object (messages/AgentHubBundle.properties).

toolWindow/
  AgentHubToolWindowFactory.kt   ToolWindowFactory (DumbAware), anchored right.
  AgentHubToolWindowPanel.kt     Panel: toolbar (agent picker, restart, IDE-version label)
                                 + hosts the terminal view; handles relaunch and the
                                 "agent not found" state.

agent/
  Agent.kt                       Model: id, displayName, command, args, enabled,
                                 readyMarkers, readyTimeoutMs. Builds the launch command
                                 line (absolute path, quoted if needed).
  AgentRegistry.kt               Known agents; Claude Code is the enabled default, others
                                 stubbed disabled. isAvailable() delegates to the resolver.
  AgentExecutableResolver.kt     Resolves the executable via PATH + well-known install dirs.

terminal/
  AgentTerminalRunner.kt         Creates the embedded terminal and sends the agent command.
  AgentTerminalView.kt           CardLayout: loading spinner until the agent is ready,
                                 then the terminal card.

settings/
  AgentSettingsState.kt          PersistentStateComponent (APP level) storing preferred agent id.
  AgentSettingsConfigurable.kt   Settings UI under Settings > Tools > Agent Hub.

util/
  IdeInfo.kt                     Reports the running IDE product/version/build.
```

### 4.1 Tool window (right anchor)
Registered in `plugin.xml` with `anchor="right"` (alongside Database, Gradle, etc.),
`icon="/icons/agentHub.svg"` (13×13, with a `_dark` variant), factory
`AgentHubToolWindowFactory`.

### 4.2 Embedded terminal (verified API)
- Depends on the bundled Terminal plugin: `<depends>org.jetbrains.plugins.terminal</depends>`
  in `plugin.xml` and `bundledPlugin("org.jetbrains.plugins.terminal")` in `build.gradle.kts`.
- The API surface changes between releases, so it was verified by decompiling the
  Terminal plugin jar shipped with `intellijIdea("2025.2.6.2")`. What we use:
  - `LocalTerminalDirectRunner.createTerminalRunner(project)`
  - `runner.startShellTerminalWidget(disposable, ShellStartupOptions, deferSessionStartUntilUiShown=false)`
    → returns `com.intellij.terminal.ui.TerminalWidget`.
  - `widget.sendCommandToExecute(cmd)` — queues the command until the shell is ready.
  - `widget.getText()` — live terminal buffer, polled for the readiness marker.
- We start a normal shell and send the agent command to it (rather than replacing the
  shell with the agent), so the terminal survives the agent exiting and stays cross-platform.
- Working directory defaults to `project.basePath` (fallback: user home).

### 4.3 Startup loader / readiness
- `AgentTerminalView` uses a `CardLayout` with a "loading" card (a big
  `AsyncProcessIcon`) and a "terminal" card. The terminal starts **eagerly**
  (`defer = false`) so it boots and renders at correct size while hidden.
- An `Alarm` polls `widget.getText()` for any `Agent.readyMarkers` substring; on match
  (or after `Agent.readyTimeoutMs`, default 15s) it flips to the terminal card and
  focuses it. Agents with no markers show the terminal directly (no loader).
- Claude Code markers: `"? for shortcuts"`, `"Welcome to Claude Code"`. These are
  version-sensitive; the timeout is the safety net if they change.

### 4.4 Multiplatform rules
- Do **not** hardcode `/bin/bash`, `cmd.exe`, or absolute shell paths. The Terminal
  plugin picks the user's default shell; we only send the agent command to it.
- Executable resolution (`AgentExecutableResolver`): try `PathEnvironmentVariableUtil.findInPath`
  first, then well-known install dirs (`~/.local/bin`, npm global, Homebrew, etc.),
  honoring Windows `.exe`/`.cmd`/`.bat`. **Launch by absolute path** so a stale/minimal
  IDE PATH (common with `runIde` sandboxes and GUI-launched IDEs) doesn't cause a false
  "not found" or a broken terminal. If unresolved, show the friendly not-found panel.
- Use `com.intellij.openapi.util.SystemInfo` for OS branching.

### 4.5 Persisting the preferred agent
- Application-level `PersistentStateComponent` (`agentHub.xml`) — the preferred agent is
  a person-level choice, not per-project. Stores only the agent `id`; falls back to the
  default if the id is unknown/disabled. Default = Claude Code.
- Both the tool-window picker and the `Configurable` (Settings > Tools > Agent Hub)
  read/write this same state. (Changing it in Settings takes effect on the next
  open/Restart of the tool window — live relaunch on settings-apply is a future nicety.)

---

## 5. Conventions

- **Kotlin, IntelliJ Platform idioms.** Services via `@Service`; obtain with
  `service<T>()` / `project.service<T>()`.
- **No blocking on EDT.** Terminal creation and PATH probes must not freeze the UI;
  use the platform's background/coroutine facilities.
- **i18n:** user-facing strings go through `AgentHubBundle`
  (`messages/AgentHubBundle.properties`).
- **Logging:** `thisLogger()`.
- **Keep it agent-agnostic:** never scatter `"claude"` literals through the UI/terminal
  layers — route everything through `Agent` / `AgentRegistry` / settings.

---

## 6. Icons & resources
- Tool-window icon: `13x13` SVG/PNG under `src/main/resources/icons/`, referenced by
  the `icon` attribute of the `<toolWindow>` element.
- Keep light/dark variants (`*_dark.svg`) per JetBrains UI guidelines.

---

## 7. Roadmap (do not build ahead of the current milestone)

**Milestone 1 (done):**
- [x] Right-anchored "Agent Hub" tool window.
- [x] Embedded terminal auto-launching Claude Code.
- [x] Preferred-agent setting, persisted globally, default Claude Code.
- [x] Cross-platform launch + "agent not found" handling (Win/macOS/Linux).
- [x] Startup loader until the agent is ready.
- [x] Replace template sample code and rename bundle/classes.

> Verified on Windows (`claude` at `~/.local/bin`). macOS/Linux paths are handled in
> `AgentExecutableResolver` but not yet verified on those OSes.

**Later:**
- Additional agents (Copilot CLI, Codex CLI, OpenCode, …) as `AgentRegistry` entries.
- Live relaunch when the preferred agent changes in Settings.
- Multiple concurrent agent terminals / tabs.
- Per-agent config (model, flags, env), custom/user-defined agents.
- Quick actions (restart agent, new session, open docs).

---

## 8. Definition of done for a change
1. `./gradlew build` and `./gradlew test` pass.
2. `./gradlew runIde` shows the tool window on the right and launches the agent
   terminal on at least the developer's current OS (note which OS was verified).
3. No new EDT-blocking calls; no hardcoded shell paths.
4. User-facing strings are in the bundle.
5. `CHANGELOG.md` "Unreleased" updated for notable changes.
