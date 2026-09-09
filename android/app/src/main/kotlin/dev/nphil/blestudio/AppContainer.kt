package dev.nphil.blestudio

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.nphil.blestudio.data.SessionStore
import dev.nphil.blestudio.ui.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BleStudioApp : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val bluetoothManager: BluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    val sessions = SessionStore(appContext)
    val themes = ThemeRepository(appContext)

    /** Process-lifetime scope for fire-and-forget persistence that must outlive a composition (e.g. theme writes). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
