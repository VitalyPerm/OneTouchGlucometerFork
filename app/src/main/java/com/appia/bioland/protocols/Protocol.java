package com.appia.bioland.protocols;

import com.appia.bioland.BiolandInfo;
import com.appia.bioland.BiolandMeasurement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import android.util.Log;

public abstract class Protocol {

    private final static String TAG = "Protocol";
    // Abstracts serial communication
    private ProtocolCallbacks protocolCallbacks;

    public List<ResultPacket> resultPackets = new ArrayList<>();

    // Contains the current protocol version
    protected Version version;

    // All protocols have the following states.
    protected enum State {DISCONNECTED, WAITING_INFO_PACKET, WAITING_MEASUREMENT,WAITING_RESULT_OR_END_PACKET};
    private State state;
    private int retries_on_current_packet;
    final static public int MAX_RETRIES = 5;
    final static public int RETRY_DELAY_MS = 1000;
    final static public int DELAY_AFTER_RECEIVED = 200;
    private static int CHECKSUM_OFFSET = 2;

    private static Timer timer;
    private static Semaphore  mutex = new Semaphore(1);

    // This class abstracts the protocol from the User
    public Protocol(ProtocolCallbacks aCallbacks){
        protocolCallbacks = aCallbacks;
        state = State.DISCONNECTED;
        timer = new Timer();
    }


    public void connect(){
        if(state == State.DISCONNECTED){
            state = State.WAITING_INFO_PACKET;
            retries_on_current_packet = 0;
            timer = new Timer();
            sendPacket();
        }
    }

    // This function starts the communication, must be used if the protocol is <3.1
    public boolean requestMeasurements(){

        try {
            mutex.acquire();
        }catch (java.lang.InterruptedException a){
            return false;
        }

        AppPacket appDataPacket = build_get_info_packet(Calendar.getInstance());
        protocolCallbacks.sendData(appDataPacket.to_bytes());
        state = State.WAITING_RESULT_OR_END_PACKET;
        retries_on_current_packet = 0;
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                sendPacket();
            }
        }, RETRY_DELAY_MS);
        mutex.release(1);
        return true;
    }

    /**
     * Cancel any ongoing communication.
     */
    public void disconnect() {
        // Cancel any pending schedules
        timer.cancel();
        state = State.DISCONNECTED;
    }


    // This function should be called when a bluetooth packet is received
    public void onDataReceived(byte[] bytes){
        // Aquire mutex not to step on send function
        try {
            mutex.acquire();
        }catch (java.lang.InterruptedException a){
            return;
        }
        // Cancel any pending schedules
        timer.cancel();
        timer = new Timer();
        switch (state){
            case DISCONNECTED:

                break;
            // If waiting for an Information packet
            case WAITING_INFO_PACKET:
                try{
                    InfoPacket infoPacket = build_info_packet(bytes);

                    BiolandInfo info = new BiolandInfo();
//                    if( version.equals(new Version("1.0"))){
//                        ProtocolV1.InfoPacketV1 v1_info_packet = (ProtocolV1.InfoPacketV1) infoPacket;
//                        info.productionDate = new GregorianCalendar();
//                        info.productionDate.set(v1_info_packet.productionYear,v1_info_packet.productionMonth,0);
//                    } else if (version.equals(new Version("2.0"))){
//                        ProtocolV2.InfoPacketV2 v2_info_packet = (ProtocolV2.InfoPacketV2) infoPacket;
//                        info.batteryCapacity = v2_info_packet.batteryCapacity;
//                        info.serialNumber = v2_info_packet.rollingCode;
//                    } else if (version.equals(new Version("3.1"))) {
//                        ProtocolV31.InfoPacketV31 v31_info_packet = (ProtocolV31.InfoPacketV31) infoPacket;
//                        info.batteryCapacity = v31_info_packet.batteryCapacity;
//                        info.serialNumber = v31_info_packet.rollingCode;
//                    } else if (version.equals(new Version("3.1"))){
                        ProtocolV32.InfoPacketV32 v32_info_packet = (ProtocolV32.InfoPacketV32) infoPacket;
                        info.batteryCapacity = v32_info_packet.batteryCapacity;
                        info.serialNumber = v32_info_packet.seriesNumber;
                    //}

                    // Notify application
                    protocolCallbacks.onDeviceInfoReceived(info);

                    state = State.WAITING_MEASUREMENT;

                }catch (IllegalLengthException | IllegalContentException e){
                    try {
                        //Try to parse as a result packet
                        ResultPacket resultPacket = build_result_packet(bytes);
                        resultPackets.add(resultPacket);
                        state = State.WAITING_RESULT_OR_END_PACKET;
                        sendPacket(); // Request new measurement

                    }catch (IllegalLengthException | IllegalContentException error) {

                        Log.e(TAG, "Wrong packet received waiting info packet!");

                    }

                    //If an error occurred load it to communication
//                    state = State.DONE;
//                    protocolCallbacks.onProtocolError(e.toString());

                }
                break;
            case WAITING_MEASUREMENT:
                try{
                    // Try to parse as a timing packet
                    DevicePacket timing_packet = build_timing_packet(bytes);
                    byte[] variables = timing_packet.getVariablesInByteArray();
                    // Get timing from packet, it's in position 4
                    protocolCallbacks.onCountdownReceived(variables[4]);
                    if(variables[4] == 0){
                        state = State.WAITING_RESULT_OR_END_PACKET;
                    }
                }catch (IllegalLengthException | IllegalContentException e) {
                    Log.e(TAG,"Wrong packet received waiting timing packet!");
                }
                break;
            case WAITING_RESULT_OR_END_PACKET:
                try{
                    //Try to parse as a result packet
                    ResultPacket resultPacket = build_result_packet(bytes);
                    resultPackets.add(resultPacket);
                    sendPacket(); // Request new measurement
                }catch (IllegalLengthException | IllegalContentException e){
                    //If controlled exception occurred
                    try {
                        //Try to parse as End Packet
                        build_end_packet(bytes);
                        // Notify the application of the received measurements
                        if(resultPackets.size()>0) {
                            ArrayList<BiolandMeasurement> arr = new ArrayList<>();
                            for (int i = 0; i < resultPackets.size(); i++) {

                                ResultPacket p = resultPackets.get(i);
                                arr.add(new BiolandMeasurement(p.getGlucose()/(float)18,
                                        (2000 + (p.year & 0xff)),
                                        p.month & 0xff,
                                        p.day & 0xff,
                                        p.hour & 0xff,
                                        p.min & 0xff,
                                        Arrays.toString(p.getVariablesInByteArray())));
                            }
                            protocolCallbacks.onMeasurementsReceived(arr);
                            resultPackets.clear();
                        }
                        // Set state as done
                        state = State.WAITING_MEASUREMENT;

                    } catch (IllegalLengthException | IllegalContentException k){
                        Log.e(TAG,"Wrong packet received waiting result or end packet!");
                    }
                }
                break;
        }

        retries_on_current_packet=0;
        mutex.release(1);
    }

    // This function sends the packet
    public void sendPacket(){

        // If i haven't retried the max number of tries
        if (retries_on_current_packet<MAX_RETRIES){

            retries_on_current_packet++;

            switch (state){
                // Request information packet
                case WAITING_INFO_PACKET:

                    // Build information packet with current date
                    AppPacket appInfoPacket = build_get_info_packet(Calendar.getInstance());
                    protocolCallbacks.sendData(appInfoPacket.to_bytes());

                    // Schedule next packet in RETRY_DELAY milliseconds
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            sendPacket();
                        }
                    }, RETRY_DELAY_MS);
                    break;

                // Request measurement packet
                case WAITING_RESULT_OR_END_PACKET:
                    // Create packet with current date
                    AppPacket appDataPacket = build_get_meas_packet(Calendar.getInstance());
                    protocolCallbacks.sendData(appDataPacket.to_bytes());
                    // Schedule next packet in RETRY_DELAY milliseconds
