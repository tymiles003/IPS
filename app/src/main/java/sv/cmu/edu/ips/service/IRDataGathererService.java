package sv.cmu.edu.ips.service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;

import sv.cmu.edu.ips.R;
import sv.cmu.edu.ips.data.AudioData;
import sv.cmu.edu.ips.data.SignalData;
import sv.cmu.edu.ips.util.IPSFileWriter;
import sv.cmu.edu.ips.util.LogUtil;
import sv.cmu.edu.ips.util.SignalAnalyzer;

import static android.widget.Toast.LENGTH_SHORT;

public class IRDataGathererService extends Service {

    private NotificationManager mNM;
    private int NOTIFICATION = R.string.local_service_started;
    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();
    private static boolean isRecording = false;
    private Handler handler;
    private static IRDataRecorder dataRecorder;
    private int dataCount;
    private Boolean isListening;
    private String logLabel = "IRDataGathererService";
    private boolean isDataToBeWrittenToFile = false;


    public IRDataGathererService() {
        isRecording = false;
    }

    public class LocalBinder extends Binder {
        public IRDataGathererService getService() {
            return IRDataGathererService.this;
        }
    }

    @Override
    public void onCreate() {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        // Display a notification about us starting.  We put an icon in the status bar.
        showNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        handler = new Handler();
        handler.postDelayed(runnable, 100);

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        // Cancel the persistent notification.
        mNM.cancel(NOTIFICATION);
        stopRecording();
//        timer.cancel();
//        Log.d("IRDataGathererService", "TimerTask stopped! :");

        Toast.makeText(this, R.string.local_service_stopped, LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = getText(R.string.local_service_started);
        Toast.makeText(this, text, LENGTH_SHORT).show();
        Log.d(LogUtil.TAG, String.valueOf(R.string.local_service_started));
    }

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if(isRecording){
                //stop recording
                stopRecording();
                isRecording= false;
                Log.d("IRDataGathererService", "Recording stopped! :");
                //start recording after 3 secs
                handler.postDelayed(this, 1000);
            }else{
                //start recording
                isRecording = true;
                startRecording();
                Log.d("IRDataGathererService", "Recording started! :");
                //stop recording in 200ms
                handler.postDelayed(this, 200);
            }

        }
    };

    private void startRecording() {

        dataCount = 0;
        final Context context = this;
        dataRecorder = new IRDataRecorder() {
            int noOfInvalidBeaconFound =0;
            int frameIndex = 0;
            ArrayList<AudioData> aggregatedData= new ArrayList<AudioData>();

            @Override
            protected void dataArrival(long timestamp, short[] data,
                                       int length, int frameLength) {
                super.dataArrival(timestamp, data, length, frameLength);

                Log.d(logLabel, "data arrived");

                AudioData audioData = new AudioData(timestamp, data);
                aggregatedData.add(audioData);
                Log.d(logLabel, "data length"+ data.length+ " agg size " + aggregatedData.size());
                Log.d(logLabel, Arrays.toString(data));
                if(aggregatedData.size()>10){
                    stopRecording();
                    Log.d(logLabel, "forced stopping collection as index excedded 10, index :"+ aggregatedData.size());
                }
            }

            @Override
            protected void onRecordEnded(){
                super.onRecordEnded();

                Log.d(logLabel, "Recording ended");
                if(isDataToBeWrittenToFile) {
                    IPSFileWriter fileWriter = new IPSFileWriter(String.valueOf(System.currentTimeMillis())+".pcm");
                    fileWriter.appendText(Arrays.toString(super.getAggregatedData()));
                    fileWriter.close();
                }

                try {
                    SignalData sigData = SignalAnalyzer.getSignalInfoStringFromRawSignal(super.getAggregatedData());
                    String beaconId = sigData.getBeaconId();
                    Log.d(logLabel, "Got beacon ID "+ beaconId + " Ampl = " + sigData.getAmplitude());
                    if(!beaconId.isEmpty() && beaconId.length()>5){
                        Intent intent = new Intent("my-event");
                        // add data
                        intent.putExtra("message", beaconId);
                        intent.putExtra("amplitude", sigData.getAmplitude());
                        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                        noOfInvalidBeaconFound = 0;
                    }else{
                        noOfInvalidBeaconFound++;
                    }

                    Log.d(logLabel, "Invalid beacons count " + noOfInvalidBeaconFound);
                }catch (Exception e) {
                    Log.d(logLabel, "exception "+ e.getMessage());
                    IPSFileWriter fileWriter = new IPSFileWriter(String.valueOf("error.log"));
                    fileWriter.appendText(e.getMessage());
                    fileWriter.close();
                }

//                if(noOfInvalidBeaconFound>10){
//                    stopRecording();
//                    Log.d(logLabel,
//                            "forced stopping collection because many invalid beacons found :"+ noOfInvalidBeaconFound);
//                }
            }

        };

        dataRecorder.startRecord();
        isListening = true;
        Log.d(logLabel, "started recording");
    }

    private void stopRecording() {
        isListening = false;
        if(dataRecorder != null) dataRecorder.stopRecord();
        Log.d(logLabel, "stopped recording");
    }
}