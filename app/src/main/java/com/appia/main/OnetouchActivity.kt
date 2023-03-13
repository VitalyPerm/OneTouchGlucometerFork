package com.appia.main

import android.R.drawable
import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.appia.Ble.BleProfileService
import com.appia.Ble.BleProfileServiceReadyActivity
import com.appia.bioland.R
import com.appia.onetouch.OnetouchMeasurement
import com.appia.onetouch.OnetouchService
import com.appia.onetouch.OnetouchService.OnetouchBinder
import java.text.DateFormat
import java.util.*

class OnetouchActivity : BleProfileServiceReadyActivity<OnetouchBinder?>() {
    var mBinder: OnetouchBinder? = null
    private var mBatteryCapacity = 0
    private var mSerialNumber: ByteArray? = null
    private var batteryLevelView: TextView? = null
    private var statusView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var unitView: TextView? = null
    private var mListView: ListView? = null
    private var mMeasArray: MeasurementsArrayAdapter? = null

    override val filterUUID: UUID?
        get() = null

    override fun onCreateView(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        setGUI()
    }

    override fun onInitialize(savedInstanceState: Bundle?) {
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(broadcastReceiver, makeIntentFilter())
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
    }

    private fun setGUI() {

        // Measurements units
        unitView = findViewById<TextView>(R.id.unit)

        // Device battery level
        batteryLevelView = findViewById<TextView>(R.id.battery)

        // Device battery level
        statusView = findViewById<TextView>(R.id.status)

        // Measurement progress bar
        progressBar = findViewById(R.id.progressBar)
        progressBar!!.visibility = View.INVISIBLE

        // Measurements list view
        mListView = findViewById<ListView>(R.id.list_view)

        // Measurement array adapter
        mMeasArray = MeasurementsArrayAdapter(this, R.layout.measurement_item)
        mMeasArray!!.setNotifyOnChange(true)
        mListView!!.adapter = mMeasArray
    }

    override fun setDefaultUI() {
        batteryLevelView!!.setText(R.string.not_available)
    }

    override fun onServiceBound(binder: OnetouchBinder?) {
        // Store binder
        mBinder = binder

        // Update gui
        onMeasurementsReceived()
    }

    override fun onServiceUnbound() {
        mBinder = null
        // TODO: update gui??
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        super.onDeviceDisconnected(device)
        runOnUiThread { batteryLevelView!!.setText(R.string.not_available) }
        statusView!!.background =
            ContextCompat.getDrawable(this, drawable.button_onoff_indicator_off)
    }

    override fun onLinkLossOccurred(device: BluetoothDevice) {
        runOnUiThread { batteryLevelView!!.text = "" }
        statusView!!.background =
            ContextCompat.getDrawable(this, drawable.button_onoff_indicator_off)
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        super.onDeviceConnected(device)
        progressBar!!.progress = 0
        statusView!!.background =
            ContextCompat.getDrawable(this, drawable.button_onoff_indicator_on)
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        super.onDeviceConnected(device)
    }

    override val aboutTextId: Int get() = R.string.gls_about_text
    override val defaultDeviceName: Int get() = R.string.gls_default_name


    fun onMeasurementsReceived() {
        runOnUiThread {
            if (mBinder != null) {
                progressBar!!.visibility = View.INVISIBLE
                val newMeasurements =
                    mBinder!!.measurements
                if (newMeasurements != null && newMeasurements.size > 0) {
                    //Collections.reverse(newMeasurements);
                    for (i in newMeasurements.indices) {
                        mMeasArray!!.insert(newMeasurements[i], 0)
                        Log.d(
                            TAG,
                            "Measurement: " + newMeasurements[i]
                        )
                    }
                }
            }
        }
    }

    fun onInformationReceived() {
        // TODO: Show device information
        runOnUiThread {
            if (mBinder != null) {
                val info = mBinder!!.dInfo
                Log.d(
                    TAG,
                    "Device information receivec: " + info!!.batteryCapacity + "% battery left"
                )
                batteryLevelView!!.text = info.batteryCapacity.toString() + "%"
            }
        }
    }

