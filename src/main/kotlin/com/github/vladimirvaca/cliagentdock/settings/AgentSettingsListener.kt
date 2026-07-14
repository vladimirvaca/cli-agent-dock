package com.github.vladimirvaca.cliagentdock.settings

import com.intellij.util.messages.Topic

/**
 * Notifies interested UI that [AgentSettingsState] was changed through the settings
 * page, so open sessions can re-apply settings that affect them live (e.g. showing or
 * hiding the changed-files panel) instead of only new sessions picking them up.
 * Published on the application message bus after the configurable applies.
 */
fun interface AgentSettingsListener {

    fun settingsChanged()

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<AgentSettingsListener> =
            Topic(AgentSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