//                    timer.schedule(new TimerTask() {
//                        @Override
//                        public void run() {
//                            sendPacket();
//                        }
//                    }, RETRY_DELAY_MS);
                    break;


            }

        } else {
            // If the max number of retries was reached, stop and mark communication as error.
            retries_on_current_packet = 0;
            state = State.DISCONNECTED;
            protocolCallbacks.onProtocolError("Max retries reached on current state");
        }
    }

    // Define all class of packets in protocols, abstracting the version of the protocol
    static public class ProtocolPacket{
        byte startCode;
        byte packetLength;
        byte packetCategory;
        byte[] checksum;

        protected void calculateChecksum(int length){
            byte[] bytes = getVariablesInByteArray();
            int sum = CHECKSUM_OFFSET;
            for (int i=0; i< bytes.length;i++){
                sum+= (int)(bytes[i]&0xff);
            }
            checksum = new byte[length];
            for (int i=0; i<length; i++){
                checksum[i] = (byte) ((sum>>(8*i))&0xff);
            }
        }
        protected byte[] getVariablesInByteArray(){
            byte[] bytes = new byte[3];
            bytes[0] = startCode;
            bytes[1] = packetLength;
            bytes[2] = packetCategory;
            return bytes;
        }



    }

    static public class DevicePacket extends ProtocolPacket{
        public DevicePacket(byte[] raw_packet)  throws IllegalContentException, IllegalLengthException {
            if(raw_packet.length<3)
                throw new IllegalLengthException("Packet length must be bigger than 3");
            startCode = raw_packet[0];
            packetLength = raw_packet[1];
            packetCategory = raw_packet[2];
        }
    }

    static public class InfoPacket extends DevicePacket{
        byte versionCode;
        byte clientCode;

        @Override
        protected byte[] getVariablesInByteArray(){
            byte[] parentBytes = super.getVariablesInByteArray();
            byte[] bytes = new byte[parentBytes.length + 2];
            for(int i=0;i<parentBytes.length;i++){
                bytes[i] = parentBytes[i];
            }
            bytes[parentBytes.length+0] = versionCode;
            bytes[parentBytes.length+1] = clientCode;
            return bytes;
        }

        public InfoPacket(byte[] raw) throws IllegalLengthException, IllegalContentException {
            super(raw);
            versionCode = raw[3];
            clientCode = raw[4];
        }
    }

    static public class ResultPacket extends DevicePacket{
        byte year;
        byte month;
        byte day;
        byte hour;
        byte min;
        byte retain;
        byte[] glucose;

        @Override
        protected byte[] getVariablesInByteArray(){
            byte[] parentBytes = super.getVariablesInByteArray();
            byte[] bytes = new byte[parentBytes.length + 9];
            for(int i=0;i<parentBytes.length;i++){
                bytes[i] = parentBytes[i];
            }
            bytes[parentBytes.length+0] = year;
            bytes[parentBytes.length+1] = month;
            bytes[parentBytes.length+2] = day;
            bytes[parentBytes.length+3] = hour;
            bytes[parentBytes.length+5] = min;
            bytes[parentBytes.length+6] = retain;
            bytes[parentBytes.length+7] = glucose[0];
            bytes[parentBytes.length+8] = glucose[1];
            return bytes;
        }

        //Returns glucose in mg/dL
        public int getGlucose(){
            return (int)((glucose[0]&0xff)<<0)+(int)((glucose[1]&0xff)<<8);
        }

        public ResultPacket(byte[] raw) throws IllegalLengthException, IllegalContentException {
            super(raw);
            if (startCode != (byte)0x55)
                throw new IllegalContentException("StartCode must be 0x55");
            if (packetCategory != (byte)0x03)
                throw new IllegalContentException("PacketCategory must be 0x03");
            year = raw[3];
            month = raw[4];
            day = raw[5];
            hour = raw[6];
            min = raw[7];
            retain = raw[8];
            glucose = new byte[2];
            glucose[0] = raw[9];
            glucose[1] = raw[10];
        }
    }

    static public class AppPacket extends ProtocolPacket{
        byte year;
        byte month;
        byte day;
        byte hour;
        byte min;

        @Override
        protected byte[] getVariablesInByteArray(){
            byte[] parentBytes = super.getVariablesInByteArray();
            byte[] bytes = new byte[parentBytes.length + 5];
            for(int i=0;i<parentBytes.length;i++){
                bytes[i] = parentBytes[i];
            }
            bytes[parentBytes.length+0] = year;
            bytes[parentBytes.length+1] = month;
            bytes[parentBytes.length+2] = day;
            bytes[parentBytes.length+3] = hour;
            bytes[parentBytes.length+4] = min;
            return bytes;
        }

        public byte[] to_bytes(){
            byte[] variables = getVariablesInByteArray();
            byte[] bytes = new byte[variables.length+checksum.length];
            for (int i=0; i< variables.length;i++){
                bytes[i] = variables[i];
            }
            for (int i=0; i< checksum.length;i++){
                bytes[variables.length+i] = checksum[i];
            }
            return bytes;
        }


        public AppPacket(Calendar calendar){
            super();
            year = (byte) (calendar.get(Calendar.YEAR)-2000);
            month = (byte) calendar.get(Calendar.MONTH);
            day = (byte) calendar.get(Calendar.DAY_OF_MONTH);
            hour = (byte) calendar.get(Calendar.HOUR_OF_DAY);
            min = (byte) calendar.get(Calendar.MINUTE);
        }
    }

    // Defines builders for different packets allowing different protocols to override them
    protected AppPacket build_get_info_packet(Calendar calendar){
        return new AppPacket(calendar);
    }

    protected AppPacket build_get_meas_packet(Calendar calendar){
        return new AppPacket(calendar);
    }

    protected byte[] build_handshake_packet(){
        return new byte[0];
    }

    protected DevicePacket build_timing_packet(byte[] raw) throws IllegalLengthException, IllegalContentException {
        throw  new IllegalContentException("Protocol"+version+" does not support timing packet");
    }

    protected InfoPacket build_info_packet(byte[] raw) throws IllegalLengthException, IllegalContentException {
        return new InfoPacket(raw);
    }

    protected ResultPacket build_result_packet(byte[] raw) throws IllegalLengthException, IllegalContentException {
        return new ResultPacket(raw);
    }

    protected DevicePacket build_end_packet(byte[] raw) throws IllegalLengthException, IllegalContentException {
        return new DevicePacket(raw);
    }

    // Define of own exceptions used for error checking
    static public class IllegalLengthException extends Exception{
        String error;
        public String toString() {
            return "IllegalLength[" + error + "]";
        }
        IllegalLengthException(String s){
            error = s;
        }
    }
    static public class IllegalContentException extends Exception{
        String error;

        public String toString() {
            return "IllegalContent[" + error + "]";
        }
        IllegalContentException(String s){
            error = s;
        }
    }
}
