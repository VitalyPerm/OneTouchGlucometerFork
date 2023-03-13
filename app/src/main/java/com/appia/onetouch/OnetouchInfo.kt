package com.appia.onetouch

import java.util.*

class OnetouchInfo {
    var protocolVersion = 0
    var batteryCapacity = 0
    lateinit var serialNumber: ByteArray
    var productionDate: GregorianCalendar? = null

    override fun toString(): String {
        return "Battery: " + batteryCapacity + "% Protocol: " + protocolVersion + " Serial N°: " + bytesToHex(
            serialNumber
        )
    }

    private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()
    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }
}