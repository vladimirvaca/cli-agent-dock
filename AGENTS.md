# AGENTS.md

Engineering guide for the **CLI Agent Dock** JetBrains plugin. This file is the source
of truth for AI coding assistants (Claude Code, Copilot, Codex, etc.) and human
contributors working on this repository. Read it before making changes.

---

## 1. What this plugin does

CLI Agent Dock adds a **tool window to the right side bar** of any JetBrains IDE
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
replaced with the real feature: a right-anchored "CLI Agent Dock" tool window that launches
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
| Plugin id | `com.github.vladimirvaca.cliagentdock` |
| Base package | `com.github.vladimirvaca.cliagentdock` |
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
CliAgentDockBundle.kt                i18n bundle object (messages/CliAgentDockBundle.properties).

toolWindow/
  CliAgentDockToolWindowFactory.kt   ToolWindowFactory (DumbAware), anchored right.
  CliAgentDockToolWindowPanel.kt     Panel: toolbar (agent picker, New Session, Restart,
                                 IDE-version label) + a JBTabbedPane of terminal views.
                                 Each closeable tab is an independent agent session
                                 (own Disposable); handles restart and the "agent not
                                 found" state per tab.

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
  AgentSettingsConfigurable.kt   Settings UI under Settings > Tools > CLI Agent Dock.

util/
  IdeInfo.kt                     Reports the running IDE product/version/build.
