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
OpenAI Codex CLI, OpenCode, etc.). Claude Code and GitHub Copilot CLI already work
end-to-end; the architecture is generalized so a new agent is a registry entry, not
new plumbing. Beyond launching the CLI, the plugin adds IDE-side value around it —
today, a live per-session **Files changed** panel with one-click diffs (§4.5).

---

## 2. Current status

**v0.2.0 is released** (2026-07-11) and the plugin is published on the
[JetBrains Marketplace (id 32765)](https://plugins.jetbrains.com/plugin/32765).
What's shipped, in order of arrival:

- **v0.1.0** — the core: right-anchored tool window, embedded terminal auto-launching
  the preferred agent (Claude Code default, GitHub Copilot CLI supported), multiple
  closeable session tabs, auto-close on agent exit, startup spinner, cross-platform
  executable resolution, persisted preferred-agent setting.
- **v0.2.0** — the polish milestone: a per-session **Files changed panel** (§4.5) that
  lists what the agent touched and opens the diff on click, a **modernized look and
  feel** (native icon toolbar actions, monochrome New UI stripe icon, per-agent icons
  in picker and tabs, standard empty-state screens), and **performance work** (cached
  executable lookups, one shared VFS refresh across sessions, hover repaints limited
  to affected rows). The toolbar IDE-version label was removed.

**Current focus:** the "Later" items in §7 — more agents as registry entries, live
relaunch on settings change, per-agent config. See §4 for the actual layout and §7
for the full done-vs-next ledger; `CHANGELOG.md` is the authoritative release record.

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
  CliAgentDockToolWindowPanel.kt     SimpleToolWindowPanel: toolbar (agent picker + native
                                 icon actions for New Session and Restart — Restart
                                 disables itself with no open session) + a JBTabbedPane
                                 of sessions. Each closeable tab is an independent agent
                                 session (own Disposable) holding an OnePixelSplitter:
                                 terminal on top, ChangedFilesPanel below (hidden until
                                 the session changes a file). Handles restart, the
                                 "agent not found" and "no active sessions" empty states
                                 (JBPanelWithEmptyText with clickable actions).

changes/
  SessionFileChangeTracker.kt    VFS BulkFileListener scoped to the session Disposable;
                                 accumulates external (agent-made) file changes under the
                                 project (§4.5).
  ChangedFilesPanel.kt           The "Files changed" strip below a session's terminal:
                                 VCS-colored hyperlink rows, click = diff view, open-file
                                 icon, header with count / commit-view jump / minimize /
                                 clear (§4.5).

agent/
  Agent.kt                       Model: id, displayName, command, args, enabled,
                                 readyMarkers, readyTimeoutMs. Builds the launch command
                                 line (absolute path, quoted if needed).
  AgentRegistry.kt               Known agents; Claude Code is the enabled default, others
                                 stubbed disabled.
  AgentExecutableResolver.kt     Resolves the executable via PATH + well-known install dirs.
                                 Successful lookups are cached (one stat on a hit); misses
                                 stay uncached so Retry performs a real lookup.

ui/
  AgentComboBox.kt               Shared agent-picker ComboBox factory (toolbar + settings).
  AgentIcons.kt                  16x16 per-agent icons for the picker and tab titles, keyed
                                 by Agent.id (generic console icon as fallback) — keeps the
                                 Agent model UI-free.

terminal/
  AgentTerminalRunner.kt         Creates the embedded terminal and sends the agent command.
  AgentTerminalView.kt           CardLayout: loading spinner until the agent is ready,
                                 then the terminal card.

settings/
  AgentSettingsState.kt          PersistentStateComponent (APP level, @Service light service —
                                 not registered in plugin.xml) storing preferred agent id.
  AgentSettingsConfigurable.kt   Settings UI under Settings > Tools > CLI Agent Dock
                                 (BoundConfigurable + Kotlin UI DSL).

util/
  IdeInfo.kt                     Reports the running IDE product/version/build (logged at
                                 startup; no longer shown in the toolbar since v0.2.0).
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
  The poll **starts only once the terminal is revealed** (ready marker or timeout) and needs
  several consecutive not-running samples before it closes: during shell boot the integration
  scripts register as a running command that goes idle before the queued agent command spawns,
  and a poll catching that gap used to close freshly opened tabs.
- `CliAgentDockToolWindowPanel.AgentSession` closes the tab on `onExit`, guarded by a
  `closed` flag (idempotent) and a `liveView` identity check so a **Restart** (which disposes
  the old view) doesn't let a stale termination callback close the restarted tab.

### 4.5 Files changed panel (per session)
- Each session's tab is an `OnePixelSplitter`: terminal on top (~80%), `ChangedFilesPanel`
  below. The panel is **hidden until the session's first file change** and can be minimized
  to a thin strip; a red clear button empties the list.
- **Detection is VFS-based and agent-agnostic** (`SessionFileChangeTracker`): a
  `BulkFileListener` on the app message bus keeps only events with
  `VFileEvent.isFromRefresh` — the file watcher reports *external* writes (the agent CLI)
  as refresh events, while the user's own editor saves/refactorings don't carry that flag
  and are excluded. Trade-off: any external writer during the session (another agent tab,
  a `git pull` elsewhere) is attributed too. `.git/` internals are filtered out.
- **Clean start:** changes made while the IDE was closed replay as refresh events on the
  first refresh after startup, so the tracker refreshes the project root first (a baseline
  pass that absorbs the backlog) and subscribes only when it completes — a new session
  never opens with pre-existing changes attributed to it.
- **Refresh nudge:** the watcher only marks paths dirty; the platform's usual refresh
  triggers (frame/editor activation) never fire while the user stays in the embedded
  terminal, so each tracker ticks a 2s `Alarm` — but a static gate ensures **one**
  `SaveAndSyncHandler.scheduleRefresh()` per interval across all live trackers (N open
  sessions must not queue N refreshes).
- **Merge semantics** (`SessionFileChangeTracker.merge`): repeated events on one file
  collapse — created+modified stays created, deleted+recreated becomes modified,
  created+deleted nets out to nothing (the file never existed for the user). Renames/moves
  carry the prior kind to the new path.
- **Rows behave like hyperlinks** (VCS colors: green created, blue modified, struck-through
  gray deleted): a single click opens the IDE **diff view** via `ShowDiffAction` /
  `ChangeListManager` (falling back to opening the file when there's no VCS change to
  diff); a hover-only open-file icon opens the file directly instead. The header shows the
  count and jumps to the IDE's commit view. Hover repaints only the affected rows — keep it
  that way.
- Tracker and panel live on the session's `Disposable`, so close/Restart tears them down.

### 4.6 Multiplatform rules
- Do **not** hardcode `/bin/bash`, `cmd.exe`, or absolute shell paths. The Terminal
  plugin picks the user's default shell; we only send the agent command to it.
- Executable resolution (`AgentExecutableResolver`): try `PathEnvironmentVariableUtil.findInPath`
  first, then well-known install dirs (`~/.local/bin`, npm global, Homebrew, and on Windows
  the WinGet `Links` shim dir + each `WinGet\Packages\<id>\` portable-package dir),
  honoring Windows `.exe`/`.cmd`/`.bat`. **Launch by absolute path** so a stale/minimal
  IDE PATH (common with `runIde` sandboxes and GUI-launched IDEs) doesn't cause a false
  "not found" or a broken terminal. If unresolved, show the friendly not-found panel.
- Use `com.intellij.openapi.util.SystemInfo` for OS branching.

### 4.7 Persisting the preferred agent
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
  layers — route everything through `Agent` / `AgentRegistry` / settings (icons via
  `AgentIcons`, keyed by `Agent.id`, so the model stays UI-free).
- **Prefer platform APIs over hand-rolled mechanics:** `SimpleToolWindowPanel`,
  `AnAction` toolbars, `JBPanelWithEmptyText`, `OnePixelSplitter`, VCS diff/status
  machinery, `AllIcons` — the v0.2.0 refactor replaced custom equivalents with these;
  don't reintroduce bespoke versions.
- **Mind the hot paths:** per-session periodic work must be shared, not multiplied
  (see the VFS refresh gate, §4.5); cache repeatable lookups (executable resolution);
  repaint only what changed (hover repaints in the changed-files list).

---

## 6. Icons & resources
- Tool-window stripe icon: `13x13` monochrome New UI outline SVG under
  `src/main/resources/icons/` (`cliAgentDock.svg` + `_dark`), referenced by the `icon`
  attribute of the `<toolWindow>` element. The purple branding lives only in the
  Marketplace/Settings plugin icon (`META-INF/pluginIcon.svg` + `_dark`).
- Per-agent icons: `16x16` SVGs under `icons/agents/` (`claudeCode.svg`,
  `githubCopilot.svg` + `_dark`), loaded through `AgentIcons` (§4). A new agent without
  art falls back to a generic console icon.
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

**Done since Milestone 1 (released as v0.1.0):**
- [x] GitHub Copilot CLI as an enabled `AgentRegistry` entry (cross-OS, incl. WinGet), with
      the readiness spinner (§4.3).
- [x] Multiple concurrent agent sessions in closeable tabs (New Session button + picker).
- [x] Auto-close a session tab when its agent exits (§4.4).
- [x] Published to the JetBrains Marketplace (plugin id 32765).

**Done in v0.2.0 (2026-07-11):**
- [x] Per-session **Files changed panel** with VCS-colored hyperlink rows, one-click diff
      view, open-file icon, commit-view shortcut, minimize and clear (§4.5).
- [x] Modernized look and feel: native icon toolbar actions, monochrome New UI stripe
      icon, per-agent icons in picker and tabs, standard empty-state screens; toolbar
      IDE-version label removed.
- [x] Performance: cached executable lookups (Retry still real), one shared VFS refresh
      across sessions, row-scoped hover repaints.

**Later:**
- Additional agents (Codex CLI, OpenCode, …) as `AgentRegistry` entries.
- Live relaunch when the preferred agent changes in Settings.
- Per-agent config (model, flags, env), custom/user-defined agents.
- Quick actions (open docs, etc.).
- README screenshot/GIF of the tool window in action (still a TODO in README.md).

---

## 8. Definition of done for a change
1. `./gradlew build` and `./gradlew test` pass.
2. `./gradlew runIde` shows the tool window on the right and launches the agent
   terminal on at least the developer's current OS (note which OS was verified).
3. No new EDT-blocking calls; no hardcoded shell paths.
4. User-facing strings are in the bundle.
5. `CHANGELOG.md` "Unreleased" updated for notable changes.
