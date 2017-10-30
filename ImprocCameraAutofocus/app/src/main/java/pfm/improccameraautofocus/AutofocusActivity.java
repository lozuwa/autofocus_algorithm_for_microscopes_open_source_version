package pfm.improccameraautofocus;

/**
 * Author: Rodrigo Loza
 * Company: pfm Medical Bolivia
 * Description: This script is designed to work as a service requested from another app. The master is the server
 * and should be able to call the other app when the service is completed.
 * */

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

import net.igenius.mqttservice.MQTTServiceCommand;
import net.igenius.mqttservice.MQTTServiceReceiver;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.imgproc.Imgproc;

import java.io.UnsupportedEncodingException;

public class AutofocusActivity extends Activity implements CameraBridgeViewBase.CvCameraViewListener2 {

    /** Constants */
    private static final String TAG_O = "Opencv::Activity";
    private static final String TAG_M = "MQTT::Activity";

    /** Init camera bridge (remember opencv uses camera1 api) */
    private CameraBridgeViewBase mOpenCvCameraView;

    /** Tensor containers (avoid calling them on the method onFrame, otherwise processing becomes really slow )*/
    private Mat mRgba;
    private Mat aux;
    private Mat mGray;

    /** Variables */
    public Double variance;
    public Boolean broker_bool;

    /** Variables for autofocus */
    public Boolean getVariance;
    public int counterAutofocus = 0;
    public Double accumulate = 0.0;

    /** Load the opencv module (automatic request to playstore if not installed) */
    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG_O, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    /** Info */
    public AutofocusActivity() {
        Log.i(TAG_O, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /** Display some info */
        Log.i(TAG_O, "called onCreate");

        /** Fix orientation to portrait and keep screen on */
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        /** Set content to the xml activity_camera_and_controller */
        setContentView(R.layout.activity_autofocus);

        /** Permissions */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
            }
        }

        /** Initialize variables */
        getVariance = false;
        broker_bool = false;

