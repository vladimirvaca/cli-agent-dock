import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.ChangelogSectionUrlBuilder
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        // Kotlin 2.2+ changed the -jvm-default mode, which makes the compiler emit
        // bridge overrides for ToolWindowFactory's default methods (getIcon, getAnchor,
        // manage, ...). Plugin Verifier then flags them as internal API usages (MP-7604).
        // no-compatibility skips those bridges and relies on real JVM default methods.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")

        // Bundled Terminal plugin - provides the embedded terminal used to host the agent CLI.
        bundledPlugin("org.jetbrains.plugins.terminal")

        testFramework(TestFrameworkType.Platform)
    }
}

// Changelog plugin configuration - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    // Release tags are "v"-prefixed (v0.1.0); make the generated comparison links match.
    sectionUrlBuilder = ChangelogSectionUrlBuilder { repositoryUrl, currentVersion, previousVersion, isUnreleased ->
        repositoryUrl + when {
            isUnreleased && previousVersion == null -> "/commits"
            isUnreleased -> "/compare/v$previousVersion...HEAD"
            previousVersion == null -> "/commits/v$currentVersion"
            else -> "/compare/v$previousVersion...v$currentVersion"
        }
    }
}

// IntelliJ Platform Gradle Plugin configuration - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Leave compatibility open-ended (JetBrains guidance); a bare null would not clear the convention value.
            untilBuild = provider { null }
        }

        // Marketplace "What's New": the changelog section of the version being built,
        // falling back to [Unreleased] for dev builds. Rendered eagerly: a lazy
        // provider here would capture the build script, which the configuration
        // cache cannot serialize.
        changeNotes = with(changelog) {
            renderItem(
                (getOrNull(providers.gradleProperty("version").get()) ?: getUnreleased())
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }
    }

    // Plugin signing - read more: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    // The env vars hold PEM content (not file paths); provided as GitHub Actions secrets.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace publishing - read more: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Pre-release versions are published to a matching custom channel:
        // "0.2.0-beta.1" -> "beta" channel, plain "0.2.0" -> "default".
        channels = providers.gradleProperty("version").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
