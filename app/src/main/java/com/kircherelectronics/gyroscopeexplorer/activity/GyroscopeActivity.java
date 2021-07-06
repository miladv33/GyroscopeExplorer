package com.kircherelectronics.gyroscopeexplorer.activity;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import com.kircherelectronics.fsensor.filter.averaging.MeanFilter;
import com.kircherelectronics.fsensor.observer.SensorSubject;
import com.kircherelectronics.fsensor.sensor.FSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.ComplementaryGyroscopeSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.GyroscopeSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.KalmanGyroscopeSensor;
import com.kircherelectronics.gyroscopeexplorer.Controller;
import com.kircherelectronics.gyroscopeexplorer.R;
import com.kircherelectronics.gyroscopeexplorer.listener.Cotrollerlistener;
import com.kircherelectronics.gyroscopeexplorer.view.joystick.JoyStickView;
import com.kircherelectronics.gyroscopeexplorer.datalogger.DataLoggerManager;
import com.kircherelectronics.gyroscopeexplorer.gauge.GaugeBearing;
import com.kircherelectronics.gyroscopeexplorer.gauge.GaugeRotation;
import com.kircherelectronics.gyroscopeexplorer.view.VectorDrawableButton;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/*
 * Copyright 2013-2017, Kaleb Kircher - Kircher Engineering, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The main activity displays the orientation estimated by the sensor(s) and
 * provides an interface for the user to modify settings, reset or view help.
 *
 * @author Kaleb
 */
public class GyroscopeActivity extends AppCompatActivity implements Cotrollerlistener {
    private final static int WRITE_EXTERNAL_STORAGE_REQUEST = 1000;

    // Indicate if the output should be logged to a .csv file
    private boolean logData = false;

    private boolean meanFilterEnabled;

    private float[] fusedOrientation = new float[3];

    // The gauge views. Note that these are views and UI hogs since they run in
    // the UI thread, not ideal, but easy to use.
    private GaugeBearing gaugeBearingCalibrated;
    private GaugeRotation gaugeTiltCalibrated;

    // Handler for the UI plots so everything plots smoothly
    protected Handler uiHandler;
    protected Runnable uiRunnable;

    private TextView tvXAxis;
    private TextView tvYAxis;
    private TextView tvZAxis;
    private TextView controllerXText;
    private TextView controllerYText;
    private JoyStickView joyStickView;

    private FSensor fSensor;

    private MeanFilter meanFilter;

    private DataLoggerManager dataLogger;

    private Dialog helpDialog;

    private SensorSubject.SensorObserver sensorObserver = new SensorSubject.SensorObserver() {
        @Override
        public void onSensorChanged(float[] values) {
            updateValues(values);
        }
    };