        /** Open the bridge with the camera interface and configure params
         * setVisibility -> True
         * callback for onFrame
         * setMaxFrameSize -> (640,480) Image processing is heavy to compute. So the smaller image, the better.
         * */
        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.tutorial1_activity_java_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        //mOpenCvCameraView.setMaxFrameSize(1920, 1280);
    }

    /************************************Class callbacks********************************************/
    @Override
    public void onStart(){
        super.onStart();
    }

    @Override
    public void onPause(){
        super.onPause();
        /** MQTT */
        receiver.unregister(this);
        /** Turn off camera when screen goes off, or change application */
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume(){
        super.onResume();
        /** MQTT */
        receiver.register(this);
        /**Resume opencv libraries when coming back to the app*/
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG_O, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d(TAG_O, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public void onStop(){
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        /**Destroy camera when leaving the app*/
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onBackPressed() {
        /** Back operation is not allowed */
    }
    /********************************************************************************************/

    /***********************************************OpenCV***********************************************************/
    public void onCameraViewStarted(int width, int height) {
        /** Authenticate when camera has openned */
        publishMessage(Initializer.AUTOFOCUS_APP_TOPIC, Initializer.AUTHENTICATE_AUTOFOCUS_ACTIVITY_MESSAGE);
        /** Constructor of the camera view, initialize tensor containers */
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        aux = new Mat(height, width, CvType.CV_8UC4);
        mGray = new Mat(height, width, CvType.CV_8UC1);
    }

    public void onCameraViewStopped() {
        /** Release tensor containers from memory */
        mRgba.release();
        mGray.release();
        aux.release();
    }

    /** Callback for camera */
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        /** Get input frame and convert to grayscale */
        aux = inputFrame.rgba();
        mGray = inputFrame.gray();
        /** Apply high pass filter to obtain high frequencies */
        Imgproc.Laplacian(mGray, mGray, CvType.CV_8U, 3, 1, 0);
        /** Convert image to 8 bit depth and one channel */
        mGray.convertTo(mRgba, CvType.CV_8U);
        /**If the start autofocus sequence is activated, process the variance of the laplace filtered image
         * The variance coefficient tells us whether the image is in focus or not.
         * */
        if (getVariance) {
            /** Laplacian variance feature */
            MatOfDouble mu = new MatOfDouble();
            MatOfDouble std = new MatOfDouble();
            Core.meanStdDev(mRgba, mu, std);
            variance = Math.pow(mu.get(0,0)[0], 2);
            Log.i(TAG_M, String.valueOf(variance));
            if (variance < 2.0) {
                Log.i(TAG_M, "Not a good value: " + String.valueOf(variance));
            }
            else {
                if (counterAutofocus == 3){
                    publishMessage(Initializer.AUTOFOCUS_APP_TOPIC, "send;variance;None;None;" + String.valueOf(accumulate/3.0));
                    getVariance = false;
                }
                else{
                    accumulate += variance;
                    counterAutofocus++;
                }
            }
        }
        else {
            //nothing()
        }
        return aux;
    }
    /**********************************************************************************************************/

    /***********************************************MQTT***********************************************************/
    /**
     * MQTT Receiver
     * */
    private MQTTServiceReceiver receiver = new MQTTServiceReceiver() {

        private static final String TAG = "Receiver";

        @Override
        public void onSubscriptionSuccessful(Context context, String requestId, String topic) {
            /** Info */
            Log.i(TAG, "Subscribed to " + topic);
            /** Authenticate connection */
            //publishMessage(CAMERA_APP_TOPIC, "oath;autofocusApp;AutofocusActivity");
        }

        @Override
        public void onSubscriptionError(Context context, String requestId, String topic, Exception exception) {
            Log.i(TAG, "Can't subscribe to " + topic, exception);
        }

        @Override
        public void onPublishSuccessful(Context context, String requestId, String topic) {
            Log.i(TAG, "Successfully published on topic: " + topic);
        }

        @Override
        public void onMessageArrived(Context context, String topic, byte[] payload) {
            /** Info */
            //showToast(topic);
            Log.i(TAG, "New message on " + topic + ":  " + new String(payload));
            /** Incoming messages */
            final String message_ = new String(payload);
            String[] messages = message_.split(";");
            String command = messages[0];
            String target = messages[1];
            String action = messages[2];
            String specific = messages[3];
            String message = messages[4];
            //command;target;action;specific;message
            /** Actions based on the income messages */
            if (command.equals("service") && target.equals("autofocus") && action.equals("completed")){
                if (specific.equals("ManualControllerAndCamera")){
                    /** Start activity ManualController from cameraApp */
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setComponent(new ComponentName("com.example.android.camera2basic", "com.example.android.camera2basic.ControllerAndCamera"));
                    startActivity(intent);
                } else if (specific.equals("CameraActivity")){
                    /** Start activity CameraActivity from cameraApp */
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setComponent(new ComponentName("com.example.android.camera2basic", "com.example.android.camera2basic.RecoverAutomaticService"));
                    startActivity(intent);
                } else{
                    //nothing
                }
            } else if (command.equals("get") && target.equals("variance")) {
                /** Set variables to starting point */
                getVariance = true;
                counterAutofocus = 0;
                accumulate = 0.0;
            } else if (command.equals("requestService") && target.equals("autofocus") && action.equals("AutomaticController")){
                //requestService;autofocus;AutomaticController;None;None
                publishMessage(Initializer.AUTOFOCUS_APP_TOPIC, Initializer.AUTHENTICATE_AUTOFOCUS_ACTIVITY_MESSAGE);
            } else{
                Log.i(TAG_M, command + ";" + action);
            }
        }

        @Override
        public void onConnectionSuccessful(Context context, String requestId) {
            showToast("Connected (AutofocusActivity)");
            Log.i(TAG, "Connected!");
        }

        @Override
        public void onException(Context context, String requestId, Exception exception) {
            exception.printStackTrace();
            Log.i(TAG, requestId + " exception");
        }

        @Override
        public void onConnectionStatus(Context context, boolean connected) {
            Log.i(TAG, "Connection status is " + String.valueOf(connected));
        }
    };
    /**************************************************************************************************************/

    /*****************************************SUPPORT classes*******************************************************/
    /** Publish a message
     * @param topic: input String that defines the target topic of the mqtt client
     * @param message: input String that contains a message to be published
     * @return no return
     * */
    public void publishMessage(String topic, String message) {
        final int qos = 2;
        byte[] encodedPayload = new byte[0];
        try {
            encodedPayload = message.getBytes("UTF-8");
            MQTTServiceCommand.publish(AutofocusActivity.this, topic, encodedPayload, qos);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    /** Support class to display information */
    public void showToast(String message){
        /** Show toast easily
         * message: a string that contains the message
         * */
        try {
            Toast.makeText(AutofocusActivity.this, message, Toast.LENGTH_SHORT).show();
        }
        catch (Exception ex){
            Toast.makeText(AutofocusActivity.this, "Unable to show toast: " + ex.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    /**************************************************************************************************************/

}
