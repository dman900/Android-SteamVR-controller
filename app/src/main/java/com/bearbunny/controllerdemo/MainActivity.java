package com.bearbunny.controllerdemo;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.hitlabnz.sensor_fusion_demo.orientationProvider.ImprovedOrientationSensor1Provider;
import org.hitlabnz.sensor_fusion_demo.orientationProvider.OrientationProvider;

import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends Activity implements SensorEventListener {

    // Setting keys
    private static final String PREFS_NAME = "ControllerDemoPreferences";
    private static final String TARGET_IP_KEY = "targetIP";
    private static final String TARGET_PORT_KEY = "targetPort";
    private static final String CONNECTION_MODE_KEY = "connectionMode";
    private static final String SEND_INTERVAL_KEY = "sendInterval";

    // Layout elements
    private EditText ipField;
    private EditText portField;
    private EditText sendIntervalField;
    private RadioGroup connectionModeToggleGroup;
    private ToggleButton sendDataModeToggle;
    private TextView ownIPField;
    private TextView orientationField;
    private TextView orientationOldField;
    private TextView fusedSensorField;
    private Button btn_button0;
    private Button btn_button1;

    private enum ConnectionModes {
        UDP, TCP
    }

    private String targetIP = "000.000.000.000";
    private int targetPort = 5555;
    private long sendInterval = 8l;
    private ConnectionModes connectionMode = ConnectionModes.UDP;

    private Timer timer;
    private Boolean sendEnabled = false;
    private SensorManager sensorManager;
    private Sensor linAccelerationSensor;
    private Sensor accelerationSensor;
    private Sensor magneticSensor;
    private Sensor orientation_old;

    DatagramPacket packet = null;
    DatagramSocket datagramSocket = null;

    private float[] linearAcc;
    private float[] rotationAngles;
    private float[] R_matrix;
    private float[] I_matrix;
    private float[] gravity;
    private float[] geomagnetic;
    private float[] rotationAngles_old;
    private float[] fusedEulerAngles;

    /**
     * Fused sensor data provider
     */
    private OrientationProvider currentOrientationProvider;

    private final int sensorSpeed = SensorManager.SENSOR_DELAY_FASTEST;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Restore preferences
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        targetIP = settings.getString(TARGET_IP_KEY, "000.000.000.000");
        targetPort = settings.getInt(TARGET_PORT_KEY, 5555);
        sendInterval = settings.getLong(SEND_INTERVAL_KEY, 8l);
        connectionMode = ConnectionModes.values()[settings.getInt(CONNECTION_MODE_KEY, 0)];

        ownIPField = (TextView) findViewById(R.id.ipTextView);
        ownIPField.setText("IP: " + GetWifiIP());

        ipField = (EditText) findViewById(R.id.targetIPField);
        ipField.setText(targetIP);

        portField = (EditText) findViewById(R.id.targetPortField);
        portField.setText(String.valueOf(targetPort));

        sendIntervalField = (EditText) findViewById(R.id.intervalField);
        sendIntervalField.setText(String.valueOf(sendInterval));

        connectionModeToggleGroup = (RadioGroup) findViewById(R.id.modeRadio);
        connectionModeToggleGroup.check(connectionModeToggleGroup.getChildAt(connectionMode.ordinal()).getId());

        sendDataModeToggle = (ToggleButton) findViewById(R.id.sendDataToggle);
        sendDataModeToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SendButtonPressed(sendDataModeToggle.isChecked());
            }
        });

        orientationField = (TextView) findViewById(R.id.orientationValue);
        orientationOldField = (TextView) findViewById(R.id.orientationOldValue);
        fusedSensorField = (TextView) findViewById(R.id.fusedValue);

        btn_button0 = (Button) findViewById(R.id.button0);
        btn_button1 = (Button) findViewById(R.id.button1);

        linearAcc = new float[] { 0f, 0f, 0f };
        rotationAngles = new float[] { 0f, 0f, 0f };
        R_matrix = new float[9];
        I_matrix = new float[9];
        geomagnetic = new float[3];
        gravity = new float[3];
        fusedEulerAngles = new float[3];

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        linAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        orientation_old = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);

        currentOrientationProvider = new ImprovedOrientationSensor1Provider(sensorManager);
    }

    @Override
    protected void onPause() {
        super.onPause();
        currentOrientationProvider.stop();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, orientation_old, sensorSpeed);
        sensorManager.registerListener(this, linAccelerationSensor, sensorSpeed);
        sensorManager.registerListener(this, magneticSensor, sensorSpeed);
        sensorManager.registerListener(this, accelerationSensor, sensorSpeed);
        currentOrientationProvider.start();
    }

    private void SendButtonPressed(boolean enabled)
    {
        ipField.setEnabled(!enabled);
        portField.setEnabled(!enabled);
        sendIntervalField.setEnabled(!enabled);
        connectionModeToggleGroup.setEnabled(!enabled);
        sendEnabled = enabled;
        if (enabled)
        {
            RefreshFieldData();
            StartDataSend();
        }
        else
        {
            timer.cancel();
        }
    }

    private void RefreshFieldData()
    {
        targetIP = ipField.getText().toString();
        targetPort = Integer.parseInt(portField.getText().toString());
        sendInterval = Long.parseLong(sendIntervalField.getText().toString());
        RadioButton selectedButton = (RadioButton) findViewById(connectionModeToggleGroup.getCheckedRadioButtonId());
        connectionMode = ConnectionModes.values()[connectionModeToggleGroup.indexOfChild(selectedButton)];
    }


    private final String timestamp_TAG = "TMST";
    private final String orientation_TAG = "OR";
    private final String linearAcc_TAG = "LA";
    private final String fusedOrientation_TAG = "FO";
    private final String buttons_TAG = "BTN";
    private final String close_TAG = "END";
    private long packet_timestamp;
    private String packet_message;
    private int buttonState;

    private String GetPacketMessage()
    {
        packet_timestamp = System.nanoTime();
        buttonState = 0;
        if (btn_button0.isPressed())
        {
            buttonState += 1;
        }

        if (btn_button1.isPressed())
        {
            buttonState += 2;
        }

        return timestamp_TAG + ";" + packet_timestamp + ";" +
                //orientation_TAG + ";" + rotationAngles_old[1] + ";" + rotationAngles_old[0] + ";" + -rotationAngles_old[2] + ";" +
                //linearAcc_TAG + ";" + linearAcc[1] +";" + linearAcc[0] +";" + -linearAcc[2] +";" +
                fusedOrientation_TAG + ";" + fusedEulerAngles[1] +";" + fusedEulerAngles[0] +";" + -fusedEulerAngles[2] +";" +
                buttons_TAG + ";" + buttonState + ";" + close_TAG;
    }

    private InetAddress target;
    private void StartDataSend()
    {
        try {
            datagramSocket = new DatagramSocket();
            target = InetAddress.getByName(targetIP);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        if (datagramSocket != null) {
            timer = new Timer(true);
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    try {
                        packet_message = GetPacketMessage();
                        System.out.println("Packet message: " + packet_message);
                        if (packet == null) {
                            byte[] bytes = packet_message.getBytes();
                            packet = new DatagramPacket(bytes, bytes.length, target, targetPort);
                        }
                        else
                        {
                            packet.setData(packet_message.getBytes());
                        }
                        datagramSocket.send(packet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }, 0l, sendInterval);
        }
    }

    private String GetWifiIP()
    {
        WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();

        // Convert little-endian to big-endianif needed
        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            ipAddress = Integer.reverseBytes(ipAddress);
        }

        byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

        String ipAddressString;
        try {
            ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
        } catch (UnknownHostException ex) {
            Log.e("WIFIIP", "Unable to get host address.");
            ipAddressString = null;
        }

        return ipAddressString;
    }

    @Override
    protected void onStop() {
        super.onStop();

        targetIP = ipField.getText().toString();
        targetPort = Integer.parseInt(portField.getText().toString());
        RadioButton selectedButton = (RadioButton) findViewById(connectionModeToggleGroup.getCheckedRadioButtonId());
        connectionMode = ConnectionModes.values()[connectionModeToggleGroup.indexOfChild(selectedButton)];
        sendInterval = Integer.parseInt(sendIntervalField.getText().toString());

        // Save preferences
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(TARGET_IP_KEY, targetIP);
        editor.putInt(TARGET_PORT_KEY, targetPort);
        editor.putInt(CONNECTION_MODE_KEY, connectionMode.ordinal());
        editor.putLong(SEND_INTERVAL_KEY, sendInterval);
        editor.commit();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        RefreshFusedOrientationData();
        switch (event.sensor.getType())
        {
//            case Sensor.TYPE_LINEAR_ACCELERATION:
//                linearAcc = event.values;
//                fusedSensorField.setText(Float3ToString(linearAcc));
//                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                geomagnetic = event.values;
                if (CalculateOrientation())
                {
                    orientationField.setText(Float3ToString(rotationAngles));
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                gravity = event.values;
                if (CalculateOrientation())
                {
                    orientationField.setText(Float3ToString(rotationAngles));
                }
                break;
            case Sensor.TYPE_ORIENTATION:
                rotationAngles_old = event.values;
                rotationAngles_old[0] -= 360f;
                rotationAngles_old[2] *= -1f;
                orientationOldField.setText(Float3ToString(rotationAngles_old));
                break;
        }
    }

    private void RefreshFusedOrientationData()
    {
        currentOrientationProvider.getEulerAngles(fusedEulerAngles);
        fusedEulerAngles[0] *= 57.2957795f;
        fusedEulerAngles[1] *= 57.2957795f;
        fusedEulerAngles[2] *= 57.2957795f;
        fusedSensorField.setText(Float3ToString(fusedEulerAngles));
    }

    private final float PI = (float) Math.PI;
    private Boolean CalculateOrientation()
    {
        R_matrix = new float[9];
        I_matrix = new float[9];
        if (SensorManager.getRotationMatrix(R_matrix, I_matrix, gravity, geomagnetic))
        {
            SensorManager.getOrientation(R_matrix, rotationAngles);

            rotationAngles[0] = rotationAngles[0] * 180f / PI;
            rotationAngles[1] = rotationAngles[1] * 180f / PI;
            rotationAngles[2] = rotationAngles[2] * 180f / PI;
            return true;
        }
        else
        {
            return false;
        }
    }

    private final String format = "%6.2f";
    private String Float3ToString(float[] values)
    {
        return String.format(Locale.US, format, values[0]) + "; " +String.format(Locale.US, format, values[1]) + "; " +
                String.format(Locale.US, format, values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}