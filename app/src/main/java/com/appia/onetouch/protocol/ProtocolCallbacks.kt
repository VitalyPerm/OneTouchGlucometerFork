package com.appia.onetouch.protocol

import com.appia.onetouch.OnetouchMeasurement

interface ProtocolCallbacks {
    /**
     *
     * @param bytes
     */
    fun sendData(bytes: ByteArray?)

    /**
     *
     * @param aMeasurements
     */
    fun onMeasurementsReceived(aMeasurements: ArrayList<OnetouchMeasurement?>?)

    /**
     *
     */
    fun onProtocolError(aMessage: String?)
}