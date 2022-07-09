package com.daitj.scbtfix;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "scbtfix";
    private static HIDDeviceManager mHIDDeviceManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        HIDDeviceManager hIDDeviceManager = new HIDDeviceManager(this.getBaseContext());
        hIDDeviceManager.initialize(true);
    }

    /* access modifiers changed from: protected */
    public void onDestroy() {
        Log.v(TAG, "onDestroy()");
        HIDDeviceManager hIDDeviceManager = mHIDDeviceManager;
        if (hIDDeviceManager != null) {
            HIDDeviceManager.release(hIDDeviceManager);
            mHIDDeviceManager = null;
        }
        super.onDestroy();
    }


    /* access modifiers changed from: protected */
    public void onPause() {
        Log.v(TAG, "onPause()");
        super.onPause();
    }

    /* access modifiers changed from: protected */
    public void onResume() {
        Log.v(TAG, "onResume()");
        super.onResume();
    }


}