package sv.cmu.edu.ips.views;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.gson.Gson;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.mit.media.funf.FunfManager;
import edu.mit.media.funf.pipeline.BasicPipeline;
import sv.cmu.edu.ips.R;
import sv.cmu.edu.ips.service.dataCollectors.SensorDataCollector;
import sv.cmu.edu.ips.util.Constants;
import sv.cmu.edu.ips.util.IPSFileWriter;
import sv.cmu.edu.ips.util.LogUtil;
import sv.cmu.edu.ips.util.UserInputManager;

/**
 * An activity representing a list of Items. This activity
 * has different presentations for handset and tablet-size devices. On
 * handsets, the activity presents a list of items, which when touched,
 * lead to a {@link DataCollectDetailActivity} representing
 * item details. On tablets, the activity presents the list of items and
 * item details side-by-side using two vertical panes.
 * <p/>
 * The activity makes heavy use of fragments. The list of items is a
 * {@link DataCollectListFragment} and the item details
 * (if present) is a {@link DataCollectDetailFragment}.
 * <p/>
 * This activity also implements the required
 * {@link DataCollectListFragment.Callbacks} interface
 * to listen for item selections.
 */
public class DataCollectListActivity extends FragmentActivity
        implements DataCollectListFragment.Callbacks {

    /**
     * Whether or not the activity is in two-pane mode, i.e. running on a tablet
     * device.
     */
    private boolean mTwoPane;
    private DataCollectListFragment listFragment;
    private ProgressBar mProgress;
    private int progressStatus = 0;
    private Button startButton;
    private FunfManager funfManager;
    private BasicPipeline pipeline;
    private Handler handler = new Handler();
    private Set<String> listOfProbesWhichFinishedDataCollection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_datacollect_list);

        if (findViewById(R.id.datacollect_detail_container) != null) {
            // The detail container view will be present only in the
            // large-screen layouts (res/values-large and
            // res/values-sw600dp). If this view is present, then the
            // activity should be in two-pane mode.
            mTwoPane = true;

            // In two-pane mode, list items should be given the
            // 'activated' state when touched.
            ((DataCollectListFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.datacollect_list))
                    .setActivateOnItemClick(true);
        }


        listFragment = ((DataCollectListFragment) getSupportFragmentManager()
                .findFragmentById(R.id.datacollect_list));

        mProgress = (ProgressBar) findViewById(R.id.progressBar);

        startButton = (Button) findViewById(R.id.startButton);
        startButton.setText(Constants.START_BUTTON_TEXT);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Button btn = (Button) v;

                if(btn.getText() == Constants.START_BUTTON_TEXT){
                    btn.setText(Constants.COLLECTING_BUTTON_TEXT);
                    listOfProbesWhichFinishedDataCollection = new HashSet<String>();

                    mProgress.incrementProgressBy(10);
                    handler.postDelayed(runnableUpdateProgress, 100);
                    final List<SensorDataCollector> probes = listFragment.getActiveSensorProbes();
                    //IR data and sound data has to be done on after other as they use same hardware
                    //Doing IR first, then will do sound in the collection

                    // Start lengthy operation in a background thread
                    for(int i=1; i<probes.size(); i++){
                        final int finalI = i;
                        Thread collectorThread = new Thread(new Runnable() {
                            public void run() {
                                Gson gson = funfManager.getGson();
                                probes.get(finalI).collectData(getApplicationContext(), gson);
                            }
                        });
                        collectorThread.start();
                    }
                }else if(btn.getText() == Constants.LABEL_BUTTON_TEXT){
                    labelData();
                }
            }
        });

    }

    @Override
    public void onResume(){
        super.onResume();
        checkIfAudioJackIsIn();

        IntentFilter intentFilter = new IntentFilter(Constants.DATA_COLLECTION_FINISHED);
        registerReceiver(dataCollectionFinishedBroadcastReceiver, intentFilter );

        // Bind to the service, to create the connection with FunfManager
        bindService(new Intent(this, FunfManager.class), funfManagerConn, BIND_AUTO_CREATE);
    }

    @Override
    public void onPause(){
        destroyFunf();
        unregisterReceiver(dataCollectionFinishedBroadcastReceiver);
        super.onPause();
    }
    @Override
    protected void onDestroy(){
        destroyFunf();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
        destroyFunf();
    }

    /**
     * Callback method from {@link DataCollectListFragment.Callbacks}
     * indicating that the item with the given ID was selected.
     */
    @Override
    public void onItemSelected(String id) {
        if (mTwoPane) {
            // In two-pane mode, show the detail view in this activity by
            // adding or replacing the detail fragment using a
            // fragment transaction.
            Bundle arguments = new Bundle();
            arguments.putString(DataCollectDetailFragment.ARG_ITEM_ID, id);
            DataCollectDetailFragment fragment = new DataCollectDetailFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.datacollect_detail_container, fragment)
                    .commit();

        } else {
            // In single-pane mode, simply start the detail activity
            // for the selected item ID.
            Intent detailIntent = new Intent(this, DataCollectDetailActivity.class);
            detailIntent.putExtra(DataCollectDetailFragment.ARG_ITEM_ID, id);
            startActivity(detailIntent);
        }
    }

    private void destroyFunf() {
        if(pipeline != null) pipeline.onDestroy();
        if(funfManagerConn != null) unbindService(funfManagerConn);
        funfManager = null;
        funfManagerConn = null;
    }

    private void labelData(){
        UserInputManager uim = new UserInputManager();
        Handler.Callback callback = new Handler.Callback() {
            public boolean handleMessage(Message msg) {
                if(msg == null){
                    reset();
                    startButton.setText(Constants.START_BUTTON_TEXT);
                }else{
                    applyLabel(String.valueOf(msg.obj));
                }
                return true;
            }
        };

        uim.getLabel(this, "Label the sound", callback);
    }

    private void reset(){
        if(mProgress != null) mProgress.setProgress(0);
        if(listOfProbesWhichFinishedDataCollection != null) {
            listOfProbesWhichFinishedDataCollection.clear();
            listOfProbesWhichFinishedDataCollection = null;
        }
    }

    private void applyLabel(String value) {
        String labelString = value + "_"+ System.currentTimeMillis();

        // TODO - need to move it as event handler
        if (labelString != "") {
            IPSFileWriter.renameTempFolder(labelString);
        }

        reset();
        startButton.setText(Constants.START_BUTTON_TEXT);
    }

    private boolean checkIfAudioJackIsIn() {
        AudioManager am = (AudioManager)getSystemService(AUDIO_SERVICE);
        LogUtil.log("am.isWiredHeadsetOn()" + am.isWiredHeadsetOn() + "");
        if(!am.isWiredHeadsetOn()){
            Toast.makeText(this, "IR receiver not found in headset, please reinsert. ", Toast.LENGTH_SHORT);
        }

        return am.isWiredHeadsetOn();
    }

    private Runnable runnableUpdateProgress = new Runnable() {
        @Override
        public void run() {
            if(listOfProbesWhichFinishedDataCollection != null){
                progressStatus = (1 + listOfProbesWhichFinishedDataCollection.size() *100)/(1 +listFragment.getActiveSensorProbes().size());
                mProgress.setProgress(progressStatus);

                if(listOfProbesWhichFinishedDataCollection.size() == (listFragment.getActiveSensorProbes().size() -1)){
                    //now collect IR data
                    // && checkIfAudioJackIsIn()
                    listFragment.getActiveSensorProbes().get(0).collectData(getApplicationContext(), funfManager.getGson());
                }
                else if(listOfProbesWhichFinishedDataCollection.size() == (listFragment.getActiveSensorProbes().size())){
                    startButton.setText(Constants.LABEL_BUTTON_TEXT);
                    labelData();
                }
            }
            Thread.yield();
        }
    };

    private ServiceConnection funfManagerConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            funfManager = ((FunfManager.LocalBinder)service).getManager();
            pipeline = (BasicPipeline) funfManager.getRegisteredPipeline(Constants.PIPELINE_NAME);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            funfManager = null;
        }
    };

    private BroadcastReceiver dataCollectionFinishedBroadcastReceiver = new BroadcastReceiver(){

        @Override
        public void onReceive(Context context, Intent intent) {
            String collectorName = intent.getStringExtra(Constants.SENSOR_TYPE);

            LogUtil.log("Received finish of " + collectorName);
            if(collectorName != null && collectorName != "" && !listOfProbesWhichFinishedDataCollection.contains(collectorName)){
                if(listOfProbesWhichFinishedDataCollection != null){
                    listOfProbesWhichFinishedDataCollection.add(collectorName);
                    handler.post(runnableUpdateProgress);
                }
            }
        }
    };
}