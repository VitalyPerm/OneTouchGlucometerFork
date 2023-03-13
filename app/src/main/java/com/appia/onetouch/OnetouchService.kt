package com.appia.onetouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.appia.Ble.BleProfileService
import com.appia.bioland.R
import com.appia.main.OnetouchActivity
import no.nordicsemi.android.ble.BleManager


class OnetouchService : BleProfileService(), OnetouchCallbacks {
    private var mManager: OnetouchManager? = null
    var mMeasurements = ArrayList<OnetouchMeasurement?>()
    private var deviceInfo: OnetouchInfo? = null

    /* This binder is an interface for the binded activity to operate with the device. */
    inner class OnetouchBinder : LocalBinder() {


        val dInfo get() = deviceInfo

        val measurements: ArrayList<OnetouchMeasurement?>
            get() {
                val ret = ArrayList(mMeasurements)
                mMeasurements.clear()
                return ret
            }

        fun requestMeasurements() {
            if (isConnected) {
                mManager!!.requestMeasurements()
            }
        }

        /**
         * Send a request to read device information.
         */
        fun requestDeviceInfo() {
            if (isConnected) {
                mManager!!.requestDeviceInfo() // Todo:
            }
        }
    }

    private val mBinder: LocalBinder = OnetouchBinder()
    fun onCountdownReceived(aCount: Int) {
        val broadcast = Intent(BROADCAST_COUNTDOWN)
        broadcast.putExtra(EXTRA_COUNTDOWN, aCount)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
    }

    /**
     * Called by OnetouchManager when all measurements were received.
     */
    override fun onMeasurementsReceived(aMeasurements: ArrayList<OnetouchMeasurement?>?) {
        mMeasurements.addAll(aMeasurements!!)
        val broadcast = Intent(BROADCAST_MEASUREMENT)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
        if (!bound) {
            wakeUpScreen()
            updateNotification(R.string.notification_new_measurements_message, true, true)
        }
    }

    /**
     * Called by OnetouchManager when device information is received..
     */
    override fun onDeviceInfoReceived(aInfo: OnetouchInfo?) {
        deviceInfo = aInfo
        val broadcast = Intent(BROADCAST_INFORMATION)
        broadcast.putExtra(EXTRA_BATTERY_CAPACITY, aInfo!!.batteryCapacity)
        broadcast.putExtra(EXTRA_SERIAL_NUMBER, aInfo.serialNumber)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
    }

    /**
     * Called by OnetouchManager when an error has occured in the communication with the device.
     */
    override fun onProtocolError(aMessage: String?) {
        val broadcast = Intent(BROADCAST_COMM_FAILED)
        broadcast.putExtra(EXTRA_ERROR_MSG, aMessage)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
    }

    override fun initializeManager(): BleManager<OnetouchCallbacks> {
        mManager = OnetouchManager(this)
        return mManager as OnetouchManager
    }

    override fun shouldAutoConnect(): Boolean {
        return true
    }

    override fun onCreate() {
        super.onCreate()

        /* Receive disconnect action.*/registerReceiver(
            disconnectActionBroadcastReceiver, IntentFilter(
                ACTION_DISCONNECT
            )
        )
        /* Receive tbd actions.*/
        //registerReceiver(intentBroadcastReceiver,new IntentFilter(ACTION_TBD));
    }

    public override fun onServiceStarted() {
        createNotificationChannel()
    }

    override fun onDestroy() {
        // when user has disconnected from the sensor, we have to cancel the notification that we've created some milliseconds before using unbindService
        stopForegroundService()
        unregisterReceiver(disconnectActionBroadcastReceiver)
        //unregisterReceiver(intentBroadcastReceiver);
        super.onDestroy()
    }

    override fun onRebind() {
        stopForegroundService()
    }

    override fun onUnbind() {
        startForegroundService()
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        super.onDeviceConnected(device)
        Log.d(TAG, "Device connected")
        if (!bound) {
            updateNotification(R.string.notification_connected_message)
        }
    }

    override fun onLinkLossOccurred(device: BluetoothDevice) {
        super.onLinkLossOccurred(device)
        Log.d(TAG, "Link loss ocurred")
        if (!bound && mMeasurements.size == 0) {
            updateNotification(R.string.notification_waiting)
        }
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        super.onDeviceDisconnected(device)
        Log.d(TAG, "Device disconnected")
        if (!bound) {
            updateNotification(R.string.notification_disconnected_message)
        }
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        super.onDeviceReady(device)
    }

    override fun onBluetoothDisabled() {}
    override fun onBluetoothEnabled() {
        super.onBluetoothEnabled()

        /* Get bluetooth device. */
        val device = bluetoothDevice
        if (device != null && !isConnected) {
            /* If it was previously connected, reconnect! */
            Log.d(TAG, "Reconnecting...")
            mManager!!.connect(bluetoothDevice!!).enqueue()
        }
    }