    @SuppressLint("ObjectAnimatorBinding")
    fun onCountdownReceived(count: Int) {
        runOnUiThread {
            if (mBinder != null) {
                progressBar!!.visibility = View.VISIBLE
                val animator = ObjectAnimator.ofInt(
                    progressBar,
                    "progress",
                    (5 - count) * progressBar!!.max / 5
                )
                animator.duration = 1200
                animator.interpolator = LinearInterpolator()
                if (count == 0) {
                    animator.addListener(object : Animator.AnimatorListener {
                        override fun onAnimationStart(animation: Animator) {}
                        override fun onAnimationEnd(animation: Animator) {
                            progressBar!!.visibility = View.INVISIBLE
                        }

                        override fun onAnimationCancel(animation: Animator) {}
                        override fun onAnimationRepeat(animation: Animator) {}
                    })
                }
                animator.start()
            }
        }
    }
    //	public class ProgressBarAnimation extends Animation{
    //		private ProgressBar progressBar;
    //		private float from;
    //		private float  to;
    //
    //		public ProgressBarAnimation(ProgressBar progressBar, float from, float to) {
    //			super();
    //			this.progressBar = progressBar;
    //			this.from = from;
    //			this.to = to;
    //		}
    //
    //		@Override
    //		protected void applyTransformation(float interpolatedTime, Transformation t) {
    //			super.applyTransformation(interpolatedTime, t);
    //			float value = from + (to - from) * interpolatedTime;
    //			progressBar.setProgress((int) value);
    //		}
    //
    //	}
    /**
     * Receive broadcast messages from the service
     */
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (OnetouchService.BROADCAST_MEASUREMENT == action) {
                Log.d(
                    TAG,
                    "Broadcast measurement received! Binder is: $mBinder"
                )
                onMeasurementsReceived()
            } else if (OnetouchService.BROADCAST_COUNTDOWN == action) {
                val count = intent.getIntExtra(OnetouchService.EXTRA_COUNTDOWN, 0)
                Log.d(TAG, "Countdown $count")
                onCountdownReceived(count)
            } else if (OnetouchService.BROADCAST_INFORMATION == action) {
                Log.d(
                    TAG,
                    "Broadcast information received! Binder is: $mBinder"
                )
                mBatteryCapacity = intent.getIntExtra(OnetouchService.EXTRA_BATTERY_CAPACITY, 0)
                mSerialNumber = intent.getByteArrayExtra(OnetouchService.EXTRA_SERIAL_NUMBER)
                onInformationReceived()
            } else if (OnetouchService.BROADCAST_COMM_FAILED == action) {
                Log.d(
                    TAG,
                    "Broadcast communication failed received! Binder is: $mBinder"
                )
                val msg = intent.getStringExtra(OnetouchService.EXTRA_ERROR_MSG)
                showToast("Error: $msg")
            }
        }
    }
    override val serviceClass: Class<out BleProfileService?> get() = OnetouchService::class.java

    inner class MeasurementsArrayAdapter(private val mContext: Context, aResource: Int) :
        ArrayAdapter<OnetouchMeasurement?>(mContext, aResource) {
        private val mInflater: LayoutInflater = LayoutInflater.from(mContext)

        @SuppressLint("SetTextI18n", "DefaultLocale")
        override fun getView(aPosition: Int, aConvertView: View?, aParent: ViewGroup): View {
            var view = aConvertView
            if (view == null) {
                view = mInflater.inflate(R.layout.measurement_item, aParent, false)
            }
            val measurement = getItem(aPosition) ?: return view!!
            // this may happen during closing the activity
            // Lookup view for data population
            val time = view!!.findViewById<TextView>(R.id.time)
            val value = view.findViewById<TextView>(R.id.value)
            val id = view.findViewById<TextView>(R.id.id)

            // Populate the data into the template view using the data object
            val format = DateFormat.getDateTimeInstance(
                DateFormat.DEFAULT,
                DateFormat.SHORT,
                Locale.getDefault()
            )
            val dateString = format.format(measurement.mDate!!)
            time.text = dateString
            if (measurement.mErrorID == 0) {
                value.text = String.format(Locale.getDefault(), "%.2f", measurement.mGlucose)
                id.text = String.format("ID: %s", measurement.mId)
            } else if (measurement.mErrorID == 1280) {
                value.text = String.format(Locale.getDefault(), "%.2f", measurement.mGlucose)
                id.text = String.format("ID: %s HI", measurement.mId)
            } else {
                value.text = "-"
                id.text =
                    String.format("ID: %s ERROR CODE: %d", measurement.mId, measurement.mErrorID)
            }
            return view
        }
    }

    companion object {
        private const val TAG = "GlucoseActivity"
        private fun makeIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(OnetouchService.BROADCAST_COUNTDOWN)
            intentFilter.addAction(OnetouchService.BROADCAST_MEASUREMENT)
            intentFilter.addAction(OnetouchService.BROADCAST_INFORMATION)
            intentFilter.addAction(OnetouchService.BROADCAST_COMM_FAILED)
            return intentFilter
        }
    }
}
