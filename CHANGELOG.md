<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# cli-agent-dock Changelog

## [Unreleased]

### Added

- **Files changed panel per session** — while an agent runs, files it creates, modifies, or deletes in the project are listed in a panel below that session's terminal. Rows use VCS-style colors and act as hyperlinks: hover highlights, a single click opens the IDE's diff view for that file (falling back to opening the file itself when there's no VCS change to diff), and a small open-file icon before the filename opens it directly instead. The header jumps to the IDE's commit view and minimizes the panel to a thin strip under the terminal; a red clear button in the bottom-right corner empties the list. The panel appears only once the session changes a file.

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

[Unreleased]: https://github.com/vladimirvaca/cli-agent-dock/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/vladimirvaca/cli-agent-dock/commits/v0.1.0
