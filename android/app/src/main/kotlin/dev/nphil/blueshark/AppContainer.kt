package dev.nphil.blueshark

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.nphil.blueshark.ble.GattClient
import dev.nphil.blueshark.ble.LinkExclusivity
import dev.nphil.blueshark.ble.ScannerRepository
import dev.nphil.blueshark.ble.SignalMonitor
import dev.nphil.blueshark.data.SessionStore
import dev.nphil.blueshark.debug.DebugLog
import dev.nphil.blueshark.export.ExportService
import dev.nphil.blueshark.guide.GuideController
import dev.nphil.blueshark.ui.project.LearnSessionCoordinator
import dev.nphil.blueshark.ui.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BlueSharkApp : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val bluetoothManager: BluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    val sessions = SessionStore(appContext)
    val themes = ThemeRepository(appContext)

    /** Process-lifetime scope for fire-and-forget persistence that must outlive a composition (e.g. theme writes). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val debug = DebugLog(appContext, appScope)

    /**
     * Shared state of the guided take-over. It lives here because the accessibility service and the
     * Capture screen are separate entry points into the same process and must agree on the target
     * app, the checklist and the markers it produces.
     */
    val guide = GuideController(appContext, appScope, debug)

    /**
     * The one learning session the process can have, and the correlation that closes it.
     *
     * It belongs here rather than in a ViewModel because the operator leaves BlueShark for the
     * middle of it: the overlay's Finish button brings the app back through a fresh intent, by
     * which time the screen that started the session may be gone - or the process may have been
     * killed and restarted.
     */
    val learning = LearnSessionCoordinator(sessions, guide, debug, appScope)

    /**
     * The shared export cache. One instance so its week-long pruning is not four screens racing
     * to delete each other's freshly written artefacts out from under a share target.
     */
    val exports = ExportService(appContext, sessions)

    /**
     * The one BLE scanner in the process. Shared so the Scan tab's aggregate and the Signal
     * screen's device picker are the same list, and so the radio never carries two discovery
     * registrations (Android throttles scan starts to five per 30 s).
     */
    val scanner = ScannerRepository(appContext, bluetoothManager, appScope)

    /**
     * One GATT link at a time, shared by every screen that needs one: the stack allows a single
     * outstanding ATT request per connection, and two clients would fight over it.
     *
     * The Command Prober raises the stakes on that invariant. Its verdicts are correlations
     * between a write and the notification that follows it, so a second screen writing to the same
     * characteristic, or tearing this client down, does not merely interleave - it silently
     * reattributes a device's answer to the wrong frame. Screens that only observe (Scan's log,
     * Signal's connected RSSI) are harmless; anything that writes must not do so during a sweep,
     * which [linkExclusivity] is what actually enforces rather than merely documenting.
     */
    val gattClient = GattClient(appContext, bluetoothManager, appScope)

    /**
     * Who owns [gattClient] exclusively right now.
     *
     * A sweep claims it for its whole run and the device-project funnel claims it for each write,
     * so neither can misattribute the other's traffic - and, unlike a flag on a screen, the claim
     * outlives navigation, which is exactly when two live runners exist at once.
     */
    val linkExclusivity = LinkExclusivity()

    /** Placement diagnostics: its own address-filtered scan plus connected-RSSI polling. */
    val signalMonitor by lazy { SignalMonitor(appContext, bluetoothManager, gattClient, appScope) }
}