    override fun stopWhenDisconnected(): Boolean {
        return false
    }

    /**
     * Sets the service as a foreground service
     */
    private fun startForegroundService() {
        // when the activity closes we need to show the notification that user is connected to the peripheral sensor
        // We start the service as a foreground service as Android 8.0 (Oreo) onwards kills any running background services
        val notification: Notification = if (isConnected) {
            createNotification(R.string.notification_connected_message, false, false)
        } else {
            createNotification(R.string.notification_waiting, false, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Stops the service as a foreground service
     */
    private fun stopForegroundService() {
        // when the activity rebinds to the service, remove the notification and stop the foreground service
        // on devices running Android 8.0 (Oreo) or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        } else {
            cancelNotification()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Onetouch Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            serviceChannel.description = getString(R.string.channel_connected_devices_description)
            serviceChannel.setShowBadge(false)
            serviceChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            serviceChannel.enableVibration(true)
            serviceChannel.setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            )
            val mManager = getSystemService(
                NotificationManager::class.java
            )!!
            mManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun wakeUpScreen() {
        val pm = this.getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "OnetouchAPP:" + TAG
            )
            wl.acquire(10000)
            val wl_cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnetouchAPP:" + TAG)
            wl_cpu.acquire(10000)
        }
    }

    fun updateNotification(messageResId: Int, aVibrate: Boolean, aSound: Boolean) {
        val notification = createNotification(messageResId, aVibrate, aSound)
        val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun updateNotification(messageResId: Int) {
        val notification = createNotification(messageResId, false, false)
        val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Creates the notification
     *
     * @param messageResId message resource id. The message must have one String parameter,<br></br>
     * f.e. `<string name="name">%s is connected</string>`
     */
    protected fun createNotification(
        messageResId: Int,
        aVibrate: Boolean,
        aSound: Boolean
    ): Notification {
        val intent = Intent(this, OnetouchActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this,
            OPEN_ACTIVITY_REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        builder.setContentIntent(pendingIntent)
        builder.setContentTitle(getString(R.string.app_name))
        builder.setContentText(getString(messageResId, deviceName))
        builder.setSmallIcon(R.drawable.ic_appia_notification)
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setShowWhen(true)
        builder.setAutoCancel(true)
        builder.setOngoing(true)
        var defaults = 0
        if (aVibrate) {
            defaults = defaults or Notification.DEFAULT_VIBRATE
        } else if (aSound) {
            defaults = defaults or Notification.DEFAULT_SOUND
        }
        builder.setDefaults(defaults)
        if (isConnected) {
            val disconnect = Intent(ACTION_DISCONNECT)
            val disconnectAction = PendingIntent.getBroadcast(
                this,
                DISCONNECT_REQ,
                disconnect,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_action_bluetooth,
                    getString(R.string.notification_action_disconnect),
                    disconnectAction
                )
            )
        }
        return builder.build()
    }

    /**
     * Cancels the existing notification. If there is no active notification this method does nothing
     */
    private fun cancelNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    /**
     * This broadcast receiver listens for [.ACTION_DISCONNECT] that may be fired by pressing Disconnect action button on the notification.
     */
    private val disconnectActionBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isConnected) binder!!.disconnect() else stopSelf()
            }
        }

    companion object {
        private const val TAG = "OnetouchService"

        /* Notifications channel. */
        private const val CHANNEL_ID = "channel_id"

        /**
         * Measurement Broadcast!
         */
        const val BROADCAST_COUNTDOWN = "com.appia.onetouch.BROADCAST_COUNTDOWN"
        const val EXTRA_COUNTDOWN = "com.appia.onetouch.EXTRA_COUNTDOWN"
        const val BROADCAST_MEASUREMENT = "com.appia.onetouch.BROADCAST_MEASUREMENT"
        const val EXTRA_GLUCOSE_LEVEL = "com.appia.onetouch.EXTRA_GLUCOSE_LEVEL"
        const val BROADCAST_INFORMATION = "com.appia.onetouch.BROADCAST_INFORMATION"
        const val EXTRA_BATTERY_CAPACITY = "com.appia.onetouch.EXTRA_BATTERY_CAPACITY"
        const val EXTRA_SERIAL_NUMBER = "com.appia.onetouch.EXTRA_SERIAL_NUMBER"
        const val BROADCAST_COMM_FAILED = "com.appia.onetouch.BROADCAST_COMM_FAILED"
        const val EXTRA_ERROR_MSG = "com.appia.onetouch.EXTRA_ERROR_MSG"

        /**
         * Action send when user press the DISCONNECT button on the notification.
         */
        const val ACTION_DISCONNECT = "com.appia.onetouch.uart.ACTION_DISCONNECT"

        /* Notification things...*/
        private const val NOTIFICATION_ID = 349 // random
        private const val OPEN_ACTIVITY_REQ = 67 // random
        private const val DISCONNECT_REQ = 97 // random
    }
}
