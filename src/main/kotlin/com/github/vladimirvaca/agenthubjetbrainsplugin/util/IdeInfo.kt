package com.github.vladimirvaca.agenthubjetbrainsplugin.util

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo

/**
 * Reports the IDE the plugin is currently running in. Useful for confirming which
 * IntelliJ Platform build `runIde` actually launched (the sandbox IDE version comes
 * from the `intellijIdea(...)` coordinate in build.gradle.kts, not your installed IDE).
 */
object IdeInfo {

    /** e.g. "IntelliJ IDEA". */
    val productName: String
        get() = ApplicationNamesInfo.getInstance().fullProductName

    /** Marketing version, e.g. "2025.2.6.2". */
    val fullVersion: String
        get() = ApplicationInfo.getInstance().fullVersion

    /** Human-friendly version name, e.g. "2025.2". */
    val versionName: String
        get() = ApplicationInfo.getInstance().versionName

    /** Build number, e.g. "IU-252.23892.409". */
    val buildNumber: String
        get() = ApplicationInfo.getInstance().build.asString()

    /** Product code portion of the build number, e.g. "IU", "IC", "PY". */
    val productCode: String
        get() = ApplicationInfo.getInstance().build.productCode

    /**
     * One-line summary, e.g. "IntelliJ IDEA 2025.2.6.2 (IU-252.23892.409)".
     */
    fun describe(): String = "$productName $fullVersion ($buildNumber)"
}
