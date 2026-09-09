package dev.nphil.blestudio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.nphil.blestudio.R
import dev.nphil.blestudio.relay.RelayPhase
import dev.nphil.blestudio.relay.RelaySession
import dev.nphil.blestudio.relay.RelayState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and visible while a BLE link is held.
 *
 * Two independent reasons to run:
 *  * [MODE_RELAY] - this service owns the MITM relay run in [RelaySession] and tears it down again
 *    in [onDestroy], so the relay survives the Activity being destroyed;
 *  * [MODE_GATT] - a live GATT capture elsewhere in the app needs the process to keep its
 *    connection while the user is in another app.
 *
 * Both are claims, not commands: whoever claims a mode with [start] releases it again with
 * [release], and only the last release stops the service. A relay run that ends on its own drops
 * its own claim the same way, so it can never pull a live GATT capture down with it.
 *
 * The notification always shows what the link is doing and offers a Stop action.
 */
class BleForegroundService : LifecycleService() {

    private val modes = LinkedHashSet<String>()
    private var relayJob: Job? = null

    /** Address of the live GATT link, once one is established; only for the notification text. */
    private var gattAddress: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            RelaySession.stop()
            modes.clear()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RELEASE) {
            val released = intent.getStringExtra(EXTRA_MODE)
            if (released != null) modes -= released
            if (released == MODE_GATT) gattAddress = null
            // Another reason to hold the link may remain - a relay run outlives any one screen.
            if (modes.isEmpty()) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                update(RelaySession.state.value)
            }
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE)
        if (mode != null) modes += mode
        if (mode == MODE_GATT) {
            intent.getStringExtra(EXTRA_ADDRESS)?.let { gattAddress = it }
        }
        if (modes.isEmpty()) {
            // Nothing asked for a link; refuse to sit in the foreground for no reason.
            stopSelf()
            return START_NOT_STICKY
        }

        // From Android 14 the connectedDevice type requires a granted Bluetooth runtime permission;
        // a refusal must not take the whole process down with it.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(RelaySession.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
        val failure = foreground.exceptionOrNull()
        if (failure != null) {
            RelaySession.reportServiceFailure(
                "The foreground service could not start: ${failure.message ?: failure.javaClass.simpleName}. " +
                    "Grant the Nearby devices permission and try again.",
            )
            modes.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        if (modes.contains(MODE_RELAY)) {
            RelaySession.attach(applicationContext)
            observeRelay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        relayJob?.cancel()
        relayJob = null
        // Whatever brought the service down - user, system or stopSelf - the relay must not survive it.
        RelaySession.stop()
        modes.clear()
        super.onDestroy()
    }

    private fun observeRelay() {
        if (relayJob?.isActive == true) return
        relayJob = lifecycleScope.launch {
            var sawActive = false
            RelaySession.state.collect { state ->
                update(state)
                if (state.phase.isActive) {
                    sawActive = true
                } else if (sawActive) {
                    // The run ended on its own (stopped or failed); the UI keeps the final state.
                    // A live GATT capture may still need the process, so only the relay's own claim
                    // is dropped here - and the notification re-rendered as a plain GATT link.
                    sawActive = false
                    modes -= MODE_RELAY
                    if (modes.isEmpty()) stopSelf() else update(state)
                }
            }
        }
    }

    private fun update(state: RelayState) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE link",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while BLE Studio holds a Bluetooth connection or relays one"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: RelayState): android.app.Notification {
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, BleForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE)
        }
        val relaying = modes.contains(MODE_RELAY)
        val title = if (relaying) "BLE relay - ${state.phase.label}" else "Live GATT session"
        val text = if (relaying) relayText(state) else gattText()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun relayText(state: RelayState): String {
        val counters = state.counters
        val traffic = "${counters.reads} reads, ${counters.writes} writes, ${counters.notifies} notifies, ${counters.errors} errors"
        return when (val phase = state.phase) {
            RelayPhase.Idle -> "Starting"
            RelayPhase.ConnectingTarget -> "Connecting to ${state.targetAddress}"
            RelayPhase.Cloning -> "Mirroring the GATT database of ${state.targetAddress}"
            RelayPhase.Advertising -> "Advertising as the target, waiting for the vendor app - $traffic"
            is RelayPhase.VendorConnected -> "${phase.address} to ${state.targetAddress} - $traffic"
            RelayPhase.Stopping -> "Releasing the advertiser, server and target link"
            is RelayPhase.Failed -> phase.reason
        }
    }

    private fun gattText(): String =
        gattAddress?.let { "Connected to $it" } ?: "Holding a Bluetooth connection for a capture"

    companion object {
        const val MODE_RELAY = "relay"
        const val MODE_GATT = "gatt"

        private const val EXTRA_MODE = "dev.nphil.blestudio.extra.LINK_MODE"
        private const val EXTRA_ADDRESS = "dev.nphil.blestudio.extra.LINK_ADDRESS"
        private const val ACTION_STOP = "dev.nphil.blestudio.action.STOP_LINK"
        private const val ACTION_RELEASE = "dev.nphil.blestudio.action.RELEASE_LINK"
        private const val CHANNEL_ID = "ble_studio_link"
        private const val NOTIFICATION_ID = 0x8B1E
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2

        /**
         * Claims [mode]; the service runs until every claim is released.
         *
         * @param address device the GATT link is connected to, for the notification text.
         */
        fun start(context: Context, mode: String, address: String? = null) {
            require(mode == MODE_RELAY || mode == MODE_GATT) { "Unknown foreground link mode \"$mode\"" }
            val intent = Intent(context, BleForegroundService::class.java)
                .putExtra(EXTRA_MODE, mode)
            if (address != null) intent.putExtra(EXTRA_ADDRESS, address)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Drops [mode]'s claim, stopping the service only once nothing else holds a link.
         *
         * This is how a screen whose own link ended lets go: stopping the service outright would
         * take every other link down with it, and neither a relay run nor a live GATT capture is
         * owned by the screen that started it.
         */
        fun release(context: Context, mode: String) {
            require(mode == MODE_RELAY || mode == MODE_GATT) { "Unknown foreground link mode \"$mode\"" }
            val intent = Intent(context, BleForegroundService::class.java)
                .setAction(ACTION_RELEASE)
                .putExtra(EXTRA_MODE, mode)
            // A no-op if the service is already gone; never worth starting one just to stop it.
            runCatching { context.startService(intent) }
        }
    }
}
