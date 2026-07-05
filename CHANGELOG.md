<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# cli-agent-dock Changelog

## [Unreleased]
### Added
- Right-anchored **CLI Agent Dock** tool window hosting an embedded terminal.
- Auto-launch of the preferred coding agent (default: Claude Code) in the project directory.
- Application-level setting to choose the preferred agent, remembered across restarts and projects (Settings > Tools > CLI Agent Dock).
- Agent picker, **New Session**, and Restart controls in the tool window toolbar.
- **Multiple concurrent agent sessions**, each in its own closeable tab; the picker chooses which agent a new session runs, without disturbing existing tabs.
- **GitHub Copilot CLI** support (`copilot`), cross-platform including WinGet installs, with the same startup spinner as Claude Code.
- Startup loader that shows a spinner until the agent's interactive UI is ready (per `Agent.readyMarkers`), then reveals it — now applied to GitHub Copilot CLI as well.
- Cross-platform executable resolution via PATH plus well-known install locations (npm global, Homebrew, `~/.local/bin`, and on Windows the WinGet `Links` and `Packages\<id>\` dirs), launched by absolute path so a stale IDE PATH does not break launching; friendly "agent not found" state otherwise (Windows, macOS, Linux).
- Running IDE product/version/build shown in the tool window and logs.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
