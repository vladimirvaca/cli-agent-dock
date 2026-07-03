package com.github.vladimirvaca.agenthubjetbrainsplugin.agent

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Resolves an agent's executable to an absolute file.
 *
 * The IDE process (especially a sandbox launched by `runIde`, or a GUI-launched IDE)
 * often inherits a stale/minimal PATH that does not match the user's shell. Relying
 * only on PATH therefore produces false "not found" results. To be robust we look up
 * the executable on PATH first, then fall back to well-known per-OS install locations
 * (e.g. the native installer's `~/.local/bin`, npm global bin, Homebrew).
 *
 * Callers should launch the returned absolute path so PATH state becomes irrelevant.
 */
object AgentExecutableResolver {

    fun resolve(agent: Agent): File? {
        // 1. Standard PATH lookup (honors PATHEXT / .exe/.cmd/.bat on Windows).
        PathEnvironmentVariableUtil.findInPath(agent.command)?.let { return it }

        // 2. Well-known install locations not always present on the IDE's PATH.
        for (dir in candidateDirs()) {
            for (name in executableNames(agent.command)) {
                val file = File(dir, name)
                if (file.isFile) return file
            }
        }
        return null
    }

    private fun executableNames(command: String): List<String> =
        if (SystemInfo.isWindows) {
            listOf("$command.exe", "$command.cmd", "$command.bat", command)
        } else {
            listOf(command)
        }

    private fun candidateDirs(): List<File> {
        val home = File(System.getProperty("user.home"))
        return if (SystemInfo.isWindows) {
            listOfNotNull(
                File(home, ".local/bin"),
                System.getenv("APPDATA")?.let { File(it, "npm") },
                System.getenv("LOCALAPPDATA")?.let { File(it, "Programs") },
            )
        } else {
            listOf(
                File(home, ".local/bin"),
                File("/usr/local/bin"),
                File("/opt/homebrew/bin"),
                File(home, ".npm-global/bin"),
                File(home, "bin"),
            )
        }
    }
}
