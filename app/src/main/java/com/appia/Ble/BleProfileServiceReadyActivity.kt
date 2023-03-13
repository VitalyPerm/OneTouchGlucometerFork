package com.appia.Ble

import android.annotation.SuppressLint
import com.appia.bioland.R
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.appia.Ble.BleProfileService.LocalBinder
import com.appia.Ble.scanner.ScannerFragment
import com.appia.Ble.scanner.ScannerFragment.OnDeviceSelectedListener
import no.nordicsemi.android.ble.BleManagerCallbacks
import java.util.*

/**
 *
 *
 * The [BleProfileServiceReadyActivity] activity is designed to be the base class for profile activities that uses services in order to connect to the
 * device. When user press CONNECT button a service is created and the activity binds to it. The service tries to connect to the service and notifies the
 * activity using Local Broadcasts ([LocalBroadcastManager]). See [BleProfileService] for messages. If the device is not in range it will listen for
 * it and connect when it become visible. The service exists until user will press DISCONNECT button.
 *
 *
 *
 * When user closes the activity (f.e. by pressing Back button) while being connected, the Service remains working. It's still connected to the device or still
 * listens for it. When entering back to the activity, activity will to bind to the service and refresh UI.
 *
 */
abstract class BleProfileServiceReadyActivity<E : LocalBinder?> :
    AppCompatActivity(), OnDeviceSelectedListener, BleManagerCallbacks {
    /**
     * Returns the service interface that may be used to communicate with the sensor. This will return `null` if the device is disconnected from the
     * sensor.
     *
     * @return the service binder or `null`
     */
    protected var service: E? = null
        private set
    private var deviceNameView: TextView? = null
    private var connectButton: Button? = null
    private var bluetoothDevice: BluetoothDevice? = null

    /**
     * Returns the name of the device that the phone is currently connected to or was connected last time
     */
    protected var deviceName: String? = null
        private set
    private val commonBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Check if the broadcast applies the connected device
            if (!isBroadcastForThisDevice(intent)) return
            val bluetoothDevice =
                intent.getParcelableExtra<BluetoothDevice>(BleProfileService.EXTRA_DEVICE)
                    ?: return
            when (intent.action) {
                BleProfileService.BROADCAST_CONNECTION_STATE -> {
                    val state = intent.getIntExtra(
                        BleProfileService.EXTRA_CONNECTION_STATE,
                        BleProfileService.STATE_DISCONNECTED
                    )
                    when (state) {
                        BleProfileService.STATE_CONNECTED -> {
                            deviceName = intent.getStringExtra(BleProfileService.EXTRA_DEVICE_NAME)
                            onDeviceConnected(bluetoothDevice)
                        }
                        BleProfileService.STATE_DISCONNECTED -> {
                            onDeviceDisconnected(bluetoothDevice)
                            deviceName = null
                        }
                        BleProfileService.STATE_LINK_LOSS -> {
                            onLinkLossOccurred(bluetoothDevice)
                        }
                        BleProfileService.STATE_CONNECTING -> {
                            onDeviceConnecting(bluetoothDevice)
                        }
                        BleProfileService.STATE_DISCONNECTING -> {
                            onDeviceDisconnecting(bluetoothDevice)
                        }
                        else -> {}
                    }
                }
                BleProfileService.BROADCAST_SERVICES_DISCOVERED -> {
                    val primaryService =
                        intent.getBooleanExtra(BleProfileService.EXTRA_SERVICE_PRIMARY, false)
                    val secondaryService =
                        intent.getBooleanExtra(BleProfileService.EXTRA_SERVICE_SECONDARY, false)
                    if (primaryService) {
                        onServicesDiscovered(bluetoothDevice, secondaryService)
                    } else {
                        onDeviceNotSupported(bluetoothDevice)
                    }
                }
                BleProfileService.BROADCAST_DEVICE_READY -> {
                    onDeviceReady(bluetoothDevice)
                }
                BleProfileService.BROADCAST_BOND_STATE -> {
                    val state = intent.getIntExtra(
                        BleProfileService.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE
                    )
                    when (state) {
                        BluetoothDevice.BOND_BONDING -> onBondingRequired(bluetoothDevice)
                        BluetoothDevice.BOND_BONDED -> onBonded(bluetoothDevice)
                    }
                }
                BleProfileService.BROADCAST_ERROR -> {
                    val message = intent.getStringExtra(BleProfileService.EXTRA_ERROR_MESSAGE)
                    val errorCode = intent.getIntExtra(BleProfileService.EXTRA_ERROR_CODE, 0)
                    onError(bluetoothDevice, message!!, errorCode)
                }
            }
        }
    }
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            this@BleProfileServiceReadyActivity.service = service as E
            val bleService: E = this@BleProfileServiceReadyActivity.service!!
            bluetoothDevice = bleService!!.getBluetoothDevice()
            Log.d(TAG, "Activity bound to the service")
            onServiceBound(bleService)

            // Update UI
            deviceName = bleService.getDeviceName()
            deviceNameView!!.text = deviceName
            connectButton!!.text = getString(com.appia.bioland.R.string.action_disconnect)

            // And notify user if device is connected
            if (bleService.isConnected) {
                onDeviceConnected(bluetoothDevice!!)
            } else {
                // If the device is not connected it means that either it is still connecting,
                // or the link was lost and service is trying to connect to it (autoConnect=true).
                onDeviceConnecting(bluetoothDevice!!)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Note: this method is called only when the service is killed by the system,
            // not when it stops itself or is stopped by the activity.
            // It will be called only when there is critically low memory, in practice never
            // when the activity is in foreground.
            Log.d(TAG, "Activity disconnected from the service")
            deviceNameView!!.setText(defaultDeviceName)
            connectButton!!.setText(R.string.action_connect)
            service = null
            deviceName = null
            bluetoothDevice = null
            onServiceUnbound()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureBLESupported()
        if (!isBLEEnabled) {
            showBLEDialog()
        }

        // Restore the old log session
        if (savedInstanceState != null) {
            val logUri = savedInstanceState.getParcelable<Uri>(LOG_URI)
        }

        // In onInitialize method a final class may register local broadcast receivers that will listen for events from the service
        onInitialize(savedInstanceState)
        // The onCreateView class should... create the view
        onCreateView(savedInstanceState)
        val toolbar = findViewById<Toolbar>(R.id.toolbar_actionbar)
        setSupportActionBar(toolbar)

        // Common nRF Toolbox view references are obtained here
        setUpView()
        // View is ready to be used
        onViewCreated(savedInstanceState)
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(commonBroadcastReceiver, makeIntentFilter())
    }

    override fun onStart() {
        super.onStart()

        /*
		 * If the service has not been started before, the following lines will not start it.
		 * However, if it's running, the Activity will bind to it and notified via serviceConnection.
		 */
        val service = Intent(this, serviceClass)
        // We pass 0 as a flag so the service will not be created if not exists.
        bindService(service, serviceConnection, 0)

        /*
		 * When user exited the UARTActivity while being connected, the log session is kept in
		 * the service. We may not get it before binding to it so in this case this event will
		 * not be logged (logSession is null until onServiceConnected(..) is called).
		 * It will, however, be logged after the orientation changes.
		 */
    }

    override fun onStop() {
        super.onStop()
        try {
            // We don't want to perform some operations (e.g. disable Battery Level notifications)
            // in the service if we are just rotating the screen. However, when the activity will
            // disappear, we may want to disable some device features to reduce the battery
            // consumption.
            if (service != null) service!!.setActivityIsChangingConfiguration(
                isChangingConfigurations
            )
            unbindService(serviceConnection)
            service = null
            Log.d(TAG, "Activity unbound from the service")
            onServiceUnbound()
            deviceName = null
            bluetoothDevice = null
        } catch (e: IllegalArgumentException) {
            // do nothing, we were not connected to the sensor
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commonBroadcastReceiver)
    }

    /**
     * Called when activity binds to the service. The parameter is the object returned in [Service.onBind] method in your service. The method is
     * called when device gets connected or is created while sensor was connected before. You may use the binder as a sensor interface.
     */
    protected abstract fun onServiceBound(binder: E)

    /**
     * Called when activity unbinds from the service. You may no longer use this binder because the sensor was disconnected. This method is also called when you
     * leave the activity being connected to the sensor in the background.
     */
    protected abstract fun onServiceUnbound()

    /**
     * Returns the service class for sensor communication. The service class must derive from [BleProfileService] in order to operate with this class.
     *
     * @return the service class
     */
    protected abstract val serviceClass: Class<out BleProfileService?>?

    /**
     * You may do some initialization here. This method is called from [.onCreate] before the view was created.
     */
    protected open fun onInitialize(savedInstanceState: Bundle?) {
        // empty default implementation
    }

    /**
     * Called from [.onCreate]. This method should build the activity UI, i.e. using [.setContentView].
     * Use to obtain references to views. Connect/Disconnect button, the device name view are manager automatically.
     *
     * @param savedInstanceState contains the data it most recently supplied in [.onSaveInstanceState].
     * Note: **Otherwise it is null**.
     */
    protected abstract fun onCreateView(savedInstanceState: Bundle?)

    /**
     * Called after the view has been created.
     *
     * @param savedInstanceState contains the data it most recently supplied in [.onSaveInstanceState].
     * Note: **Otherwise it is null**.
     */
    private fun onViewCreated(savedInstanceState: Bundle?) {
        // empty default implementation
    }

    /**
     * Called after the view and the toolbar has been created.
     */
    private fun setUpView() {
        // set GUI
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        connectButton = findViewById<Button>(R.id.action_connect)
        deviceNameView = findViewById<TextView>(R.id.device_name)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SIS_DEVICE_NAME, deviceName)
        outState.putParcelable(SIS_DEVICE, bluetoothDevice)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        deviceName = savedInstanceState.getString(SIS_DEVICE_NAME)
        bluetoothDevice = savedInstanceState.getParcelable(SIS_DEVICE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // TODO: lo comento
        //getMenuInflater().inflate(R.menu.help, menu);
        return true
    }

    /**
     * Use this method to handle menu actions other than home and about.
     *
     * @param itemId the menu item id
     * @return `true` if action has been handled
     */
    protected fun onOptionsItemSelected(itemId: Int): Boolean {
        // Overwrite when using menu other than R.menu.help
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when (id) {
            R.id.home -> onBackPressed()
            else -> return onOptionsItemSelected(id)
        }
        return true
    }

    /**
     * Called when user press CONNECT or DISCONNECT button. See layout files -> onClick attribute.
     */
    fun onConnectClicked(view: View?) {
        if (isBLEEnabled) {
            if (service == null) {
                setDefaultUI()
                filterUUID?.let { showDeviceScanningDialog(it) }
            } else {
                service!!.disconnect()
            }
        } else {
            showBLEDialog()
        }
    }

    override fun onDeviceSelected(device: BluetoothDevice, name: String?) {
        bluetoothDevice = device
        deviceName = name

        // The device may not be in the range but the service will try to connect to it if it reach it
        Log.d(TAG, "Creating service...")
        val service = Intent(this, serviceClass)
        service.putExtra(BleProfileService.EXTRA_DEVICE_ADDRESS, device.address)
        service.putExtra(BleProfileService.EXTRA_DEVICE_NAME, name)
        startService(service)
        Log.d(TAG, "Binding to the service...")
        bindService(service, serviceConnection, 0)
    }

    override fun onDialogCanceled() {
        // do nothing
    }

    override fun onDeviceConnecting(device: BluetoothDevice) {
        deviceNameView!!.text =
            if (deviceName != null) deviceName else getString(R.string.not_available)
        connectButton!!.setText(R.string.action_connecting)
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        deviceNameView!!.text = deviceName
        connectButton!!.setText(R.string.action_disconnect)
    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {
        connectButton!!.setText(R.string.action_disconnecting)
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        connectButton!!.setText(R.string.action_connect)
        deviceNameView!!.setText(defaultDeviceName)
        try {
            Log.d(TAG, "Unbinding from the service...")
            unbindService(serviceConnection)
            service = null
            Log.d(TAG, "Activity unbound from the service")
            onServiceUnbound()
            deviceName = null
            bluetoothDevice = null
        } catch (e: IllegalArgumentException) {
            // do nothing. This should never happen but does...
        }
    }

    override fun onLinkLossOccurred(device: BluetoothDevice) {
        // empty default implementation
    }

    override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {
        // empty default implementation
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        // empty default implementation
    }

    override fun onBondingRequired(device: BluetoothDevice) {
        // empty default implementation
    }

    override fun onBonded(device: BluetoothDevice) {
        // empty default implementation
    }

    override fun onBondingFailed(device: BluetoothDevice) {
        // empty default implementation
    }

    override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
        Log.e(
            TAG,
            "Error occurred: $message,  error code: $errorCode"
        )
        showToast("$message ($errorCode)")
    }

    override fun onDeviceNotSupported(device: BluetoothDevice) {
        showToast(R.string.not_supported)
    }

    /**
     * Shows a message as a Toast notification. This method is thread safe, you can call it from any thread
     *
     * @param message a message to be shown
     */
    protected fun showToast(message: String?) {
        runOnUiThread {
            Toast.makeText(
                this@BleProfileServiceReadyActivity,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Shows a message as a Toast notification. This method is thread safe, you can call it from any thread
     *
     * @param messageResId an resource id of the message to be shown
     */
    protected fun showToast(messageResId: Int) {
        runOnUiThread {
            Toast.makeText(
                this@BleProfileServiceReadyActivity,
                messageResId,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Returns `true` if the device is connected. Services may not have been discovered yet.
     */
    protected val isDeviceConnected: Boolean
        protected get() = service != null && service!!.isConnected

    /**
     * Restores the default UI before reconnecting
     */
    protected abstract fun setDefaultUI()

    /**
     * Returns the default device name resource id. The real device name is obtained when connecting to the device. This one is used when device has
     * disconnected.
     *
     * @return the default device name resource id
     */
    protected abstract val defaultDeviceName: Int

    /**
     * Returns the string resource id that will be shown in About box
     *
     * @return the about resource id
     */
    protected abstract val aboutTextId: Int

    /**
     * The UUID filter is used to filter out available devices that does not have such UUID in their advertisement packet. See also:
     * [.isChangingConfigurations].
     *
     * @return the required UUID or `null`
     */
    protected abstract val filterUUID: UUID?

    /**
     * Checks the [BleProfileService.EXTRA_DEVICE] in the given intent and compares it with the connected BluetoothDevice object.
     * @param intent intent received via a broadcast from the service
     * @return true if the data in the intent apply to the connected device, false otherwise
     */
    protected fun isBroadcastForThisDevice(intent: Intent): Boolean {
        val btDevice =
            intent.getParcelableExtra<BluetoothDevice>(BleProfileService.EXTRA_DEVICE)
        return btDevice != null && btDevice == bluetoothDevice
    }

    /**
     * Shows the scanner fragment.
     *
     * @param filter               the UUID filter used to filter out available devices. The fragment will always show all bonded devices as there is no information about their
     * services
     * @see .getFilterUUID
     */
    private fun showDeviceScanningDialog(filter: UUID) {
        val dialog = ScannerFragment.getInstance(filter)
        dialog.show(supportFragmentManager, "scan_fragment")
    }

    private fun ensureBLESupported() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.no_ble, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val isBLEEnabled: Boolean
         get() {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            return adapter != null && adapter.isEnabled
        }

    @SuppressLint("MissingPermission")
    private fun showBLEDialog() {
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
    }

    companion object {
        private const val TAG = "BleProfServActivity"
        private const val SIS_DEVICE_NAME = "device_name"
        private const val SIS_DEVICE = "device"
        private const val LOG_URI = "log_uri"
        protected const val REQUEST_ENABLE_BT = 2
        private fun makeIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(BleProfileService.BROADCAST_CONNECTION_STATE)
            intentFilter.addAction(BleProfileService.BROADCAST_SERVICES_DISCOVERED)
            intentFilter.addAction(BleProfileService.BROADCAST_DEVICE_READY)
            intentFilter.addAction(BleProfileService.BROADCAST_BOND_STATE)
            intentFilter.addAction(BleProfileService.BROADCAST_ERROR)
            return intentFilter
        }
    }
}
