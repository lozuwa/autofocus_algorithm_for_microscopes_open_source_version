package pfm.improccameraautofocus;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import net.igenius.mqttservice.MQTTService;
import net.igenius.mqttservice.MQTTServiceCommand;
import net.igenius.mqttservice.MQTTServiceLogger;
import net.igenius.mqttservice.MQTTServiceReceiver;

import java.io.UnsupportedEncodingException;
import java.util.UUID;

/**
 * Subclass for variable initialization
 * */

public class Initializer extends Application {
    /**
     * Broker
     * */
    static public String BROKER = "tcp://192.168.0.107:1883";

    /**
     * MQTT Topics
     * */
    static public String PREFIX = "/40X/2";
    static public String MICROSCOPE_TOPIC = "/microscope" + PREFIX;
    static public String CAMERA_APP_TOPIC = "/cameraApp" + PREFIX;
    static public String AUTOFOCUS_APP_TOPIC = "/autofocusApp" + PREFIX;
    static public String REMOTE_CONTROLLER_TOPIC = "/remoteController" + PREFIX;
    static public String MACROS_TOPIC = "/macros" + PREFIX;

    /**
     * MQTT Messages
     * */
    // /microscope
    public static String MOVE_X_RIGHT_FIELD = "move;x;right;field;1";
    public static String MOVE_X_LEFT_FIELD = "move;x;left;field;1";
    public static String MOVE_X_RIGHT_PROCESS_START = "move;x;right;process;1";
    public static String MOVE_X_LEFT_PROCESS_START = "move;x;left;process;1";
    public static String MOVE_X_RIGHT_PROCESS_END = "move;x;right;process;0";
    public static String MOVE_X_LEFT_PROCESS_END = "move;x;left;process;0";

    public static String MOVE_Y_UP_FIELD = "move;y;up;field;1";
    public static String MOVE_Y_DOWN_FIELD = "move;y;down;field;1";
    public static String MOVE_Y_UP_PROCESS_START = "move;y;up;process;1";
    public static String MOVE_Y_DOWN_PROCESS_START = "move;y;down;process;1";
    public static String MOVE_Y_UP_PROCESS_END = "move;y;up;process;0";
    public static String MOVE_Y_DOWN_PROCESS_END = "move;y;down;process;0";

    public static String MOVE_Z_UP_FIELD = "move;z;up;field;1";
    public static String MOVE_Z_DOWN_FIELD = "move;z;down;field;1";
    public static String MOVE_Z_UP_PROCESS_START = "move;z;up;process;1";
    public static String MOVE_Z_DOWN_PROCESS_START = "move;z;down;process;1";
    public static String MOVE_Z_UP_PROCESS_END = "move;z;up;process;0";
    public static String MOVE_Z_DOWN_PROCESS_END = "move;z;down;process;0";

    public static String HOME_X = "home;x;None;None;None";
    public static String HOME_Y = "home;y;None;None;None";
    public static String HOME_Z_TOP = "home;z;top;None;None";
    public static String HOME_Z_BOTTOM = "home;z;bottom;None;None";

    /** autofocus service Manual */
	public static String AUTHENTICATE_AUTOFOCUS_ACTIVITY_MESSAGE = "authenticate;AutofocusActivity;None;None;None";

    /** Autofocus service automatic*/
    public static String REQUEST_SERVICE_AUTOFOCUS = "requestService;autofocus;ManualController;None;None";

    /**  */
    public static String AUTOMATIC_SERVICE_START = "requestService;automatic;start;None;None";

    /**
     * Static variables
     * */
    public static int KEEP_ALIVE_TIMING = 15;
    public static int CONNECT_TIMEOUT = 60;

    /**
     * Constructor
     * */
    @Override
    public void onCreate() {
        super.onCreate();
        /** Initialize variables for MQTT Service */
        MQTTService.NAMESPACE = "com.example.android.camera2basic";
        MQTTService.KEEP_ALIVE_INTERVAL = KEEP_ALIVE_TIMING;
        MQTTService.CONNECT_TIMEOUT = CONNECT_TIMEOUT;
        /** Connect to server */
        /** Connect MQTT */
        String username = "pfm";
        String password = "161154029";
        String clientId = UUID.randomUUID().toString();
        int qos = 2;
        MQTTServiceLogger.setLogLevel(MQTTServiceLogger.LogLevel.DEBUG);
        MQTTServiceCommand.connectAndSubscribe(Initializer.this,
                                                BROKER,
                                                clientId,
                                                username,
                                                password,
                                                qos,
                                                true,
                                                AUTOFOCUS_APP_TOPIC);
    }

}
