package com.appia.onetouch.protocol.bleuart

interface BleuartCallbacks {
    fun sendData(aBytes: ByteArray?)

    /**
     *
     * @param aBytes
     */
    fun onPacketReceived(aBytes: ByteArray?)
}