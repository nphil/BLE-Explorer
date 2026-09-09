package dev.nphil.blueshark

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.nphil.blueshark.ble.GattClient
import dev.nphil.blueshark.ble.ScannerRepository
import dev.nphil.blueshark.ble.SignalMonitor
import dev.nphil.blueshark.data.SessionStore
import dev.nphil.blueshark.debug.DebugLog
import dev.nphil.blueshark.guide.GuideController
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
     * The one BLE scanner in the process. Shared so the Scan tab's aggregate and the Signal
     * screen's device picker are the same list, and so the radio never carries two discovery
     * registrations (Android throttles scan starts to five per 30 s).
     */
    val scanner = ScannerRepository(appContext, bluetoothManager, appScope)

    /**
     * One GATT link at a time, shared by every screen that needs one: the stack allows a single
     * outstanding ATT request per connection, and two clients would fight over it.
     */
    val gattClient = GattClient(appContext, bluetoothManager, appScope)

    /** Placement diagnostics: its own address-filtered scan plus connected-RSSI polling. */
    val signalMonitor by lazy { SignalMonitor(appContext, bluetoothManager, gattClient, appScope) }
}
