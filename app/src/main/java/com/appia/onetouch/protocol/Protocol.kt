package com.appia.onetouch.protocol

import android.util.Log
import com.appia.onetouch.OnetouchMeasurement
import com.appia.onetouch.protocol.bleuart.Bleuart
import com.appia.onetouch.protocol.bleuart.BleuartCallbacks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class Protocol(aCallbacks: ProtocolCallbacks?, aMaxPacketSize: Int) : BleuartCallbacks {
    private val TAG = "OneTouchProtocol"

    // Abstracts serial communication
    private var protocolCallbacks: ProtocolCallbacks? = aCallbacks

    private val PACKET_INITIAL_BYTE = 1 // Always 0x02

    private val PACKET_LENGTH_BYTES = 2 // 16 bit packet length (little endian)

    private val PACKET_PAYLOAD_BEGIN_BYTE_A = 1 // Always 0x04 before payload

    private val PACKET_PAYLOAD_BEGIN_BYTE_B = 1 // Always 0x06 before payload when receiving

    private val PACKET_PAYLOAD_END_BYTE = 1 // Always 0x03 after payload

    private val PACKET_CRC_BYTES = 2 // 16 bit checksum (little endian)


    private val PACKET_PAYLOAD_BEGIN = PACKET_INITIAL_BYTE +
            PACKET_LENGTH_BYTES +
            PACKET_PAYLOAD_BEGIN_BYTE_A +
            PACKET_PAYLOAD_BEGIN_BYTE_B

    private val PROTOCOL_OVERHEAD = PACKET_INITIAL_BYTE +
            PACKET_LENGTH_BYTES +
            PACKET_PAYLOAD_BEGIN_BYTE_A +
            PACKET_PAYLOAD_BEGIN_BYTE_B +
            PACKET_PAYLOAD_END_BYTE +
            PACKET_CRC_BYTES

    private val PROTOCOL_SENDING_OVERHEAD = PACKET_INITIAL_BYTE +
            PACKET_LENGTH_BYTES +
            PACKET_PAYLOAD_BEGIN_BYTE_A +
            PACKET_PAYLOAD_END_BYTE +
            PACKET_CRC_BYTES

    private val DEVICE_TIME_OFFSET = 946684799 // Year 2000 UNIX time


    private var mHighestMeasIndex: Short = 0
    private var mHighestMeasID: Short = 0
    private var mHighestStoredMeasID: Short = 0
    private var mSynced = false

    var mMeasurements = ArrayList<OnetouchMeasurement?>()

    enum class State {
        IDLE, WAITING_TIME, WAITING_HIGHEST_ID, WAITING_OLDEST_INDEX, WAITING_MEASUREMENT, WAITING_LOW_LIMIT_SET, WAITING_LOW_LIMIT_GET, WAITING_HIGH_LIMIT_SET, WAITING_HIGH_LIMIT_GET
    }

    private var mBleUart: Bleuart? = null
    private var mState: State? = null
    private var timer: Timer? = null


    fun getStoredMeasurements() {
        getOldestRecordIndex()
    }

    // packing an array of 4 bytes to an int, little endian, clean code
    fun intFromByteArray(bytes: ByteArray?): Int {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun shortFromByteArray(bytes: ByteArray?): Short {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short
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
            Log.d(TAG, "Packet received: " + bytesToHex(payload))
            when (mState) {
                State.WAITING_TIME -> if (payload.size == 4) { // Time get response
                    handleTimeGet(computeUnixTime(payload).toLong())
                } else if (payload.size == 0) { // Time set response (empty)
                    handleTimeSet()
                } else {
                    Log.e(TAG, "Unexpected payload waiting for time request!")
                }
                State.WAITING_HIGHEST_ID -> if (payload.size == 4) {
                    val highestID = intFromByteArray(payload)
                    handleHighestRecordID(highestID.toShort())
                } else {
                    Log.e(TAG, "Unexpected payload waiting for highest record ID!")
                }
                State.WAITING_OLDEST_INDEX -> if (payload.size == 2) {
                    val recordCount = shortFromByteArray(payload)
                    handleTotalRecordCount(recordCount)
                } else {
                    Log.e(TAG, "Unexpected payload waiting for total record request!")
                }
                State.WAITING_MEASUREMENT -> if (payload.size == 11) {
                    val measTime = computeUnixTime(Arrays.copyOfRange(payload, 0, 0 + 4))
                    val measValue = shortFromByteArray(Arrays.copyOfRange(payload, 4, 4 + 2))
                    val measError = shortFromByteArray(Arrays.copyOfRange(payload, 9, 9 + 2))
                    handleMeasurementByID(measTime, measValue, measError)
                } else if (payload.size == 0) {
                    // Measurement was not found! Indicate with aMeasTime=0
                    handleMeasurementByID(0, 0.toShort(), 0.toShort())
                } else if (payload.size == 16) {
                    val measIndex = shortFromByteArray(Arrays.copyOfRange(payload, 0, 0 + 2))
                    val measID = shortFromByteArray(Arrays.copyOfRange(payload, 3, 3 + 2))
                    val measTime = computeUnixTime(Arrays.copyOfRange(payload, 5, 5 + 4))
                    val measValue = shortFromByteArray(Arrays.copyOfRange(payload, 9, 9 + 2))
                    val measUnknownValue =
                        shortFromByteArray(Arrays.copyOfRange(payload, 13, 13 + 2))
                    handleMeasurementByIndex(
                        measIndex,
                        measID,
                        measTime,
                        measValue,
                        measUnknownValue
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
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x20, 0x02))!!)
        mState = State.WAITING_TIME
    }

    fun setTime() {
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
            )!!
        )
        mState = State.WAITING_TIME
    }

    fun getHighLimit() {
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x0A, 0x02, 0x0A))!!)
        mState = State.WAITING_HIGH_LIMIT_GET
    }

    fun setHighLimit(high: Short) {
        mBleUart!!.sendPacket(
            buildPacket(
                byteArrayOf(
                    0x0A,
                    0x01,
                    0x0A,
                    (high.toInt() and 0x00FF).toByte(),
                    (high.toInt() and 0xFF00 shr 8).toByte(),
                    0x00,
                    0x00
                )
            )!!
        )
        mState = State.WAITING_HIGH_LIMIT_SET
    }

    fun getLowLimit() {
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x0A, 0x02, 0x09))!!)
        mState = State.WAITING_LOW_LIMIT_GET
    }

    fun setLowLimit(low: Short) {
        mBleUart!!.sendPacket(
            buildPacket(
                byteArrayOf(
                    0x0A,
                    0x01,
                    0x09,
                    (low.toInt() and 0x00FF).toByte(),
                    (low.toInt() and 0xFF00 shr 8).toByte(),
                    0x00,
                    0x00
                )
            )!!
        )
        mState = State.WAITING_LOW_LIMIT_SET
    }

    fun getHighestRecordID() {
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x0A, 0x02, 0x06))!!)
        mState = State.WAITING_HIGHEST_ID
    }

    fun getOldestRecordIndex() {
        mBleUart!!.sendPacket(buildPacket(byteArrayOf(0x27, 0x00))!!)
        mState = State.WAITING_OLDEST_INDEX
    }

    fun getMeasurementsByIndex(index: Int) {
        mBleUart!!.sendPacket(
            buildPacket(
                byteArrayOf(
                    0x31, 0x02, (index and 0x00FF).toByte(), (index and 0xFF00 shr 8).toByte(),
                    0x00
                )
            )!!
        )
        mState = State.WAITING_MEASUREMENT
    }

    fun getMeasurementsById(id: Int) {
        mBleUart!!.sendPacket(
            buildPacket(
                byteArrayOf(
                    0xB3.toByte(),
                    (id and 0x00FF).toByte(),
                    (id and 0xFF00 shr 8).toByte()
                )
            )!!
        )
        mState = State.WAITING_MEASUREMENT
    }

    private fun handleTimeGet(aSeconds: Long) {
        Log.d(TAG, "Glucometer time is: " + Date(1000 * aSeconds).toString())
        Log.d(TAG, "System time is: " + Date(System.currentTimeMillis()).toString())
        setTime()
    }

    private fun handleTimeSet() {
        Log.d(TAG, "Time has been set!")
        if (!mSynced) {
            getOldestRecordIndex()
        } else {
            getHighestRecordID()
        }
    }

    private fun handleTotalRecordCount(aRecordCount: Short) {
        Log.d(TAG, "Total records stored on Glucometer: $aRecordCount")
        mHighestMeasIndex = aRecordCount
        // After getting the number of stored measurements, start from the oldest one!
        getMeasurementsByIndex(aRecordCount - 1)
    }

    private fun handleHighestRecordID(aRecordID: Short) {
        Log.d(TAG, "Highest record ID: $aRecordID")
        if (aRecordID > mHighestMeasID) {
            mHighestStoredMeasID = mHighestMeasID
            mHighestMeasID = aRecordID
            Log.d(TAG, "There are " + (mHighestMeasID - mHighestStoredMeasID) + " new records!")
            getMeasurementsById(mHighestStoredMeasID + 1)
        } else {
            Log.d(TAG, "Measurements are up to date!")
            // Enqueue timer to poll new measurements?
        }
    }

    private fun handleMeasurementByID(aMeasTime: Int, aMeasValue: Short, aMeasError: Short) {
        // Update latest ID
        mHighestStoredMeasID++
        if (aMeasTime != 0) { // If measurement was found..
            Log.d(
                TAG, "Measurement - Value: " + aMeasValue +
                        " Time: " + Date(1000 * aMeasTime.toLong()).toString() +
                        " Error: " + aMeasError
            )
            val date = Date(1000 * aMeasTime.toLong())
            mMeasurements.add(
                OnetouchMeasurement(
                    aMeasValue.toFloat(),
                    date,
                    Integer.toString(mHighestStoredMeasID.toInt()),
                    aMeasError.toInt()
                )
            )
        } else {
            Log.d(TAG, "Measurement with ID: $mHighestStoredMeasID was not found!")
        }
        if (mHighestStoredMeasID < mHighestMeasID) {
            Log.d(TAG, "Requesting next measurement, ID: " + (mHighestStoredMeasID + 1))
            getMeasurementsById(mHighestStoredMeasID + 1)
        } else {
            Log.d(TAG, "Measurement up to date!")
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
        aMeasValue: Short,
        aMeasUnknownValue: Short
    ) {
        Log.d(
            TAG, "Measurement " + aMeasIndex + " |" +
                    " Value: " + aMeasValue +
                    " Time: " + Date(1000 * aMeasTime.toLong()).toString() +
                    " ID:" + aMeasID
        )

        // Update latest ID
        mHighestMeasID = Math.max(aMeasID.toInt(), mHighestMeasID.toInt()).toShort()
        mHighestStoredMeasID = mHighestMeasID
        val date = Date(1000 * aMeasTime.toLong())
        mMeasurements.add(
            OnetouchMeasurement(
                aMeasValue.toFloat(),
                date,
                Integer.toString(aMeasID.toInt())
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
            Log.d(TAG, "Requesting next measurement: " + (aMeasIndex - 1))
            getMeasurementsByIndex(aMeasIndex - 1)
        }
    }

    private fun buildPacket(payload: ByteArray): ByteArray? {
        val N = payload.size
        val packetLength = PROTOCOL_SENDING_OVERHEAD + N
        val packet = ByteArray(packetLength)
        packet[0] = 0x02.toByte()
        packet[1] = packetLength.toByte()
        packet[2] = 0x00.toByte()
        packet[3] = 0x04.toByte()
        System.arraycopy(payload, 0, packet, 4, N)
        packet[4 + N] = 0x03.toByte()
        appendCRC16(packet, packetLength - 2)
        return packet
    }

    @Throws(Exception::class)
    private fun extractPayload(packet: ByteArray): ByteArray {
        if (checkCRC16(packet)) {
            return if (packet.size == extractLength(packet) && packet.size >= PROTOCOL_OVERHEAD) {
                Arrays.copyOfRange(
                    packet,
                    PACKET_PAYLOAD_BEGIN,
                    PACKET_PAYLOAD_BEGIN + packet.size - PROTOCOL_OVERHEAD
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
        return DEVICE_TIME_OFFSET + intFromByteArray(sysTime)
    }

    private fun computeSystemTime(): Int {
        return (System.currentTimeMillis() / 1000).toInt() - DEVICE_TIME_OFFSET
    }

    fun computeCRC(data: ByteArray?, offset: Int, length: Int): Int {
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

    fun appendCRC16(data: ByteArray, length: Int) {
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