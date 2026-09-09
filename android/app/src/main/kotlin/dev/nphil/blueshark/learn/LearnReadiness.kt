package dev.nphil.blueshark.learn

import dev.nphil.blueshark.hci.SnoopCapabilities
import dev.nphil.blueshark.shell.ShizukuState

/** Where the vendor app's traffic will be read from during a learning session. */
enum class TrafficSource {
    /** The Android stack's HCI snoop log; the vendor app runs on this tablet. */
    HCI_SNOOP,

    /** BlueShark impersonates the device; the vendor app runs on a second phone. */
    RELAY,
}

/** What tapping a checklist row should do; the screen maps these onto intents. */
enum class ChecklistAction {
    NONE,
    PICK_APP,
    OPEN_ACCESSIBILITY,
    OPEN_DEVELOPER_OPTIONS,
    RESTART_BLUETOOTH,
    INSTALL_SHIZUKU,
    START_SHIZUKU,
    GRANT_SHIZUKU,
    OPEN_RELAY,

    /** The app cannot observe the fact; the operator ticks a box saying they did it. */
    CONFIRM_SNOOP,
}

data class ChecklistItem(
    val id: String,
    val satisfied: Boolean,
    val title: String,
    val detail: String,
    val action: ChecklistAction = ChecklistAction.NONE,
    /** A second thing the row can do, when the facts cannot tell which of two fixes applies. */
    val secondaryAction: ChecklistAction = ChecklistAction.NONE,
    /** Unsatisfied but the session can still start; the row explains what will be slower. */
    val optional: Boolean = false,
)

data class Readiness(
    val items: List<ChecklistItem>,
    val source: TrafficSource,
) {
    val canStart: Boolean get() = items.none { !it.satisfied && !it.optional }
    val blocking: List<ChecklistItem> get() = items.filter { !it.satisfied && !it.optional }
}

/**
 * Decides, from observed facts only, what the operator must still do before a learning session
 * can produce evidence - and, just as important, what they must NOT be asked to do again.
 *
 * The facts that shape this (all verified on the test tablet):
 * - The HCI snoop mode is a property only Settings may write, and the stack reads it when the
 *   adapter starts. Once the toggle is on and the adapter has restarted once, it stays effective
 *   across reboots, so a restart is never a routine step. `serviceSnoopSetting` (dumpsys) is what
 *   the running stack read at its last start - the effective mode; the property is the toggle
 *   itself and is unreadable on some OEM builds (HyperOS returns nothing). When the property is
 *   readable and says full while the stack does not, exactly one restart is needed. When it is
 *   unreadable, the app cannot tell "toggle off" from "toggle on, not restarted" and must say so,
 *   offering both fixes rather than guessing one.
 * - Shizuku is an accelerator, not a prerequisite: it lets the app collect the log and restart
 *   the adapter itself. Without it, the operator takes a bug report from Developer options and
 *   imports the zip. That path is slower, not broken, so its rows are optional.
 * - A relay session needs no snoop at all, but the vendor app must run on another phone: a radio
 *   never hears its own advertisements.
 */
object LearnReadiness {
    fun evaluate(
        source: TrafficSource,
        capabilities: SnoopCapabilities,
        shizuku: ShizukuState,
        observerEnabled: Boolean,
        vendorAppLabel: String?,
        relayRunning: Boolean = false,
        /** Operator's word that the snoop toggle is on and Bluetooth was restarted once, for when nothing can check. */
        snoopConfirmedByOperator: Boolean = false,
    ): Readiness {
        val items = ArrayList<ChecklistItem>(6)

        items += ChecklistItem(
            id = "app",
            satisfied = vendorAppLabel != null,
            title = if (vendorAppLabel != null) "Vendor app: $vendorAppLabel" else "Pick the device's own app",
            detail = if (source == TrafficSource.RELAY) {
                "The app runs on another phone for a relay session; naming it here only labels the evidence."
            } else {
                "BlueShark launches it for you and watches which control you touch."
            },
            action = ChecklistAction.PICK_APP,
            optional = source == TrafficSource.RELAY,
        )

        items += ChecklistItem(
            id = "observer",
            satisfied = observerEnabled || source == TrafficSource.RELAY,
            title = if (observerEnabled) "Control observer on" else "Turn on the BlueShark control observer",
            detail = if (source == TrafficSource.RELAY) {
                "Not needed for a relay: the app is on the other phone, so mark actions from the overlay instead."
            } else {
                "An accessibility service that records which control you tapped - the label every captured " +
                    "command will carry. It never reads typed text."
            },
            action = ChecklistAction.OPEN_ACCESSIBILITY,
        )

        when (source) {
            TrafficSource.HCI_SNOOP -> items += snoopItems(capabilities, shizuku, snoopConfirmedByOperator)
            TrafficSource.RELAY -> items += ChecklistItem(
                id = "relay",
                satisfied = relayRunning,
                title = if (relayRunning) "Relay running" else "Start the relay",
                detail = "BlueShark advertises as the device; connect the vendor app on the other phone to it. " +
                    "Every write is captured as it passes through.",
                action = ChecklistAction.OPEN_RELAY,
            )
        }
        return Readiness(items, source)
    }

