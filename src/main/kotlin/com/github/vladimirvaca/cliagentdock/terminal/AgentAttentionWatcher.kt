package com.github.vladimirvaca.cliagentdock.terminal

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsState
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AppIcon
import com.intellij.ui.SystemNotifications

/**
 * Tells the user an agent has gone quiet while they were not watching the dock.
 *
 * Agents block on approval prompts and finish turns without announcing it, so with the
 * dock collapsed or the IDE in the background that state goes unnoticed. Detecting
 * "waiting for approval" precisely would mean parsing each agent's prompt text, which is
 * exactly the per-agent coupling the plugin avoids; instead this watches the one
 * agent-agnostic signal available — output stopped arriving — and only speaks up when the
 * user cannot already see it.
 *
 * Fed from the session's existing poll in [AgentTerminalView] rather than its own timer.
 * The decision rules live in [AttentionDecider] so they are unit-testable without a
 * project; this class only does the platform wiring.
 */
class AgentAttentionWatcher(
    private val project: Project,
    /** Read when a notice fires, so a tab renamed mid-session is announced by its new name. */
    private val sessionTitle: () -> String,
    /** True while the user can see the dock, in which case there is nothing to announce. */
    private val isUserWatching: () -> Boolean,
    /** Brings the session into view when the user clicks the notification. */
    private val onOpenRequested: () -> Unit,
) {

    private val decider = AttentionDecider(IDLE_CHECKS_REQUIRED)

    /** Focus state seen by the previous check: watching the session, IDE window active. */
    private var lastFocusState: Pair<Boolean, Boolean>? = null

    /**
     * Call with the terminal's current output on each attention check. Cheap: only the
     * length is inspected, and [AgentTerminalView] throttles how often this runs because
     * materializing the whole scrollback is not free.
     */
    fun onCheck(output: CharSequence) {
        val watching = isUserWatching()
        // Track focus even while notifications are off, so re-enabling them mid-session
        // doesn't read the first sample as a focus change.
        val focusState = watching to ApplicationManager.getApplication().isActive
        val focusChanged = lastFocusState != null && lastFocusState != focusState
        lastFocusState = focusState

        // Checked per call rather than at construction so toggling the setting mid-session
        // takes effect immediately, matching how the changed-files toggle behaves.
        if (!AgentSettingsState.getInstance().notifyWhenAgentIdle) return
        if (decider.onCheck(output.length, watching, focusChanged)) {
            notifyAttention()
        }
    }

    private fun notifyAttention() {
        val title = CliAgentDockBundle["notification.attention.title", sessionTitle()]
        val content = CliAgentDockBundle["notification.attention.content"]

        // In-IDE balloon: carries the click-through action and persists in the
        // notification list. Only visible while the IDE itself is on screen.
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)
            .addAction(
                NotificationAction.createSimpleExpiring(
                    CliAgentDockBundle["notification.attention.open"],
                    onOpenRequested,
                ),
            )
            .notify(project)

        // An IDE balloon is invisible from another application, which is precisely when
        // a waiting agent is easiest to miss — so also ask the OS (Windows toast, macOS
        // Notification Center, libnotify). No condition needed here: the platform's
        // implementation already no-ops while the IDE is active and honours the user's
        // "system notifications" setting, so this is the whole gate.
        SystemNotifications.getInstance().notify(NOTIFICATION_GROUP, title, content)

        // Belt and braces for a muted or missed toast: flash the taskbar button on
        // Windows, bounce the Dock icon on macOS. Gated explicitly because, unlike
        // SystemNotifications, this would otherwise fire while the user is working in the
        // IDE. `critical = false` keeps it a single nudge rather than a continuous bounce.
        // Runs on the EDT already — the caller is a SWING_THREAD Alarm.
        if (!ApplicationManager.getApplication().isActive) {
            AppIcon.getInstance().requestAttention(project, false)
        }
    }

    private companion object {
        /** Must match the `<notificationGroup id=...>` in plugin.xml. */
        private const val NOTIFICATION_GROUP = "CLI Agent Dock"

        /**
         * Consecutive quiet checks before speaking up. At [AgentTerminalView]'s attention
         * cadence this is a handful of seconds — long enough that a pause mid-answer does
         * not trigger it, short enough to be useful.
         */
        private const val IDLE_CHECKS_REQUIRED = 3
    }
}

/**
 * The "has this agent gone quiet while unwatched?" state machine, kept free of platform
 * types so it can be tested directly.
 *
 * Fires **once** per quiet period: after output has been seen, then stops growing for
 * [idleChecksRequired] consecutive checks, while the user is not watching. New output
 * re-arms it, so one notification per turn is the most that can be produced. Looking at
 * the dock marks the current output as seen, so merely having been away earlier does not
 * produce a stale notification later.
 *
 * Focus changes make agents' TUIs repaint: the terminal reports focus in/out and the
 * agent redraws its screen, or the terminal re-lays out. That changes the buffer length
 * without the agent doing anything, and used to re-arm the notice — so after an idle
 * session had been announced or seen, simply switching to another app announced it
 * again. So when focus changes while the agent is already quiet, changes in the next
 * [settleChecks] checks only move the baseline. A real new turn keeps producing past
 * that window and re-arms as usual.
 */
internal class AttentionDecider(
    private val idleChecksRequired: Int,
    private val settleChecks: Int = DEFAULT_SETTLE_CHECKS,
) {

    private var lastLength = UNSET
    private var idleChecks = 0
    private var outputSinceLastNotice = false
    private var alreadyNotified = false

    /** Consecutive checks with no output from the agent, watched or not. */
    private var quietChecks = 0

    /** Checks left in which a length change counts as a focus repaint, not new output. */
    private var settleRemaining = 0

    /**
     * @param focusChanged the user's focus moved since the previous check (IDE window
     *   activated/deactivated, dock or tab came into or out of view).
     * @return true when the caller should raise an attention notice right now.
     */
    fun onCheck(outputLength: Int, userIsWatching: Boolean, focusChanged: Boolean = false): Boolean {
        val changed = lastLength != UNSET && outputLength != lastLength
        val first = lastLength == UNSET
        lastLength = outputLength

        if (focusChanged && quietChecks >= idleChecksRequired) settleRemaining = settleChecks
        val settling = settleRemaining > 0
        if (settling) settleRemaining--

        // A change right after a focus change on an idle session is the TUI repainting:
        // take it as the new baseline without re-arming, and keep counting it as quiet.
        val grew = changed && !settling
        if (!changed || settling) quietChecks++ else quietChecks = 0

        if (grew) {
            // The agent is actively producing; reset the quiet run and re-arm.
            idleChecks = 0
            outputSinceLastNotice = true
            alreadyNotified = false
            return false
        }
        if (userIsWatching) {
            // They can see it, so whatever is on screen counts as seen.
            idleChecks = 0
            outputSinceLastNotice = false
            alreadyNotified = false
            return false
        }
        // The very first check establishes a baseline; it is not evidence of a turn ending.
        if (first || !outputSinceLastNotice || alreadyNotified) return false
        if (++idleChecks < idleChecksRequired) return false

        alreadyNotified = true
        outputSinceLastNotice = false
        idleChecks = 0
        return true
    }

    private companion object {
        private const val UNSET = -1

        /** About 4 s at the attention cadence; a focus repaint lands within the first. */
        private const val DEFAULT_SETTLE_CHECKS = 2
    }
}