```

### 4.1 Tool window (right anchor)
Registered in `plugin.xml` with `anchor="right"` (alongside Database, Gradle, etc.),
`icon="/icons/cliAgentDock.svg"` (13×13, with a `_dark` variant), factory
`CliAgentDockToolWindowFactory`.

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
  - `widget.addTerminationCallback(Runnable, Disposable)` — fires when the PTY process
    ends; the deterministic signal for auto-closing the tab (§4.4). Prefer it over
    `widget.isCommandRunning()`, whose interface **default just returns `false`** (verified
    by decompilation) and is only meaningfully overridden by some concrete widgets.
- We start a normal shell and send the agent command to it (rather than replacing the
  shell with the agent) so the agent inherits the user's profile PATH/env, and stay
  cross-platform. The command is exit-wrapped so the shell ends with the agent (§4.4).
- Working directory defaults to `project.basePath` (fallback: user home).

### 4.4 Auto-close the tab when the agent exits
- Goal: when the agent CLI ends (Ctrl+C-and-quit, EOF, its own `/exit`/`q`, or a crash),
  the hosting terminal tab closes instead of dropping to a bare shell prompt. **Always
  close**, regardless of exit code.
- Because the agent runs *inside* an interactive shell, the shell must end with the agent.
  `AgentTerminalRunner.exitWrappedCommand` wraps the command per the configured shell
  (`TerminalProjectOptionsProvider.shellPath`): `exec <cmd>` for POSIX (bash/zsh/fish, incl.
  Git Bash — robust for Ctrl+C, preserves the already-sourced env), `& <cmd>; exit` for
  PowerShell, `<cmd> & exit` for cmd.exe. An unrecognized shell is left unwrapped (the old
  survive-the-agent behavior) so it degrades gracefully.
- `AgentTerminalView` takes an `onExit` callback fired **at most once, on the EDT**, from two
  sources: (1) the primary `addTerminationCallback`, and (2) a slow `isCommandRunning` poll
  as a best-effort net for an agent hard-killed before the trailing `exit` runs — guarded by
  a "seen running once" flag so a `false`-stub `isCommandRunning` can never close a tab.
- `CliAgentDockToolWindowPanel.AgentSession` closes the tab on `onExit`, guarded by a
  `closed` flag (idempotent) and a `liveView` identity check so a **Restart** (which disposes
  the old view) doesn't let a stale termination callback close the restarted tab.

### 4.3 Startup loader / readiness — **the spinner pattern (every agent must follow it)**
- `AgentTerminalView` uses a `CardLayout` with a "loading" card (a big
  `AsyncProcessIcon` spinner) and a "terminal" card. The terminal starts **eagerly**
  (`defer = false`) so it boots and renders at correct size while hidden behind the spinner.
- An `Alarm` polls `widget.getText()` for any `Agent.readyMarkers` substring; on match
  (or after `Agent.readyTimeoutMs`, default 15s) it removes the spinner, flips to the
  terminal card and focuses it. This logic is **fully generic** — an agent opts in purely
  by supplying `readyMarkers`; there is no per-agent code in `AgentTerminalView`.
- **When adding a new agent, always give it `readyMarkers`** so it gets the same
  loading-spinner UX as Claude Code. Rules for choosing them (see the KDoc on
  `Agent.readyMarkers` for the authoritative contract):
  1. Use strings that appear only once the interactive UI has finished painting — a
     stable header/footer hint, not transient boot logs.
  2. They must **not** be substrings of the launch command line. The shell echoes the
     command (an **absolute path**) into the buffer, so a marker contained in it matches
     instantly and skips the spinner. Match is **case-sensitive** — exploit that.
  3. They are version-sensitive/best-effort; `readyTimeoutMs` is the safety net. Leaving
     `readyMarkers` empty is allowed but means **no spinner** (terminal shows immediately).
- Known markers:
  - Claude Code: `"? for shortcuts"`, `"Welcome to Claude Code"`.
  - GitHub Copilot CLI: `"GitHub Copilot"`, `"/help"`, `"/login"` (spaced "GitHub
    Copilot" is safe because the launch path contains the dotted `GitHub.Copilot`).

### 4.4 Multiplatform rules
- Do **not** hardcode `/bin/bash`, `cmd.exe`, or absolute shell paths. The Terminal
  plugin picks the user's default shell; we only send the agent command to it.
- Executable resolution (`AgentExecutableResolver`): try `PathEnvironmentVariableUtil.findInPath`
  first, then well-known install dirs (`~/.local/bin`, npm global, Homebrew, and on Windows
  the WinGet `Links` shim dir + each `WinGet\Packages\<id>\` portable-package dir),
  honoring Windows `.exe`/`.cmd`/`.bat`. **Launch by absolute path** so a stale/minimal
  IDE PATH (common with `runIde` sandboxes and GUI-launched IDEs) doesn't cause a false
  "not found" or a broken terminal. If unresolved, show the friendly not-found panel.
- Use `com.intellij.openapi.util.SystemInfo` for OS branching.

### 4.5 Persisting the preferred agent
- Application-level `PersistentStateComponent` (`cliAgentDock.xml`) — the preferred agent is
  a person-level choice, not per-project. Stores only the agent `id`; falls back to the
  default if the id is unknown/disabled. Default = Claude Code.
- Both the tool-window picker and the `Configurable` (Settings > Tools > CLI Agent Dock)
  read/write this same state. (Changing it in Settings takes effect on the next
  open/Restart of the tool window — live relaunch on settings-apply is a future nicety.)

---

## 5. Conventions

- **Kotlin, IntelliJ Platform idioms.** Services via `@Service`; obtain with
  `service<T>()` / `project.service<T>()`.
- **No blocking on EDT.** Terminal creation and PATH probes must not freeze the UI;
  use the platform's background/coroutine facilities.
- **i18n:** user-facing strings go through `CliAgentDockBundle`
  (`messages/CliAgentDockBundle.properties`).
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
- [x] Right-anchored "CLI Agent Dock" tool window.
- [x] Embedded terminal auto-launching Claude Code.
- [x] Preferred-agent setting, persisted globally, default Claude Code.
- [x] Cross-platform launch + "agent not found" handling (Win/macOS/Linux).
- [x] Startup loader until the agent is ready.
- [x] Replace template sample code and rename bundle/classes.

> Verified on Windows (`claude` at `~/.local/bin`). macOS/Linux paths are handled in
> `AgentExecutableResolver` but not yet verified on those OSes.

**Done since Milestone 1:**
- [x] GitHub Copilot CLI as an enabled `AgentRegistry` entry (cross-OS, incl. WinGet), with
      the readiness spinner (§4.3).
- [x] Multiple concurrent agent sessions in closeable tabs (New Session button + picker).
- [x] Auto-close a session tab when its agent exits (§4.4).

**Later:**
- Additional agents (Codex CLI, OpenCode, …) as `AgentRegistry` entries.
- Live relaunch when the preferred agent changes in Settings.
- Per-agent config (model, flags, env), custom/user-defined agents.
- Quick actions (open docs, etc.).

---

## 8. Definition of done for a change
1. `./gradlew build` and `./gradlew test` pass.
2. `./gradlew runIde` shows the tool window on the right and launches the agent
   terminal on at least the developer's current OS (note which OS was verified).
3. No new EDT-blocking calls; no hardcoded shell paths.
4. User-facing strings are in the bundle.
5. `CHANGELOG.md` "Unreleased" updated for notable changes.
