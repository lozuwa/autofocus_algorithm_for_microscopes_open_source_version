package pfm.improccameraautofocus;

/**
 * Author: Rodrigo Loza
 * Company: pfm Medical Bolivia
 * Description: app designed to work as a remote controller for the click microscope and
 * the camera app.
 * */

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.core.MatOfDouble;
import org.opencv.imgproc.Imgproc;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;

public class MainActivity extends Activity implements CvCameraViewListener2, MqttCallback, View.OnClickListener {

    /** Variables */
    public Button brokerButton;

    /** Constants */
    private static final String TAG_O = "Opencv::Activity";
    private static final String TAG_M = "MQTT::Activity";

    public static final String TEST_BROKER = "tcp://test.mosquitto.org:1883";
    public static final String PC_BROKER = "tcp:192.168.3.174:1883";
    public String CHOSEN_BROKER = "";

    /** Init camera bridge (remember opencv uses camera1 api) */
    private CameraBridgeViewBase mOpenCvCameraView;
    public static final String AUTOFOCUS_TOPIC = "/autofocus";
    public static final String VARIANCE_TOPIC = "/variance";
    private boolean mIsJavaCamera = true;

    /** Tensor containers (avoid calling them on the method onFrame, otherwise processing becomes really slow )*/
    private Mat mRgba;
    private Mat aux;
    private Mat mGray;

    /** Variables */
    public Double variance = 0.0;
    public Boolean broker_bool;

    /** Variables for autofocus */
    public Boolean get_variance;
    public Integer counter_autofocus = 0;

    /** New client for mqtt connection */
    private MqttAndroidClient client;
    public MqttConnectOptions options;

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
    public MainActivity() {
        Log.i(TAG_O, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /** Display some info */
        Log.i(TAG_O, "called onCreate");

        /** Fix orientation to portrait and keep screen on */
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        /** Set content to the xml activity_main */
        setContentView(R.layout.activity_main);

        /** Permissions */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
            }
        }

        /** Initialize UI elements */
        brokerButton = (Button) findViewById(R.id.button);
        brokerButton.setOnClickListener(this);

        /** Initialize variables */
        get_variance = false;
        broker_bool = false;

        /** Start mqtt client and connection */
         options = new MqttConnectOptions();
         options.setMqttVersion( 4 );
         options.setKeepAliveInterval( 300 );
         options.setCleanSession( false );
         connectMQTT();

        /** Open the bridge with the camera interface and configure params
         * setVisibility -> True
         * callback for onFrame
         * setMaxFrameSize -> (640,480) Image processing is heavy to compute. So the smaller image, the better.
         * */
        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.tutorial1_activity_java_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.setMaxFrameSize(320,180);
    }

    @Override
    public void onPause(){
        /** Turn off camera when screen goes off, or change application */
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume(){
        super.onResume();
        /**Resume opencv libraries when coming back to the app*/
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG_O, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d(TAG_O, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        /**Destroy camera when leaving the app*/
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    /***********************************************OpenCV***********************************************************/
    public void onCameraViewStarted(int width, int height) {
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
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {

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
        if (get_variance){

            /** Autofocus steps */
            counter_autofocus++;
            MatOfDouble mu = new MatOfDouble();
            MatOfDouble std= new MatOfDouble();
            Core.meanStdDev(mRgba, mu, std);
            variance += Math.pow(mu.get(0,0)[0], 2);
            //Log.i(TAG_O, String.valueOf(variance));

            if (counter_autofocus == 30){
                variance = variance / counter_autofocus;
                publish_message( VARIANCE_TOPIC, String.valueOf(variance) );
                get_variance = false;
                counter_autofocus = 0;
            }
        }

        /*  MatOfDouble mu = new MatOfDouble();
            MatOfDouble std= new MatOfDouble();
            Core.meanStdDev(mRgba, mu, std);
            variance = Math.pow(mu.get(0,0)[0], 2);
            Log.i(TAG_O, String.valueOf(variance) );*/

        return aux;
    }
    /**********************************************************************************************************/

    /***********************************************MQTT***********************************************************/
    /** Methods for mqtt connection */
    @Override
    public void connectionLost(Throwable cause) {
        showToast("Connection lost!");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String mess_payload = new String(message.getPayload());
        /** Show in a toast the messages that arrive */
        showToast(topic+"  --  "+mess_payload);
        /** Actions based on the income messages */
        if (topic.equals(AUTOFOCUS_TOPIC) && mess_payload.equals("get")){
            counter_autofocus = 0;
            get_variance = true;
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Log.i(TAG_M, "Message has been sent: " + token.toString());
    }
    /**************************************************************************************************************/

    /*****************************************SUPPORT classes*******************************************************/
    /** Support class to connect mqtt client */
    public void connectMQTT(){
        String clientId = MqttClient.generateClientId();
        client = new MqttAndroidClient(this.getApplicationContext(), TEST_BROKER, clientId);
        try {
            IMqttToken token = client.connect(options);
            token.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG_M, "onSuccess");
                    Toast.makeText(MainActivity.this, "Connection successful", Toast.LENGTH_SHORT).show();
                    client.setCallback(MainActivity.this);
                    final String topic = AUTOFOCUS_TOPIC;
                    int qos = 1;
                    try {
                        IMqttToken subToken = client.subscribe(topic, qos);
                        subToken.setActionCallback(new IMqttActionListener() {
                            @Override
                            public void onSuccess(IMqttToken asyncActionToken){
                            }
                            @Override
                            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                            }
                        });
                    } catch (MqttException e) {
                        e.printStackTrace();
                    } catch (NullPointerException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.d(TAG_M, "onFailure");
                    Toast.makeText(MainActivity.this, "Connection failed", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    /** Support class to handle the publishing of messages */
    public void publish_message(String topic, String payload){
        /** Hardware must be configured for:
         * topic: /autofocus -> saved in static final variable
         * message: values [0,1] that determine direction based on the sensors and motor direction
         * setRetained -> false
         * */
        byte[] encodedPayload = new byte[0];
        try {
            encodedPayload = payload.getBytes("UTF-8");
            MqttMessage message = new MqttMessage(encodedPayload);
            message.setRetained(false);
            client.publish(topic, message);
        } catch (UnsupportedEncodingException | MqttException e) {
            e.printStackTrace();
        }
    }

    /** Support class to display information */
    public void showToast(String message){
        try {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
        catch (Exception ex){
            Toast.makeText(MainActivity.this, "Unable to show toast: " + ex.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    /** UI elements' events */
    @Override
    public void onClick(View v) {
        broker_bool = !broker_bool;
        if (broker_bool) {
            CHOSEN_BROKER = PC_BROKER;
            showToast("Connecting to: " + CHOSEN_BROKER);
        }
        else {
            CHOSEN_BROKER = TEST_BROKER;
            showToast("Connecting to: " + CHOSEN_BROKER);
        }
        connectMQTT();
    }
    /**************************************************************************************************************/

}