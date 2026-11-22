package com.nongmay.camera.camera.overlays;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/** Horizon level from the gravity sensor (accelerometer fallback), low-pass filtered. */
public final class LevelSensor implements SensorEventListener {
    public interface Listener {
        /** roll in degrees relative to the nearest upright orientation (−45..45), pitch flag when flat. */
        void onLevel(float rollDeg, boolean flat);
    }

    private final SensorManager sm;
    private final Sensor sensor;
    private Listener listener;
    private float gx, gy, gz;
    private boolean init;

    public LevelSensor(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensor = g != null ? g : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public boolean available() {
        return sensor != null;
    }

    public void start(Listener l) {
        listener = l;
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
    }

    public void stop() {
        sm.unregisterListener(this);
        listener = null;
        init = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float a = init ? 0.2f : 1f;
        init = true;
        gx += a * (e.values[0] - gx);
        gy += a * (e.values[1] - gy);
        gz += a * (e.values[2] - gz);
        double norm = Math.sqrt(gx * gx + gy * gy + gz * gz);
        boolean flat = norm > 0 && Math.abs(gz) / norm > 0.85;
        double angle = Math.toDegrees(Math.atan2(gx, gy)); // 0 = portrait upright
        double rel = angle - 90 * Math.round(angle / 90.0);
        Listener l = listener;
        if (l != null) l.onLevel((float) rel, flat);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
