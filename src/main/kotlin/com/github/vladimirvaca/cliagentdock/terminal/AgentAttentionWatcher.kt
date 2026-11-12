package com.github.vladimirvaca.cliagentdock.terminal

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsState
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
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
    /** The session's lifetime; ends the IDE-activation subscription. */
    parentDisposable: Disposable,
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
     * The IDE window was activated or deactivated since the last check. Sampling the
     * focus state on each check misses a quick visit — into the IDE and out again between
     * two checks leaves both samples equal — yet the agent's TUI repainted twice.
     */
    private var focusEvent = false

    /**
     * The user had this session on screen at some point since the last check, even if
     * they are gone again by the time it runs. A short look still counts as having seen
     * what the agent did.
     */
    private var glanced = false

    init {
        // Delivered on the EDT, like the checks, so plain fields are enough.
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    focusEvent = true
                    // The frame is not reported active until the activation has been
                    // processed, so look one event later.
                    ApplicationManager.getApplication().invokeLater {
                        if (isUserWatching()) glanced = true
                    }
                }

                override fun applicationDeactivated(ideFrame: IdeFrame) {
                    focusEvent = true
                }
            },
        )
    }

    /**
     * Call on each attention check with the length and hash of the terminal's current
     * output. [AgentTerminalView] supplies them, and only re-reads the terminal when its
     * buffer reported a change.
     */
    fun onCheck(outputLength: Int, outputHash: Int) {
        val watchingNow = isUserWatching()
        // Track focus even while notifications are off, so re-enabling them mid-session
        // doesn't read the first sample as a focus change.
        val focusState = watchingNow to ApplicationManager.getApplication().isActive
        val focusChanged = focusEvent || (lastFocusState != null && lastFocusState != focusState)
        lastFocusState = focusState
        focusEvent = false
        val watching = watchingNow || glanced
        glanced = false

        // Checked per call rather than at construction so toggling the setting mid-session
        // takes effect immediately, matching how the changed-files toggle behaves.
        if (!AgentSettingsState.getInstance().notifyWhenAgentIdle) return
        if (decider.onCheck(outputLength, watching, focusChanged, outputHash)) {
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
 * Fires **once** per turn: the agent worked, then its output stayed identical for
 * [idleChecksRequired] consecutive checks, while the user was not watching.
 *
 * - **Quiet means unchanged content, not unchanged length.** An agent that is thinking
 *   animates a spinner and an elapsed-time counter in place: the text changes while its
 *   length stays the same for many seconds, and judging by length alone announced a
 *   thinking agent as waiting.
 * - **Only sustained output is a turn.** The notice is armed once the output changed on
 *   [activeChecksToArm] consecutive checks. A single changed check is a repaint — the
 *   user clicking into the terminal, a status line ticking — and neither arms a first
 *   notice nor re-arms one already given.
 * - **Looking is seeing.** A quiet check while the user is watching disarms, so having
 *   been away earlier does not produce a stale notice later.
 * - **Focus changes are not output.** Agents' TUIs repaint when focus moves (focus
 *   in/out reporting, re-layout). Changes in the [settleChecks] checks after a focus
 *   change only move the baseline: they count neither as output nor as quiet. A turn in
 *   progress when the user leaves stays armed and is announced when it ends.
 */
internal class AttentionDecider(
    private val idleChecksRequired: Int,
    private val settleChecks: Int = DEFAULT_SETTLE_CHECKS,
    private val activeChecksToArm: Int = DEFAULT_ACTIVE_CHECKS_TO_ARM,
) {

    private var baselined = false
    private var lastLength = 0
    private var lastHash = 0

    /** Consecutive unwatched checks with no change, counted while [armed]. */
    private var idleChecks = 0

    /** Consecutive checks on which the agent's output changed. */
    private var activeChecks = 0

    /** The agent has done work the user has neither seen nor been told about. */
    private var armed = false

    /** Checks left in which a change counts as a focus repaint, not output. */
    private var settleRemaining = 0

    /**
     * @param outputLength length of the terminal text.
     * @param outputHash hash of the terminal text, so a same-length change still counts.
     * @param focusChanged the user's focus moved since the previous check (IDE window
     *   activated/deactivated, dock or tab came into or out of view).
     * @return true when the caller should raise an attention notice right now.
     */
    fun onCheck(
        outputLength: Int,
        userIsWatching: Boolean,
        focusChanged: Boolean = false,
        outputHash: Int = 0,
    ): Boolean {
        // The very first check establishes a baseline; it is not evidence of a turn.
        val changed = baselined && (outputLength != lastLength || outputHash != lastHash)
        baselined = true
        lastLength = outputLength
        lastHash = outputHash

        if (focusChanged) settleRemaining = settleChecks
        val settling = settleRemaining > 0
        if (settling) settleRemaining--

        if (changed) {
            // Right after a focus change this is the TUI repainting: a new baseline and
            // nothing more. It must not look like quiet either, or a thinking agent
            // would be announced while the window absorbs its animation.
            if (settling) return false
            idleChecks = 0
            if (++activeChecks >= activeChecksToArm) armed = true
            return false
        }
        activeChecks = 0
        if (userIsWatching) {
            // They can see it, so whatever is on screen counts as seen.
            idleChecks = 0
            armed = false
            return false
        }
        if (!armed) return false
        if (++idleChecks < idleChecksRequired) return false

        armed = false
        idleChecks = 0
        return true
    }

    private companion object {
        /** About 4 s at the attention cadence; a focus repaint lands within the first. */
        private const val DEFAULT_SETTLE_CHECKS = 2

        /** Two checks in a row: the agent has been producing for a couple of seconds. */
        private const val DEFAULT_ACTIVE_CHECKS_TO_ARM = 2
    }
}