    private fun snoopItems(
        capabilities: SnoopCapabilities,
        shizuku: ShizukuState,
        confirmed: Boolean,
    ): List<ChecklistItem> {
        val out = ArrayList<ChecklistItem>(3)
        val stackFull = capabilities.snoopModeIsFull
        val propertyReadable = capabilities.snoopMode.isNotBlank()
        val toggleOn = capabilities.snoopMode.equals("full", ignoreCase = true)
        // Every observation here comes through the shell. Without Shizuku the probe returns only
        // an error, and without a probe there is nothing to read: the operator is the only sensor.
        val unobservable = !shizuku.ready || (capabilities.probed && capabilities.error != null &&
            capabilities.effectiveSnoopMode.isBlank())

        when {
            stackFull -> out += ChecklistItem(
                id = "snoop",
                satisfied = true,
                title = "HCI logging is on",
                detail = "The Bluetooth stack is recording every packet. Nothing to do here.",
            )
            unobservable -> out += ChecklistItem(
                id = "snoop",
                satisfied = confirmed,
                title = if (confirmed) "HCI logging on (your word)" else "Confirm HCI logging is on",
                detail = "Without Shizuku, BlueShark cannot read the stack's state. Developer options > " +
                    "Enable Bluetooth HCI snoop log > Enabled, then restart Bluetooth once (airplane mode " +
                    "on and off works). Tick this when done; the log you collect will show whether it took.",
                action = ChecklistAction.CONFIRM_SNOOP,
                secondaryAction = ChecklistAction.OPEN_DEVELOPER_OPTIONS,
            )
            !capabilities.probed -> out += ChecklistItem(
                id = "snoop",
                satisfied = false,
                title = "Checking HCI logging…",
                detail = "Waiting for the first capability probe.",
            )
            toggleOn -> out += ChecklistItem(
                id = "snoop",
                satisfied = false,
                title = "Restart Bluetooth once",
                detail = "The toggle is on but the running stack started before it was. One restart makes it " +
                    "stick; you will not be asked again.",
                action = ChecklistAction.RESTART_BLUETOOTH,
            )
            propertyReadable -> out += ChecklistItem(
                id = "snoop",
                satisfied = false,
                title = "Enable Bluetooth HCI snoop log",
                detail = "Developer options > Enable Bluetooth HCI snoop log > Enabled. Only Settings may flip " +
                    "this; no app can do it for you. Come back and restart Bluetooth once.",
                action = ChecklistAction.OPEN_DEVELOPER_OPTIONS,
            )
            else -> out += ChecklistItem(
                id = "snoop",
                satisfied = false,
                title = "Enable HCI snoop log, then restart Bluetooth once",
                detail = "The stack is not logging. This device hides the toggle's value from apps, so: if " +
                    "Developer options > Enable Bluetooth HCI snoop log is off, turn it on; then restart " +
                    "Bluetooth once so the stack picks it up. Both buttons are here.",
                action = ChecklistAction.OPEN_DEVELOPER_OPTIONS,
                secondaryAction = ChecklistAction.RESTART_BLUETOOTH,
            )
        }

        out += when (shizuku) {
            is ShizukuState.Ready -> ChecklistItem(
                id = "collect",
                satisfied = true,
                title = "One-tap collection ready",
                detail = "Shizuku lets BlueShark pull the log itself when you press Finish.",
            )
            ShizukuState.PermissionNeeded -> ChecklistItem(
                id = "collect",
                satisfied = false,
                optional = true,
                title = "Allow Shizuku (optional)",
                detail = "Without it, Finish will ask you to take a bug report and import it - slower, same result.",
                action = ChecklistAction.GRANT_SHIZUKU,
            )
            is ShizukuState.NotRunning -> ChecklistItem(
                id = "collect",
                satisfied = false,
                optional = true,
                title = "Start Shizuku (optional)",
                detail = "Without it, Finish will ask you to take a bug report and import it - slower, same result.",
                action = ChecklistAction.START_SHIZUKU,
            )
            ShizukuState.NotInstalled -> ChecklistItem(
                id = "collect",
                satisfied = false,
                optional = true,
                title = "Manual collection",
                detail = "Finish will ask you to take a bug report (Developer options) and import it. Installing " +
                    "Shizuku makes this one tap.",
                action = ChecklistAction.INSTALL_SHIZUKU,
            )
        }
        return out
    }
}
