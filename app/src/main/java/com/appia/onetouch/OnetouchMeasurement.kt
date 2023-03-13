package com.appia.onetouch

import java.util.*

class OnetouchMeasurement {
    constructor() {}
    constructor(aGlucose: Float, aDate: Date?, aId: String?, aErrorID: Int) {
        mGlucose = aGlucose
        mDate = aDate
        mId = aId
        mErrorID = aErrorID
    }

    constructor(aGlucose: Float, aDate: Date?, aId: String?) {
        mGlucose = aGlucose
        mDate = aDate
        mId = aId
        mErrorID = 0
    }

    /** The glucose concentration  */
    var mGlucose = 0f

    /** The base time of the measurement  */
    var mDate: Date? = null
    var mId: String? = null
    var mErrorID = 0
    override fun toString(): String {
        return mGlucose.toString() + " mmol/L @ " + mDate.toString() + " Error: " + mErrorID
    }
}
/*
public class OnetouchMeasurement {
    public OnetouchMeasurement(){

    }
    public OnetouchMeasurement(float aGlucose, Date aDate, String aId, int aErrorID) {
        mGlucose = aGlucose;
        mDate = aDate;
        mId = aId;
        mErrorID = aErrorID;
    }
    public OnetouchMeasurement(float aGlucose, Date aDate, String aId) {
        mGlucose = aGlucose;
        mDate = aDate;
        mId = aId;
        mErrorID = 0;
    }


    /** The glucose concentration */
    public float mGlucose;

    /** The base time of the measurement */
    public Date mDate;
    public String mId;
    public int mErrorID;

    @NonNull
    @Override
    public String toString() {
        return mGlucose + " mmol/L @ " + mDate.toString() + " Error: " + mErrorID;
    }
}
 */