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
    static public String BROKER = "tcp://192.168.0.103:1883";

    /**
     * MQTT Topics
     * */
    static public String AUTOFOCUS_APP_TOPIC = "/autofocusApp";
    static public String CAMERA_APP_TOPIC = "/cameraApp";
    static public String EXTRA_ACTIONS_TOPIC = "/extra";

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
