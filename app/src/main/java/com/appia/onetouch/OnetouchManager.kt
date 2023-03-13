package com.appia.onetouch

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.appia.onetouch.protocol.Protocol
import com.appia.onetouch.protocol.ProtocolCallbacks
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import java.util.*

class OnetouchManager(context: Context) : BleManager<OnetouchCallbacks>(context),
    ProtocolCallbacks {
    private var mRxCharacteristic: BluetoothGattCharacteristic? = null
    private var mTxCharacteristic: BluetoothGattCharacteristic? = null
    private val mProtocol = Protocol(this, 20)

    /**
     * Sends the request to obtain all the records stored in the device.
     */
    fun requestMeasurements() {
        //mProtocol.requestMeasurements(); TODO
    }

    /**
     * Sends the request to obtain the device information.
     */
    fun requestDeviceInfo() { /*mProtocol.requestDeviceInfo();*/
    }

    override fun log(priority: Int, message: String) {
        // Uncomment to see Bluetooth Logs
        Log.println(priority, TAG, message)
    }

    override fun getGattCallback(): BleManagerGattCallback {
        return OnetouchManagerGattCallback()
    }

    /**
     * BluetoothGatt callbacks for connection/disconnection, service discovery,
     * receiving indication, etc.
     */
    private inner class OnetouchManagerGattCallback : BleManagerGattCallback() {
        override fun initialize() {
            if (isConnected) {
                requestMtu(20 + 3)
                    .with { device: BluetoothDevice?, mtu: Int ->
                        Log.i(
                            TAG,
                            "MTU set to $mtu"
                        )
                    }
                    .fail { device: BluetoothDevice?, status: Int ->
                        log(
                            Log.WARN,
                            "MTU change failed."
                        )
                    }
                    .enqueue()
/* Register callback to get data from the device. */
                setNotificationCallback(mTxCharacteristic)
                    .with { device: BluetoothDevice?, data: Data ->
                        Log.v(
                            TAG,
                            "BLE data received: $data"
                        )
                        mProtocol.onDataReceived(data.value)
                    }
                enableNotifications(mTxCharacteristic)
                    .done { device: BluetoothDevice? ->
                        Log.i(
                            TAG,
                            "Onetouch TX characteristic  notifications enabled"
                        )
                        mProtocol.getTime()
                    }
                    .fail { device: BluetoothDevice?, status: Int ->
                        Log.w(
                            TAG,
                            "Onetouch TX characteristic  notifications not enabled"
                        )
                    }
                    .enqueue()
            }
        }

        public override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(ONETOUCH_SERVICE_UUID)
            if (service != null) {
                mRxCharacteristic = service.getCharacteristic(ONETOUCH_RX_CHARACTERISTIC_UUID)
                mTxCharacteristic = service.getCharacteristic(ONETOUCH_TX_CHARACTERISTIC_UUID)
            }
            var writeRequest = false
            var writeCommand = false
            if (mRxCharacteristic != null) {
                val rxProperties = mRxCharacteristic!!.properties
                writeRequest = rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0
                writeCommand =
                    rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0

                // Set the WRITE REQUEST type when the characteristic supports it.
                // This will allow to send long write (also if the characteristic support it).
                // In case there is no WRITE REQUEST property, this manager will divide texts
                // longer then MTU-3 bytes into up to MTU-3 bytes chunks.
                //if (writeRequest)
                mRxCharacteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                //else
            }
            return (mRxCharacteristic != null) && (mTxCharacteristic != null) &&
                    (writeRequest || writeCommand)
        }

        override fun onDeviceReady() {
            super.onDeviceReady()
            mProtocol.connect()
        }

        override fun onDeviceDisconnected() {
            mProtocol.disconnect()
            // Release all references.
            mRxCharacteristic = null
            mTxCharacteristic = null
        }
    }

    override fun onMeasurementsReceived(aMeasurements: ArrayList<OnetouchMeasurement?>?) {

        /* Notify new measurements were received. */
        mCallbacks!!.onMeasurementsReceived(aMeasurements)
    }

    //	public void onDeviceInfoReceived(OnetouchInfo aInfo) {
    //		/* Notify info was received. */
    //		mCallbacks.onDeviceInfoReceived(aInfo);
    //	}
    override fun onProtocolError(aMessage: String?) {
        mCallbacks!!.onProtocolError(aMessage)
    }

    /**
     * Sends the given bytes to RX characteristic.
     * @param bytes the text to be sent
     */
    override fun sendData(bytes: ByteArray?) {
        // Are we connected?
        if (mRxCharacteristic == null) {
            Log.e(TAG, "Tried to send data but mRxCharacteristic was null: " + bytes.toString())
            return
        }
        if (bytes != null && bytes.size > 0) {
            writeCharacteristic(mRxCharacteristic, bytes)
                .with { device: BluetoothDevice?, data: Data ->
                    Log.v(
                        TAG,
                        "\"$data\" sent"
                    )
                } //.split()
                .enqueue()
        }
    }

    companion object {
        /** Onetouch communication service UUID  */
        val ONETOUCH_SERVICE_UUID = UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b")

        /** RX characteristic UUID  */
        private val ONETOUCH_RX_CHARACTERISTIC_UUID =
            UUID.fromString("af9df7a2-e595-11e3-96b4-0002a5d5c51b")

        /** TX characteristic UUID  */
        private val ONETOUCH_TX_CHARACTERISTIC_UUID =
            UUID.fromString("af9df7a3-e595-11e3-96b4-0002a5d5c51b")
        private const val TAG = "OnetouchManager"
    }
}