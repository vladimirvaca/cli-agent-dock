<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# cli-agent-dock Changelog

## [Unreleased]

## [0.3.0] - 2026-07-11

### Added

- **The Files changed panel cleans itself up on commit** — after every VCS refresh, entries the VCS reports as clean again (committed or reverted) are removed automatically, so landing the session's work empties the panel without pressing the clear button. Files that merely await a VCS rescan are left alone, and a deleted file is only removed once the VCS actually saw its deletion.
- **"Untracked" tag on files unknown to version control** — files the VCS doesn't track yet show a small grayed `· untracked` tag after the path, with a tooltip explaining they won't be included in commits until added. The tag disappears on its own once the file is added (e.g. `git add`).

### Changed

- **Calmer Files changed rows** — hover no longer highlights or underlines rows (mouse movement repaints nothing, removing the flicker); the VCS colors and hand cursor carry the affordance, and the open-file icon is now always visible on openable rows instead of appearing on hover.

### Fixed

- The Files changed panel no longer starts a session pre-populated with stale entries: changes that happened while the IDE was closed used to replay on the first refresh after startup and get attributed to the fresh session. The tracker now runs a baseline refresh of the project root first and only starts recording once it completes.
- A newly opened agent tab no longer closes itself right after opening. The exit-detection poll could mistake the shell's boot sequence (integration scripts running, then a brief idle gap before the agent command spawns) for the agent exiting; it now starts only after the agent is ready and requires several consecutive idle samples before closing the tab.

## [0.2.0] - 2026-07-11

### Added

- **Files changed panel per session** — while an agent runs, files it creates, modifies, or deletes in the project are listed in a panel below that session's terminal. Rows use VCS-style colors and act as hyperlinks: hover highlights, a single click opens the IDE's diff view for that file (falling back to opening the file itself when there's no VCS change to diff), and an open-file icon on the hovered row opens it directly instead. The header shows the change count, jumps to the IDE's commit view, minimizes the panel to a thin strip under the terminal, and holds a red clear button that empties the list. The panel appears only once the session changes a file.

### Changed

- **Modernized tool window look and feel** — the toolbar uses native icon actions for New Session and Restart (Restart disables itself when no session is open), the tool window stripe icon is a monochrome New UI outline (the purple branding now lives in the Marketplace/Settings plugin icon), each agent has its own icon in the picker and session tab titles, and the "agent not found" and "no active sessions" screens use the IDE's standard empty-state style with clickable actions.
- Snappier sessions and lighter idle overhead: successful agent executable lookups are cached so new sessions and restarts skip the PATH rescan (Retry still performs a real lookup), concurrent sessions share a single periodic VFS refresh instead of scheduling one each, and the files-changed list repaints only the affected rows on hover.

### Removed

- **IDE version label** from the toolbar — it didn't carry its weight next to the agent controls; `IdeInfo.describe()` is still logged on startup for diagnostics.

## [0.1.0] - 2026-07-05

### Added

- Right-anchored **CLI Agent Dock** tool window hosting an embedded terminal.
- Auto-launch of the preferred coding agent (default: Claude Code) in the project directory.
- Application-level setting to choose the preferred agent, remembered across restarts and projects (Settings > Tools > CLI Agent Dock).
- Agent picker, **New Session**, and Restart controls in the tool window toolbar.
- **Multiple concurrent agent sessions**, each in its own closeable tab; the picker chooses which agent a new session runs, without disturbing existing tabs.
- **Auto-close a session tab when its agent exits** — quitting the agent (Ctrl+C, EOF, its own `/exit`/`q`, or a crash) now closes the terminal tab instead of leaving a bare shell prompt. The shell command is exit-wrapped per shell (`exec` on POSIX, `; exit`/`& exit` on Windows) and the tab closes deterministically via the terminal's termination callback.
- **GitHub Copilot CLI** support (`copilot`), cross-platform including WinGet installs, with the same startup spinner as Claude Code.
- Startup loader that shows a spinner until the agent's interactive UI is ready (per `Agent.readyMarkers`), then reveals it — now applied to GitHub Copilot CLI as well.
- Cross-platform executable resolution via PATH plus well-known install locations (npm global, Homebrew, `~/.local/bin`, and on Windows the WinGet `Links` and `Packages\<id>\` dirs), launched by absolute path so a stale IDE PATH does not break launching; friendly "agent not found" state otherwise (Windows, macOS, Linux).
- Running IDE product/version/build shown in the tool window and logs.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

[Unreleased]: https://github.com/vladimirvaca/cli-agent-dock/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/vladimirvaca/cli-agent-dock/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/vladimirvaca/cli-agent-dock/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/vladimirvaca/cli-agent-dock/commits/v0.1.0
