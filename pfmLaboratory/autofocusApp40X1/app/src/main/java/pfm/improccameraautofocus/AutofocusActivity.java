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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
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
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.logging.Logger;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AutofocusActivity extends Activity
        implements CameraBridgeViewBase.CvCameraViewListener2 {
    /**
     * Constants
     * */
    private static final String TAG_O = "Opencv::Activity";
    private static final String TAG_M = "MQTT::Activity";
    private static final String TAG_T = "Tensorflow::Activity";

    /**
     * Init camera bridge (remember opencv uses camera1 api)
     * */
    private CameraBridgeViewBase mOpenCvCameraView;

    /**
     * Decimal formatter
     * */
    public DecimalFormat df;

    /**
     * Tensor containers (avoid calling them on the method onFrame, otherwise processing becomes
     * really slow )
     * */
    private Mat mRgba;
    private Mat aux;
    private Mat mGray;

    /**
     * Variables autofocus
     * */
    public Double variance;
    public Double varianceq0 = 0.0;
    public Double varianceq1 = 0.0;
    public Double varianceq2 = 0.0;
    public Double varianceq3 = 0.0;

    public Rect roi0;
    public Rect roi1;
    public Rect roi2;
    public Rect roi3;

    public Boolean getVariance;
    public int counterAutofocus = 0;
    public Double accumulate = 0.0;
    public Double accumulateq0 = 0.0;
    public Double accumulateq1 = 0.0;
    public Double accumulateq2 = 0.0;
    public Double accumulateq3 = 0.0;

    public boolean activateq0;
    public boolean activateq1;
    public boolean activateq2;
    public boolean activateq3;

    /**
     * Variables tensorflow models
     * */
    public Boolean classifyPatches;

    private Classifier classifier;

    private static final int INPUT_SIZE = 128;
    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128;
    private static final String INPUT_NAME = "input";
    private static final String OUTPUT_NAME = "final_result";

    private static final String MODEL_FILE = "file:///android_asset/output_graph.pb";
    private static final String LABEL_FILE = "file:///android_asset/output_labels.txt";

    /**
     * Permission statements
     * */
    public int PERMISSION_WRITE_EXTERNAL_STORAGE = 1;
    public int PERMISSION_CAMERA = 2;

    /**
     * Load the opencv module (automatic request to playstore if not installed)
     * */
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

    /**
     * Info
     * */
    public AutofocusActivity() {
        Log.i(TAG_O, "Instantiated new " + this.getClass());
    }

    /**
     * Called when the activity is first created.
     * */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /**
         * Display some info
         * */
        Log.i(TAG_O, "called onCreate");

        /**
         * Fix orientation to portrait and keep screen on
         * */
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        /**
         * Set content to the xml activity_camera_and_controller
         * */
        setContentView(R.layout.activity_autofocus);

        /**
         * Permissions
         * */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) !=
                                        PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CAMERA}, 1);
            }
        }
        grantPermissionCamera();
        grantPermissionExternalStorage();

        /**
         * Create tensorflow model
         * */
        classifier = TensorFlowImageClassifier.create(getAssets(),
                                                        MODEL_FILE,
                                                        LABEL_FILE,
                                                        INPUT_SIZE,
                                                        IMAGE_MEAN,
                                                        IMAGE_STD,
                                                        INPUT_NAME,
                                                        OUTPUT_NAME);

        /**
         * Initialize classes
         * */
        df = new DecimalFormat("##.###");
        df.setRoundingMode(RoundingMode.DOWN);

        /**
         * Initialize variables
         * */
        getVariance = false;
        classifyPatches = false;

        activateq0 = false;
        activateq1 = false;
        activateq2 = false;
        activateq3 = false;

        //Rect roi = new Rect(x, y, width, height);
        roi0 = new Rect(new Point(180 ,70), new Point(180+128, 70+128));
        roi1 = new Rect(new Point(360 ,70), new Point(360+128, 70+128));
        roi2 = new Rect(new Point(180 ,240), new Point(180+128, 240+128));
        roi3 = new Rect(new Point(360 ,240), new Point(360+128, 240+128));

        /*roi0 = new Rect(new Point(180 ,70), new Point(360, 240));
        roi1 = new Rect(new Point(360 ,70), new Point(540, 240));
        roi2 = new Rect(new Point(180 ,240), new Point(360, 410));
        roi3 = new Rect(new Point(360 ,240), new Point(540, 410));*/

        /** Open the bridge with the camera interface and configure params
         * setVisibility -> True
         * callback for onFrame
         * setMaxFrameSize -> (640,480) Image processing is heavy to compute. So the smaller image, the better.
         * */
        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.tutorial1_activity_java_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        //mOpenCvCameraView.setMaxFrameSize(4200, 4200);
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
        /** Variables */
        //List<Mat> quadrants = new ArrayList<Mat>();
        /** Get input frame and convert to grayscale */
        aux = inputFrame.rgba();
        mGray = inputFrame.gray();
        /** Feedback */
        //Log.i("SIZE IMAGE::", String.valueOf(aux.rows()) + "," + String.valueOf(aux.cols()));
        /** If get patch classification is required, then classify the given regions */
        if (classifyPatches){
            /*** Split image into four regions */
            Mat quadrant0 = mGray.submat(roi0);
            Mat quadrant1 = mGray.submat(roi1);
            Mat quadrant2 = mGray.submat(roi2);
            Mat quadrant3 = mGray.submat(roi3);
            /** Save images */
            Imgcodecs.imwrite(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q0.jpg", quadrant0);
            Imgcodecs.imwrite(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q1.jpg", quadrant1);
            Imgcodecs.imwrite(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q2.jpg", quadrant2);
            Imgcodecs.imwrite(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q3.jpg", quadrant3);
            /** Configuration for decoded file conversion to bitmap */
            BitmapFactory.Options op = new BitmapFactory.Options();
            op.inPreferredConfig = Bitmap.Config.ARGB_8888;
            /** Read sliced patches */
            Bitmap bMap0 = BitmapFactory.decodeFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q0.jpg", op);
            Bitmap bMap1 = BitmapFactory.decodeFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q1.jpg", op);
            Bitmap bMap2 = BitmapFactory.decodeFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q2.jpg", op);
            Bitmap bMap3 = BitmapFactory.decodeFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "q3.jpg", op);
            /** Classify patches */
            final List<Classifier.Recognition> resultsroi0 = classifier.recognizeImage(bMap0);
            final List<Classifier.Recognition> resultsroi1 = classifier.recognizeImage(bMap1);
            final List<Classifier.Recognition> resultsroi2 = classifier.recognizeImage(bMap2);
            final List<Classifier.Recognition> resultsroi3 = classifier.recognizeImage(bMap3);
            /** Compute classification results */
            activateq0 = computeResult(resultsroi0);
            activateq1 = computeResult(resultsroi1);
            activateq2 = computeResult(resultsroi2);
            activateq3 = computeResult(resultsroi3);
            /** Feedback */
            Log.i(TAG_T, "Results0: " + resultsroi0.get(0).getTitle() + ", " + String.valueOf(resultsroi0.get(0).getConfidence()) + ", result: " + String.valueOf(activateq0));
            Log.i(TAG_T, "Results1: " + resultsroi1.get(0).getTitle() + ", " + String.valueOf(resultsroi1.get(0).getConfidence()) + ", result: " + String.valueOf(activateq1));
            Log.i(TAG_T, "Results2: " + resultsroi2.get(0).getTitle() + ", " + String.valueOf(resultsroi2.get(0).getConfidence()) + ", result: " + String.valueOf(activateq2));
            Log.i(TAG_T, "Results3: " + resultsroi3.get(0).getTitle() + ", " + String.valueOf(resultsroi3.get(0).getConfidence()) + ", result: " + String.valueOf(activateq3));
            /** Restart variable */
            classifyPatches = false;
        }
        else {
            // pass
        }
        /**If the start autofocus sequence is activated, process the variance of the laplace filtered image
         * The variance coefficient tells us whether the image is in focus or not.
         * */
        if (getVariance) {
            /*** Split image into four regions */
            Mat quadrant0 = mGray.submat(roi0);
            Mat quadrant1 = mGray.submat(roi1);
            Mat quadrant2 = mGray.submat(roi2);
            Mat quadrant3 = mGray.submat(roi3);
            /** Convolve each region with a HPF
             * Decide to compute */
            if (activateq0){
                varianceq0 = extractFeature(quadrant0);
            }
            else {
                varianceq0 = 0.0;
            }
            if (activateq1){
                varianceq1 = extractFeature(quadrant1);
            }
            else {
                varianceq1 = 0.0;
            }
            if (activateq2){
                varianceq2 = extractFeature(quadrant2);
            }
            else {
                varianceq2 = 0.0;
            }
            if (activateq3){
                varianceq3 = extractFeature(quadrant3);
            }
            else {
                varianceq3 = 0.0;
            }
            /** Get the variance of the complete image */
            variance = extractFeature(mGray);
            /** Feedback */
            Log.i(TAG_M, String.valueOf(variance) + "," + String.valueOf(varianceq0) + "," +
                    String.valueOf(varianceq1) + "," + String.valueOf(varianceq2) + "," +
                    String.valueOf(varianceq3));
            /** If we have a weird value, then ignore it */
            if (variance < 2.0) {
                Log.i(TAG_M, "Not a good value: " + String.valueOf(variance));
            }
            else {
                /** Let's average the variance in order to get a better estimate */
                if (counterAutofocus == 3) {
                    accumulate = Double.valueOf(df.format(accumulate /= 3.0));
                    accumulateq0 = Double.valueOf(df.format(accumulateq0 /= 3.0));
                    accumulateq1 = Double.valueOf(df.format(accumulateq1 /= 3.0));
                    accumulateq2 = Double.valueOf(df.format(accumulateq2 /= 3.0));
                    accumulateq3 = Double.valueOf(df.format(accumulateq3 /= 3.0));
                    String valuesVariance = String.valueOf(accumulate) + "," +
                                            String.valueOf(accumulateq0) + "," +
                                            String.valueOf(accumulateq1) + "," +
                                            String.valueOf(accumulateq2) + "," +
                                            String.valueOf(accumulateq3);
                    publishMessage(Initializer.AUTOFOCUS_APP_TOPIC,
                                    "send;variance;None;None;" + valuesVariance);
                    getVariance = false;
                }
                else {
                    /** Accumulate complete image */
                    accumulate += variance;
                    /** Accumulate for the quadrants */
                    accumulateq0 += varianceq0;
                    accumulateq1 += varianceq1;
                    accumulateq2 += varianceq2;
                    accumulateq3 += varianceq3;
                    /** Increase counter */
                    counterAutofocus++;
                }
            }
        }
        else {
            //nothing()
        }
        return aux;
    }

    /**
     * Convolve the input image with a HPF, give it format and then
     * compute the variance of the final signal.
     * @param inputImage: input Mat image
     * @return feature_: a double value that contains the variance
     *                   of the processed image.
     * */
    public Double extractFeature(Mat inputImage){
        /** Variables */
        MatOfDouble mu = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        /** Extract high frequency signals */
        Imgproc.Laplacian(inputImage, inputImage, CvType.CV_8U, 3, 1, 0);
        /** Give format to the image */
        inputImage.convertTo(inputImage, CvType.CV_8U);
        /** Compute variance */
        Core.meanStdDev(inputImage, mu, std);
        Double feature_ = Math.pow(mu.get(0,0)[0], 2);
        /** Return value */
        return feature_;
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
            } else if (command.equals("classify") && target.equals("patches")){
                /** Set booleans to activate patch classification */
                classifyPatches = true;
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

    /*****************************************Support methods*******************************************************/
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

    /** Compute classification */
    public boolean computeResult(List <Classifier.Recognition> result){
        double confidencehdb = 0;
        double confidenceldb = 0;
        if (result.get(0).getTitle().equals("hdb")){
            confidencehdb = result.get(0).getConfidence();
            confidenceldb = 1 - confidencehdb;
        }
        else{
            confidenceldb = result.get(0).getConfidence();
            confidencehdb = 1 - confidenceldb;
        }
        return confidencehdb >= confidenceldb? false: true;
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

    /**
     * Grants permission to WRITE_EXTERNAL_STORAGE
     * */
    public void grantPermissionExternalStorage(){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_WRITE_EXTERNAL_STORAGE);
            }
        }
    }

    /**
     * Grants permission to CAMERA
     * */
    public void grantPermissionCamera(){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showToast("Write external permission granted");
                } else {
                }
                return;
            }
            case 2: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showToast("Camera permission granted");
                } else {
                }
                return;
            }
        }
    }

}
