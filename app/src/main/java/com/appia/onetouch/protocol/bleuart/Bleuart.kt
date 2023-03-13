package com.appia.onetouch.protocol.bleuart

import android.util.Log
import com.appia.bioland.BuildConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Bleuart(aCallbacks: BleuartCallbacks?, aMaxPacketSize: Int) {

    /**
     * This function sends a packet of bytes to the device.
     */
    fun sendPacket(aBytes: ByteArray) {
        if (BuildConfig.DEBUG && mState != State.IDLE) {
            throw AssertionError("Was busy to send packet!")
        }
        mState = State.SENDING
        /* Compute the number of packets needed in the transaction. */mNpackets =
            Math.ceil(aBytes.size / mMaxPayloadSize.toDouble()).toInt()
        mTxData = ByteArrayInputStream(aBytes)
        buildAndSendFragment(true)
    }

    /**
     * This function should be called by the upper layer when a bluetooth packet is received
     */
    fun onDataReceived(aBytes: ByteArray) {
        when (mState) {
            State.IDLE -> if (headerIs(aBytes[0], HEADER_FIRST_PACKET)) {
                mNpackets = aBytes[0].toInt() and 0x0F
                Log.d(TAG, "Receiving 1 of $mNpackets")
                mRxData = ByteArrayOutputStream()
                handleDataReceived(aBytes)
            }
            State.SENDING -> if (aBytes.size == 1 && headerIs(aBytes[0], HEADER_ACK_PACKET)) {
                // Acknowledge packet
                val nAck = aBytes[0].toInt() and 0x0F
                if (nAck == mNpackets) {
                    mNpackets--
                    if (mNpackets == 0) {
                        mTxData = null
                        mState = State.IDLE
                        Log.d(TAG, "SENDING -> IDLE.")
                    } else {
                        val nBytesToSend =
                            Math.min(mMaxPayloadSize, BLEUART_HEADER_SIZE + mTxData!!.available())
                        val bytesToSend = ByteArray(nBytesToSend)
                        bytesToSend[0] = (0x40 or (0x0F and mNpackets)).toByte()
                        mTxData!!.read(
                            bytesToSend,
                            BLEUART_HEADER_SIZE,
                            nBytesToSend - BLEUART_HEADER_SIZE
                        )
                        mCallbacks!!.sendData(bytesToSend)
                    }
                } else {
                    Log.e(
                        TAG,
                        "Wrong ACK number!. Expecting $mNpackets but $nAck received."
                    )
                }
            } else {
                Log.e(TAG, "Expecting ACK but received: $aBytes")
            }
            State.RECEIVING -> if (headerIs(aBytes[0], HEADER_FRAG_PACKET)) {
                val remainingPackets = aBytes[0].toInt() and 0x0F
                if (remainingPackets == mNpackets) {
                    handleDataReceived(aBytes)
                } else {
                    Log.e(
                        TAG,
                        "Wrong packet number!. Expecting $mNpackets but $remainingPackets received."
                    )
                }
            } else {
                Log.e(
                    TAG,
                    "Wrong header code!. Expecting " + 0x40 + " but " + (aBytes[0].toInt() and 0xF0) + " received."
                )
            }
            else -> throw IllegalStateException("Unexpected value: $mState")
        }
    }

    private fun buildAndSendFragment(aFirstPacket: Boolean) {
        val nBytesToSend = Math.min(mMaxPayloadSize, BLEUART_HEADER_SIZE + mTxData!!.available())
        val bytesToSend = ByteArray(nBytesToSend)
        bytesToSend[0] = (0x0F and mNpackets).toByte()
        bytesToSend[0] =
            (bytesToSend[0].toInt() or (if (aFirstPacket) 0x00.toByte() else 0x40.toByte()
                .toInt()) as Int).toByte()
        mTxData!!.read(bytesToSend, BLEUART_HEADER_SIZE, nBytesToSend - BLEUART_HEADER_SIZE)
        mCallbacks!!.sendData(bytesToSend)
    }

    private fun handleDataReceived(aBytes: ByteArray) {
        mRxData!!.write(aBytes, 1, aBytes.size - 1)
        val bytesToSend = ByteArray(1)
        bytesToSend[0] = (0x80 or (0x0F and mNpackets)).toByte()
        mCallbacks!!.sendData(bytesToSend)
        mNpackets--
        if (mNpackets > 0) {
            Log.d(TAG, "$mNpackets remaining.")
            mState = State.RECEIVING
        } else {
            Log.d(TAG, mRxData!!.size().toString() + " bytes received")
            mTxData = null
            mState = State.IDLE
            mCallbacks!!.onPacketReceived(mRxData!!.toByteArray())
        }
    }

    /* Check if the header of the packet is the specified one. */
    private fun headerIs(aHeader: Byte, aHeaderType: Byte): Boolean {
        return aHeader.toInt() and 0xF0.toByte().toInt() == aHeaderType.toInt()
    }

    private val HEADER_FIRST_PACKET = 0x00.toByte()
    private val HEADER_FRAG_PACKET = 0x40.toByte()
    private val HEADER_ACK_PACKET = 0x80.toByte()

    private val TAG = "BleuartProtocol"
    private val BLEUART_HEADER_SIZE = 1

    /* Interface with upper layer. */
    private var mCallbacks: BleuartCallbacks? = aCallbacks

    /* Stream of data to read when sending. */
    private var mTxData: ByteArrayInputStream? = null

    /* Stream of data where to write when receiving. */
    private var mRxData: ByteArrayOutputStream? = null

    /* Packet counter for TX/RX */
    private var mNpackets = 0

    /* Maximum amount of bytes sent in one packet */
    private var mMaxPayloadSize = 0

    private enum class State {
        IDLE, SENDING, RECEIVING
    }

    private var mState: State? = null

    init {
        mState = State.IDLE
        mMaxPayloadSize = aMaxPacketSize - BLEUART_HEADER_SIZE
    }
}