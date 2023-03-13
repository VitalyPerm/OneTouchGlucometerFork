package com.appia.onetouch.protocol

import android.util.Log
import com.appia.onetouch.OnetouchMeasurement
import com.appia.onetouch.protocol.bleuart.Bleuart
import com.appia.onetouch.protocol.bleuart.BleuartCallbacks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class Protocol(aCallbacks: ProtocolCallbacks?, aMaxPacketSize: Int) : BleuartCallbacks {
    private val tag = "OneTouchProtocol"

    // Abstracts serial communication
    private var protocolCallbacks: ProtocolCallbacks? = aCallbacks

    private val packetInitialByte = 1 // Always 0x02

    private val packetLengthBytes = 2 // 16 bit packet length (little endian)

    private val packetPayloadBeginByteA = 1 // Always 0x04 before payload

    private val packetPayloadBeginByteB = 1 // Always 0x06 before payload when receiving

    private val packetPayloadEndByte = 1 // Always 0x03 after payload

    private val packetCRCByte = 2 // 16 bit checksum (little endian)


    private val packetPayloadBegin = packetInitialByte +
            packetLengthBytes +
            packetPayloadBeginByteA +
            packetPayloadBeginByteB

    private val protocolOverHead = packetInitialByte +
            packetLengthBytes +
            packetPayloadBeginByteA +
            packetPayloadBeginByteB +
            packetPayloadEndByte +
            packetCRCByte

    private val protocolSendingOverhead = packetInitialByte +
            packetLengthBytes +
            packetPayloadBeginByteA +
            packetPayloadEndByte +
            packetCRCByte

    private val deviceTimeOffset = 946684799 // Year 2000 UNIX time


    private var mHighestMeasIndex: Short = 0
    private var mHighestMeasID: Short = 0
    private var mHighestStoredMeasID: Short = 0
    private var mSynced = false

    private var mMeasurements = ArrayList<OnetouchMeasurement?>()

    enum class State {
        IDLE,
        WAITING_TIME,
        WAITING_HIGHEST_ID,
        WAITING_OLDEST_INDEX,
        WAITING_MEASUREMENT
    }

    private var mBleUart: Bleuart? = null
    private var mState: State? = null
    private var timer: Timer? = null

    // packing an array of 4 bytes to an int, little endian, clean code
    private fun intFromByteArray(bytes: ByteArray?): Int {
        return ByteBuffer.wrap(bytes!!).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun shortFromByteArray(bytes: ByteArray?): Short {
        return ByteBuffer.wrap(bytes!!).order(ByteOrder.LITTLE_ENDIAN).short
    }

    /**
     * Called by Bleuart protocol to send bytes over ble
     *
     * @param aBytes
     */
    override fun sendData(aBytes: ByteArray?) {
        protocolCallbacks!!.sendData(aBytes)
    }

    override fun onPacketReceived(aBytes: ByteArray?) {
        try {
            val payload = extractPayload(aBytes!!)
            Log.d(tag, "Packet received: " + bytesToHex(payload))
            when (mState) {
                State.WAITING_TIME -> when (payload.size) {
                    4 -> { // Time get response
                        handleTimeGet(computeUnixTime(payload).toLong())
                    }
                    0 -> { // Time set response (empty)
                        handleTimeSet()
                    }
                    else -> {
                        Log.e(tag, "Unexpected payload waiting for time request!")
                    }
                }
                State.WAITING_HIGHEST_ID -> if (payload.size == 4) {
                    val highestID = intFromByteArray(payload)
                    handleHighestRecordID(highestID.toShort())
                } else {
                    Log.e(tag, "Unexpected payload waiting for highest record ID!")
                }
                State.WAITING_OLDEST_INDEX -> if (payload.size == 2) {
                    val recordCount = shortFromByteArray(payload)
                    handleTotalRecordCount(recordCount)
                } else {
                    Log.e(tag, "Unexpected payload waiting for total record request!")
                }
                State.WAITING_MEASUREMENT -> if (payload.size == 11) {
                    val measTime = computeUnixTime(payload.copyOfRange(0, 0 + 4))
                    val measValue = shortFromByteArray(payload.copyOfRange(4, 4 + 2))
                    val measError = shortFromByteArray(payload.copyOfRange(9, 9 + 2))
                    handleMeasurementByID(measTime, measValue, measError)
                } else if (payload.isEmpty()) {
                    // Measurement was not found! Indicate with aMeasTime=0
                    handleMeasurementByID(0, 0.toShort(), 0.toShort())
                } else if (payload.size == 16) {
                    val measIndex = shortFromByteArray(payload.copyOfRange(0, 0 + 2))
                    val measID = shortFromByteArray(payload.copyOfRange(3, 3 + 2))
                    val measTime = computeUnixTime(payload.copyOfRange(5, 5 + 4))
                    val measValue = shortFromByteArray(payload.copyOfRange(9, 9 + 2))
                    handleMeasurementByIndex(
                        measIndex,
                        measID,
                        measTime,
                        measValue
                    )
                }
                else -> {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    /**********************************************************************************************/
    /** */
    /**
     * Called by the lower layer when a ble data is received
     *
     * @param bytes
     */
    fun onDataReceived(bytes: ByteArray?) {
        // Forward data to the bleuart protocol.
        mBleUart!!.onDataReceived(bytes!!)
    }

    /** */ // Function to be called when the device connected
    fun connect() {
        if (mState == State.IDLE) {
            timer = Timer()
            getTime()
        }
    }

    // Function to be called when the device disconnects
    fun disconnect() {
        // Cancel any pending schedules
        timer!!.cancel()
        // Set state to disconnected
        mState = State.IDLE
    }

    fun getTime() {
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x20, 0x02)))
        mState = State.WAITING_TIME
    }

    private fun setTime() {
        val currTime = computeSystemTime().toLong()
        mBleUart!!.sendPacket(
            buildPacket(
                byteArrayOf(
                    0x20,
                    0x01,
                    (currTime and 0x000000FFL).toByte(),
                    (currTime and 0x0000FF00L shr 8).toByte(),
                    (currTime and 0x00FF0000L shr 16).toByte(),
                    (currTime and 0xFF000000L shr 24).toByte()
                )
            )
        )
        mState = State.WAITING_TIME
    }



    private fun getHighestRecordID() {
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x0A, 0x02, 0x06)))
        mState = State.WAITING_HIGHEST_ID
    }

    private fun getOldestRecordIndex() {
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x27, 0x00)))
        mState = State.WAITING_OLDEST_INDEX
    }

    private fun getMeasurementsByIndex(index: Int) {
        mBleUart!!.sendPacket(
            buildPacket(
                byteArrayOf(
                    0x31, 0x02, (index and 0x00FF).toByte(), (index and 0xFF00 shr 8).toByte(),
                    0x00
                )
            )
        )
        mState = State.WAITING_MEASUREMENT
    }

    private fun getMeasurementsById(id: Int) {
        mBleUart!!.sendPacket(
            buildPacket(
                byteArrayOf(
                    0xB3.toByte(),
                    (id and 0x00FF).toByte(),
                    (id and 0xFF00 shr 8).toByte()
                )
            )
        )
        mState = State.WAITING_MEASUREMENT
    }

    private fun handleTimeGet(aSeconds: Long) {
        Log.d(tag, "Glucometer time is: " + Date(1000 * aSeconds).toString())
        Log.d(tag, "System time is: " + Date(System.currentTimeMillis()).toString())
        setTime()
    }

    private fun handleTimeSet() {
        Log.d(tag, "Time has been set!")
        if (!mSynced) {
            getOldestRecordIndex()
        } else {
            getHighestRecordID()
        }
    }

    private fun handleTotalRecordCount(aRecordCount: Short) {
        Log.d(tag, "Total records stored on Glucometer: $aRecordCount")
        mHighestMeasIndex = aRecordCount
        // After getting the number of stored measurements, start from the oldest one!
        getMeasurementsByIndex(aRecordCount - 1)
    }

    private fun handleHighestRecordID(aRecordID: Short) {
        Log.d(tag, "Highest record ID: $aRecordID")
        if (aRecordID > mHighestMeasID) {
            mHighestStoredMeasID = mHighestMeasID
            mHighestMeasID = aRecordID
            Log.d(tag, "There are " + (mHighestMeasID - mHighestStoredMeasID) + " new records!")
            getMeasurementsById(mHighestStoredMeasID + 1)
        } else {
            Log.d(tag, "Measurements are up to date!")
            // Enqueue timer to poll new measurements?
        }
    }

    private fun handleMeasurementByID(aMeasTime: Int, aMeasValue: Short, aMeasError: Short) {
        // Update latest ID
        mHighestStoredMeasID++
        if (aMeasTime != 0) { // If measurement was found..
            Log.d(
                tag, "Measurement - Value: " + aMeasValue +
                        " Time: " + Date(1000 * aMeasTime.toLong()).toString() +
                        " Error: " + aMeasError
            )
            val date = Date(1000 * aMeasTime.toLong())
            mMeasurements.add(
                OnetouchMeasurement(
                    aMeasValue.toFloat(),
                    date,
                    mHighestStoredMeasID.toInt().toString(),
                    aMeasError.toInt()
                )
            )
        } else {
            Log.d(tag, "Measurement with ID: $mHighestStoredMeasID was not found!")
        }
        if (mHighestStoredMeasID < mHighestMeasID) {
            Log.d(tag, "Requesting next measurement, ID: " + (mHighestStoredMeasID + 1))
            getMeasurementsById(mHighestStoredMeasID + 1)
        } else {
            Log.d(tag, "Measurement up to date!")
            // Notify application
            protocolCallbacks!!.onMeasurementsReceived(mMeasurements)
            mMeasurements.clear()
            // Start timer to poll for new measurements??
        }
    }

    private fun handleMeasurementByIndex(
        aMeasIndex: Short,
        aMeasID: Short,
        aMeasTime: Int,
        aMeasValue: Short
    ) {
        Log.d(
            tag, "Measurement " + aMeasIndex + " |" +
                    " Value: " + aMeasValue +
                    " Time: " + Date(1000 * aMeasTime.toLong()).toString() +
                    " ID:" + aMeasID
        )

        // Update latest ID
        mHighestMeasID = aMeasID.toInt().coerceAtLeast(mHighestMeasID.toInt()).toShort()
        mHighestStoredMeasID = mHighestMeasID
        val date = Date(1000 * aMeasTime.toLong())
        mMeasurements.add(
            OnetouchMeasurement(
                aMeasValue.toFloat(),
                date,
                aMeasID.toInt().toString()
            )
        )
        if (aMeasIndex.toInt() == 0) { // The latest measurement
            // Notify application
            protocolCallbacks!!.onMeasurementsReceived(mMeasurements)
            mMeasurements.clear()
            mSynced = true
            getHighestRecordID()
            // Start timer to poll for new measurements??
        } else {
            Log.d(tag, "Requesting next measurement: " + (aMeasIndex - 1))
            getMeasurementsByIndex(aMeasIndex - 1)
        }
    }

    private fun buildPacket(payload: ByteArray): ByteArray {
        val payloadSize = payload.size
        val packetLength = protocolSendingOverhead + payloadSize
        val packet = ByteArray(packetLength)
        packet[0] = 0x02.toByte()
        packet[1] = packetLength.toByte()
        packet[2] = 0x00.toByte()
        packet[3] = 0x04.toByte()
        System.arraycopy(payload, 0, packet, 4, payloadSize)
        packet[4 + payloadSize] = 0x03.toByte()
        appendCRC16(packet, packetLength - 2)
        return packet
    }

    @Throws(Exception::class)
    private fun extractPayload(packet: ByteArray): ByteArray {
        if (checkCRC16(packet)) {
            return if (packet.size == extractLength(packet) && packet.size >= protocolOverHead) {
                packet.copyOfRange(
                    packetPayloadBegin,
                    packetPayloadBegin + packet.size - protocolOverHead
                )
            } else {
                throw Exception(
                    "Bad Length! Received " + packet.size + " bytes but should have been " + extractLength(
                        packet
                    )
                )
            }
        } else {
            val computedCRC = computeCRC(packet, 0, packet.size - 2)
            val receivedCRC = extractCRC(packet)
            throw Exception(
                "Bad CRC! Expected " + Integer.toHexString(computedCRC) +
                        " but got " + Integer.toHexString(receivedCRC) + "."
            )
        }
    }

    private fun computeUnixTime(sysTime: ByteArray): Int {
        return deviceTimeOffset + intFromByteArray(sysTime)
    }

    private fun computeSystemTime(): Int {
        return (System.currentTimeMillis() / 1000).toInt() - deviceTimeOffset
    }

    private fun computeCRC(data: ByteArray?, offset: Int, length: Int): Int {
        if ((data == null) || (offset < 0) || (offset > data.size - 1) || (offset + length > data.size)) {
            return 0
        }
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[offset + i].toInt() shl 8)
            for (j in 0..7) {
                crc = if ((crc and 0x8000) > 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
        }
        return crc and 0xFFFF
    }

    private fun extractCRC(data: ByteArray): Int {
        return (((data[data.size - 1].toInt() shl 8) and 0xFF00) or (data[data.size - 2].toInt() and 0x00FF))
    }

    private fun extractLength(data: ByteArray): Int {
        return (((data[2].toInt() shl 8) and 0xFF00) or (data[1].toInt() and 0x00FF))
    }

    private fun appendCRC16(data: ByteArray, length: Int) {
        val crc = computeCRC(data, 0, length)
        data[length] = ((crc and 0x00FF)).toByte()
        data[length + 1] = ((crc and 0xFF00) shr 8).toByte()
    }

    private fun checkCRC16(data: ByteArray): Boolean {
        val computedCRC = computeCRC(data, 0, data.size - 2)
        val receivedCRC = extractCRC(data)
        return receivedCRC == computedCRC
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    init {
        mState = State.IDLE
        timer = Timer()
        mBleUart = Bleuart(this, aMaxPacketSize)
    }
}