    Controller rightController = new Controller(300, 300);
    Controller leftController = new Controller(300, 300);
    double lastZ = 0;
    double lastX = 0;
    double lastY = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gyroscope);
        dataLogger = new DataLoggerManager(this);
        meanFilter = new MeanFilter();
        uiHandler = new Handler();
        uiRunnable = new Runnable() {
            @Override
            public void run() {
                uiHandler.postDelayed(this, 100);
                updateText();
                updateGauges();
            }
        };

        initUI();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.gyroscope, menu);
        return true;
    }

    /**
     * Event Handling for Individual menu item selected Identify single menu
     * item by it's id
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_reset:
                fSensor.reset();
                break;
            case R.id.action_config:
                Intent intent = new Intent();
                intent.setClass(this, ConfigActivity.class);
                startActivity(intent);
                break;
            case R.id.action_help:
                showHelpDialog();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }

        return true;
    }

    @Override
    public void onResume() {
        super.onResume();

        Mode mode = readPrefs();

        switch (mode) {
            case GYROSCOPE_ONLY:
                fSensor = new GyroscopeSensor(this);
                break;
            case COMPLIMENTARY_FILTER:
                fSensor = new ComplementaryGyroscopeSensor(this);
                ((ComplementaryGyroscopeSensor) fSensor).setFSensorComplimentaryTimeConstant(getPrefImuOCfQuaternionCoeff());
                break;
            case KALMAN_FILTER:
                fSensor = new KalmanGyroscopeSensor(this);
                break;
        }

        fSensor.register(sensorObserver);
        fSensor.start();
        uiHandler.post(uiRunnable);
    }

    @Override
    public void onPause() {
        if (helpDialog != null && helpDialog.isShowing()) {
            helpDialog.dismiss();
        }

        fSensor.unregister(sensorObserver);
        fSensor.stop();
        uiHandler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == WRITE_EXTERNAL_STORAGE_REQUEST) {// If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission was granted, yay! Do the
                // contacts-related task you need to do.

                startDataLog();
            }
        }
    }

    private boolean getPrefMeanFilterEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.MEAN_FILTER_SMOOTHING_ENABLED_KEY, false);
    }

    private float getPrefMeanFilterTimeConstant() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return Float.parseFloat(prefs.getString(ConfigActivity.MEAN_FILTER_SMOOTHING_TIME_CONSTANT_KEY, "0.5"));
    }

    private boolean getPrefKalmanEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.KALMAN_QUATERNION_ENABLED_KEY, false);
    }

    private boolean getPrefComplimentaryEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.COMPLIMENTARY_QUATERNION_ENABLED_KEY, false);
    }

    private float getPrefImuOCfQuaternionCoeff() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return Float.parseFloat(prefs.getString(ConfigActivity.COMPLIMENTARY_QUATERNION_COEFF_KEY, "0.5"));
    }

    private void initStartButton() {
        final VectorDrawableButton button = findViewById(R.id.button_start);

        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!logData) {
                    button.setText(getString(R.string.action_stop));
                    startDataLog();
                } else {
                    button.setText(getString(R.string.action_start));
                    stopDataLog();
                }
            }
        });
    }

    /**
     * Initialize the UI.
     */
    private void initUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        // Initialize the calibrated text views
        tvXAxis = this.findViewById(R.id.value_x_axis_calibrated);
        tvYAxis = this.findViewById(R.id.value_y_axis_calibrated);
        tvZAxis = this.findViewById(R.id.value_z_axis_calibrated);
        controllerXText = this.findViewById(R.id.label_x_forController);
        controllerYText = this.findViewById(R.id.label_y_forController);
        joyStickView = this.findViewById(R.id.joystick);
        joyStickView.setCotrollerlistener(this);
        // Initialize the calibrated gauges views
        gaugeBearingCalibrated = findViewById(R.id.gauge_bearing_calibrated);
        gaugeTiltCalibrated = findViewById(R.id.gauge_tilt_calibrated);

        initStartButton();
    }


    private Mode readPrefs() {
        meanFilterEnabled = getPrefMeanFilterEnabled();
        boolean complimentaryFilterEnabled = getPrefComplimentaryEnabled();
        boolean kalmanFilterEnabled = getPrefKalmanEnabled();

        if (meanFilterEnabled) {
            meanFilter.setTimeConstant(getPrefMeanFilterTimeConstant());
        }

        Mode mode;

        if (!complimentaryFilterEnabled && !kalmanFilterEnabled) {
            mode = Mode.GYROSCOPE_ONLY;
        } else if (complimentaryFilterEnabled) {
            mode = Mode.COMPLIMENTARY_FILTER;
        } else {
            mode = Mode.KALMAN_FILTER;
        }

        return mode;
    }

    private void showHelpDialog() {
        helpDialog = new Dialog(this);
        helpDialog.setCancelable(true);
        helpDialog.setCanceledOnTouchOutside(true);
        helpDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = getLayoutInflater().inflate(R.layout.layout_help_home, (ViewGroup) findViewById(android.R.id.content), false);
        helpDialog.setContentView(view);
        helpDialog.show();
    }

    private void startDataLog() {
        if (!logData && requestPermissions()) {
            logData = true;
            dataLogger.startDataLog();
        }
    }

    private void stopDataLog() {
        if (logData) {
            logData = false;
            String path = dataLogger.stopDataLog();
            Toast.makeText(this, "File Written to: " + path, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateText() {
        double z = (Math.toDegrees(fusedOrientation[0]) + 360) % 360;
        double x = (Math.toDegrees(fusedOrientation[1]) + 360) % 360;
        double y = (Math.toDegrees(fusedOrientation[2]) + 360) % 360;

        double defferentY = lastY - (fusedOrientation[2] * 1000);
        if (defferentY >= 1 || defferentY <= -1)
            rightController.setX((int) (rightController.getX() + defferentY));

        GamePadConfig gamePadConfig = new GamePadConfig();
        setJoystickView(joyStickView, gamePadConfig, true);
        lastY = fusedOrientation[2] * 1000;

        tvZAxis.setText(String.format(Locale.getDefault(), "%.1f", z));
        tvXAxis.setText(String.format(Locale.getDefault(), "%.1f", x));
        tvYAxis.setText(String.format(Locale.getDefault(), "%.1f", y));
    }

    private void updateGauges() {
        gaugeBearingCalibrated.updateBearing(fusedOrientation[0]);
        gaugeTiltCalibrated.updateRotation(fusedOrientation[1], fusedOrientation[2]);
    }

    private boolean requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_EXTERNAL_STORAGE_REQUEST);
            return false;
        }

        return true;
    }

    private void updateValues(float[] values) {
        fusedOrientation = values;
        if (meanFilterEnabled) {
            fusedOrientation = meanFilter.filter(fusedOrientation);
        }

        if (logData) {
            dataLogger.setRotation(fusedOrientation);
        }
    }

    @Override
    public void moved(final int x, final int y) {
        controllerXText.post(new Runnable() {
            @Override
            public void run() {
                controllerXText.setText("x: " + x);
                controllerYText.setText("y: " + y);
            }
        });

    }

    private enum Mode {
        GYROSCOPE_ONLY,
        COMPLIMENTARY_FILTER,
        KALMAN_FILTER
    }

    private void setJoystickView(JoyStickView view, GamePadConfig pState, boolean isRightController) {
        view.muckTouch(mockOnTouchJoysticks(view, pState, isRightController));
    }

    private MotionEvent mockOnTouch(JoyStickView view, GamePadConfig pState, boolean isRightController) {
        Controller controller;
        if (isRightController) {
            controller = rightController;
        } else {
            controller = leftController;
        }
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis() + 50;
        int metaState = 0;

        int x = convertRange(0, 360, (int) view.startRange, (int) view.endRange, controller.getX());
        int y = convertRange(0, 360, (int) view.startRange, (int) view.endRange, controller.getY());
        int motionEvent;
        if (x == 0) {
            view.justShowTouch = false;
            motionEvent = MotionEvent.ACTION_UP;
        } else {
            view.justShowTouch = true;
            motionEvent = MotionEvent.ACTION_DOWN;
        }
        return MotionEvent.obtain(
                downTime,
                eventTime,
                motionEvent,
                x,
                y,
                metaState
        );
    }


    private MotionEvent mockOnTouchJoysticks(JoyStickView view, GamePadConfig pState, boolean isRightController) {
        Controller controller;
        if (isRightController) {
            controller = rightController;
        } else {
            controller = leftController;
        }
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis() + 50;
        int metaState = 0;
//        int x = convertRange(0, 360, (int) view.startRange, (int) view.endRange, controller.getX());
//        int y = convertRange(0, 360, (int) view.startRange, (int) view.endRange, controller.getY());
        int motionEvent;
        if (controller.getX() == 0) {
            view.justShowTouch = false;
            motionEvent = MotionEvent.ACTION_UP;
        } else {
            view.justShowTouch = true;
            motionEvent = MotionEvent.ACTION_DOWN;
        }
        return MotionEvent.obtain(
                downTime,
                eventTime,
                motionEvent,
                controller.getX(),
                controller.getY(),
                metaState
        );
    }

    private int convertRange(int originalStart, int originalEnd, int newStart, int newEnd, int value) {
        double scale = (double) (newEnd - newStart) / (originalEnd - originalStart);
        return (int) (newStart + (value - originalStart) * scale);
    }
}
