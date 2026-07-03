<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# cli-agent-dock Changelog

## [Unreleased]
### Added
- Right-anchored **CLI Agent Dock** tool window hosting an embedded terminal.
- Auto-launch of the preferred coding agent (default: Claude Code) in the project directory.
- Application-level setting to choose the preferred agent, remembered across restarts and projects (Settings > Tools > CLI Agent Dock).
- Agent picker and restart controls in the tool window toolbar.
- Startup loader that shows a spinner until the agent's interactive UI is ready, then reveals it.
- Cross-platform executable resolution via PATH plus well-known install locations, launched by absolute path so a stale IDE PATH does not break launching; friendly "agent not found" state otherwise (Windows, macOS, Linux).
- Running IDE product/version/build shown in the tool window and logs.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
