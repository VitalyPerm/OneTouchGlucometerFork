package com.appia.onetouch

import no.nordicsemi.android.ble.BleManagerCallbacks

interface OnetouchCallbacks: BleManagerCallbacks {
    /**
     * Called when new measurements are available. This measurements must be stored by the one who
     * implements this interface.
     * @param aMeasurements
     */
    fun onMeasurementsReceived(aMeasurements: ArrayList<OnetouchMeasurement?>?)

    /**
     * Called when device information is received.
     * @param aInfo
     */
    fun onDeviceInfoReceived(aInfo: OnetouchInfo?)

    /**
     * Called when an error occurs during the communication.
     * @param aMessage
     */
    fun onProtocolError(aMessage: String?)
}