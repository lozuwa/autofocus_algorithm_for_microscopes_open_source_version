package pfm.improccameraautofocus;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
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

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

public class MainActivity extends Activity implements CvCameraViewListener2, MqttCallback {

    /** Variables */
    private static final String TAG_O = "Opencv::Activity";
    private static final String TAG_M = "MQTT::Activity";

    /** Init camera bridge (remember opencv uses camera1 api) */
    private CameraBridgeViewBase mOpenCvCameraView;
    private boolean mIsJavaCamera = true;

    /** Tensor containers (avoid calling them on the method onFrame, otherwise processing becomes really slow )*/
    private Mat mRgba;
    private Mat aux;
    private Mat mGray;

    /** New client for mqtt connection */
    private MqttAndroidClient client;

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

        /** Start mqtt client and connection */
        String clientId = MqttClient.generateClientId();
        client = new MqttAndroidClient(this.getApplicationContext(), "tcp://broker.hivemq.com:1883", clientId);
        try {
            IMqttToken token = client.connect();
            token.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG_M, "Connection succesful");

                    /** Subscribe to topic if connection was succesful */
                    final String topic = "/microscope";
                    int qos = 1;
                    try {
                        IMqttToken subToken = client.subscribe(topic, qos);
                        subToken.setActionCallback(new IMqttActionListener() {
                            @Override
                            public void onSuccess(IMqttToken asyncActionToken) {
                                // The message was published
                                Log.d(TAG_M, "Subscribed to topic: " + topic);
                            }

                            @Override
                            public void onFailure(IMqttToken asyncActionToken,
                                                  Throwable exception) {
                                // The subscription could not be performed, maybe the user was not
                                // authorized to subscribe on the specified topic e.g. using wildcards
                                Log.d(TAG_M, "Unsucessfull subscription to" + topic + " " + exception.toString());
                            }
                        });
                    } catch (MqttException e) {
                        e.printStackTrace();
                    }

                }
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.d(TAG_M, "Connection failed -- " + exception.toString());
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }

        /** Open the bridge with the camera interface and configure params
         * setVisibility -> True
         * callback for onFrame
         * setMaxFrameSize -> (640,480) Image processing is heavy to compute. So the smaller image, the better.
         * */
        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.camera_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.setMaxFrameSize(640,480);
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

    public void onDestroy() {
        super.onDestroy();
        /**Destroy camera when leaving the app*/
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

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

    public Integer count = 0;

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        aux = inputFrame.rgba();
        mGray = inputFrame.gray();
        Imgproc.Laplacian(mGray, mGray, CvType.CV_8U, 3, 1, 0);
        mGray.convertTo(mRgba, CvType.CV_8U);

        count++;
        if (count % 20 == 0){
            MatOfDouble mu = new MatOfDouble();
            MatOfDouble std= new MatOfDouble();
            Core.meanStdDev(mRgba, mu, std);
            double variance = Math.pow(mu.get(0,0)[0], 2);
            Log.i(TAG, String.valueOf(std) );
        }

        return aux;
    }

    /** Methods for mqtt connection */
    @Override
    public void connectionLost(Throwable cause) {

    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {

    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }

    public void showToast(String message){
        Toast.makeText()
    }

}
