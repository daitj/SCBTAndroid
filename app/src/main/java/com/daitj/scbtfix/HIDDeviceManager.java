package com.daitj.scbtfix;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

@SuppressLint("MissingPermission")
public class HIDDeviceManager {
    private static final String TAG = "hidapi";

    private static HIDDeviceManager sManager;
    private static int sManagerRefCount = 0;

    public static HIDDeviceManager acquire(Context context) {
        if (sManagerRefCount == 0) {
            sManager = new HIDDeviceManager(context);
        }
        ++sManagerRefCount;
        return sManager;
    }

    public static void release(HIDDeviceManager manager) {
        if (manager == sManager) {
            --sManagerRefCount;
            if (sManagerRefCount == 0) {
                sManager.close();
                sManager = null;
            }
        }
    }

    private Context mContext;
    private HashMap<Integer, HIDDevice> mDevicesById = new HashMap<Integer, HIDDevice>();
    private HashMap<BluetoothDevice, HIDDeviceBLESteamController> mBluetoothDevices = new HashMap<BluetoothDevice, HIDDeviceBLESteamController>();
    private int mNextDeviceId = 0;
    private SharedPreferences mSharedPreferences = null;
    private boolean mIsChromebook = false;
    private Handler mHandler;
    private BluetoothManager mBluetoothManager;
    private List<BluetoothDevice> mLastBluetoothDevices;

    private final BroadcastReceiver mBluetoothBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // Bluetooth device was connected. If it was a Steam Controller, handle it
            if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Log.d(TAG, "Bluetooth device connected: " + device);

                if (isSteamController(device)) {
                    connectBluetoothDevice(device);
                }
            }

            // Bluetooth device was disconnected, remove from controller manager (if any)
            if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Log.d(TAG, "Bluetooth device disconnected: " + device);

                disconnectBluetoothDevice(device);
            }
        }
    };

    public HIDDeviceManager(final Context context) {
        mContext = context;

        HIDDeviceRegisterCallback();

        mSharedPreferences = mContext.getSharedPreferences("hidapi", Context.MODE_PRIVATE);
        mIsChromebook = mContext.getPackageManager().hasSystemFeature("org.chromium.arc.device_management");

//        if (shouldClear) {
//            SharedPreferences.Editor spedit = mSharedPreferences.edit();
//            spedit.clear();
//            spedit.commit();
//        }
//        else
        {
            mNextDeviceId = mSharedPreferences.getInt("next_device_id", 0);
        }
    }

    public Context getContext() {
        return mContext;
    }

    public int getDeviceIDForIdentifier(String identifier) {
        SharedPreferences.Editor spedit = mSharedPreferences.edit();

        int result = mSharedPreferences.getInt(identifier, 0);
        if (result == 0) {
            result = mNextDeviceId++;
            spedit.putInt("next_device_id", mNextDeviceId);
        }

        spedit.putInt(identifier, result);
        spedit.commit();
        return result;
    }

    private void initializeBluetooth() {
        Log.d(TAG, "Initializing Bluetooth");

        if (Build.VERSION.SDK_INT <= 30 &&
                mContext.getPackageManager().checkPermission(android.Manifest.permission.BLUETOOTH, mContext.getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Couldn't initialize Bluetooth, missing android.permission.BLUETOOTH");
            return;
        }

        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || (Build.VERSION.SDK_INT < 18)) {
            Log.d(TAG, "Couldn't initialize Bluetooth, this version of Android does not support Bluetooth LE");
            return;
        }

        // Find bonded bluetooth controllers and create SteamControllers for them
        mBluetoothManager = (BluetoothManager)mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            // This device doesn't support Bluetooth.
            return;
        }

        BluetoothAdapter btAdapter = mBluetoothManager.getAdapter();
        if (btAdapter == null) {
            // This device has Bluetooth support in the codebase, but has no available adapters.
            return;
        }

        // Get our bonded devices.
        for (BluetoothDevice device : btAdapter.getBondedDevices()) {

            Log.d(TAG, "Bluetooth device available: " + device);
            if (isSteamController(device)) {
                connectBluetoothDevice(device);
            }

        }

        // NOTE: These don't work on Chromebooks, to my undying dismay.
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        mContext.registerReceiver(mBluetoothBroadcast, filter);

        if (mIsChromebook) {
            mHandler = new Handler(Looper.getMainLooper());
            mLastBluetoothDevices = new ArrayList<BluetoothDevice>();

            // final HIDDeviceManager finalThis = this;
            // mHandler.postDelayed(new Runnable() {
            //     @Override
            //     public void run() {
            //         finalThis.chromebookConnectionHandler();
            //     }
            // }, 5000);
        }
    }

    private void shutdownBluetooth() {
        try {
            mContext.unregisterReceiver(mBluetoothBroadcast);
        } catch (Exception e) {
            // We may not have registered, that's okay
        }
    }

    // Chromebooks do not pass along ACTION_ACL_CONNECTED / ACTION_ACL_DISCONNECTED properly.
    // This function provides a sort of dummy version of that, watching for changes in the
    // connected devices and attempting to add controllers as things change.
    public void chromebookConnectionHandler() {
        if (!mIsChromebook) {
            return;
        }

        ArrayList<BluetoothDevice> disconnected = new ArrayList<BluetoothDevice>();
        ArrayList<BluetoothDevice> connected = new ArrayList<BluetoothDevice>();

        List<BluetoothDevice> currentConnected = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);

        for (BluetoothDevice bluetoothDevice : currentConnected) {
            if (!mLastBluetoothDevices.contains(bluetoothDevice)) {
                connected.add(bluetoothDevice);
            }
        }
        for (BluetoothDevice bluetoothDevice : mLastBluetoothDevices) {
            if (!currentConnected.contains(bluetoothDevice)) {
                disconnected.add(bluetoothDevice);
            }
        }

        mLastBluetoothDevices = currentConnected;

        for (BluetoothDevice bluetoothDevice : disconnected) {
            disconnectBluetoothDevice(bluetoothDevice);
        }
        for (BluetoothDevice bluetoothDevice : connected) {
            connectBluetoothDevice(bluetoothDevice);
        }

        final HIDDeviceManager finalThis = this;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finalThis.chromebookConnectionHandler();
            }
        }, 10000);
    }

    public boolean connectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        Log.v(TAG, "connectBluetoothDevice device=" + bluetoothDevice);
        synchronized (this) {
            if (mBluetoothDevices.containsKey(bluetoothDevice)) {
                Log.v(TAG, "Steam controller with address " + bluetoothDevice + " already exists, attempting reconnect");

                HIDDeviceBLESteamController device = mBluetoothDevices.get(bluetoothDevice);
                device.reconnect();

                return false;
            }
            HIDDeviceBLESteamController device = new HIDDeviceBLESteamController(this, bluetoothDevice);
            int id = device.getId();
            mBluetoothDevices.put(bluetoothDevice, device);
            mDevicesById.put(id, device);

            // The Steam Controller will mark itself connected once initialization is complete
        }
        return true;
    }

    public void disconnectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            HIDDeviceBLESteamController device = mBluetoothDevices.get(bluetoothDevice);
            if (device == null)
                return;

            int id = device.getId();
            mBluetoothDevices.remove(bluetoothDevice);
            mDevicesById.remove(id);
            device.shutdown();
            HIDDeviceDisconnected(id);
        }
    }

    public boolean isSteamController(BluetoothDevice bluetoothDevice) {
        // Sanity check.  If you pass in a null device, by definition it is never a Steam Controller.
        if (bluetoothDevice == null) {
            return false;
        }

        // If the device has no local name, we really don't want to try an equality check against it.
        if (bluetoothDevice.getName() == null) {
            return false;
        }

        return bluetoothDevice.getName().equals("SteamController") && ((bluetoothDevice.getType() & BluetoothDevice.DEVICE_TYPE_LE) != 0);
    }

    private void close() {
        shutdownBluetooth();
        synchronized (this) {
            for (HIDDevice device : mDevicesById.values()) {
                device.shutdown();
            }
            mDevicesById.clear();
            mBluetoothDevices.clear();
            HIDDeviceReleaseCallback();
        }
    }

    public void setFrozen(boolean frozen) {
        synchronized (this) {
            for (HIDDevice device : mDevicesById.values()) {
                device.setFrozen(frozen);
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    private HIDDevice getDevice(int id) {
        synchronized (this) {
            HIDDevice result = mDevicesById.get(id);
            if (result == null) {
                Log.v(TAG, "No device for id: " + id);
                Log.v(TAG, "Available devices: " + mDevicesById.keySet());
            }
            return result;
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////// JNI interface functions
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    public boolean initialize(boolean bluetooth) {
        Log.v(TAG, "initialize(" + bluetooth + ")");

        if (bluetooth) {
            initializeBluetooth();
        }
        return true;
    }

    public boolean openDevice(int deviceID) {
        Log.v(TAG, "openDevice deviceID=" + deviceID);
        HIDDevice device = getDevice(deviceID);
        if (device == null) {
            HIDDeviceDisconnected(deviceID);
            return false;
        }
        try {
            return device.open();
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    public int sendOutputReport(int deviceID, byte[] report) {
        try {
            //Log.v(TAG, "sendOutputReport deviceID=" + deviceID + " length=" + report.length);
            HIDDevice device;
            device = getDevice(deviceID);
            if (device == null) {
                HIDDeviceDisconnected(deviceID);
                return -1;
            }

            return device.sendOutputReport(report);
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
        return -1;
    }

    public int sendFeatureReport(int deviceID, byte[] report) {
        try {
            //Log.v(TAG, "sendFeatureReport deviceID=" + deviceID + " length=" + report.length);
            HIDDevice device;
            device = getDevice(deviceID);
            if (device == null) {
                HIDDeviceDisconnected(deviceID);
                return -1;
            }

            return device.sendFeatureReport(report);
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
        return -1;
    }

    public boolean getFeatureReport(int deviceID, byte[] report) {
        try {
            //Log.v(TAG, "getFeatureReport deviceID=" + deviceID);
            HIDDevice device;
            device = getDevice(deviceID);
            if (device == null) {
                HIDDeviceDisconnected(deviceID);
                return false;
            }

            return device.getFeatureReport(report);
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    public void closeDevice(int deviceID) {
        try {
            Log.v(TAG, "closeDevice deviceID=" + deviceID);
            HIDDevice device;
            device = getDevice(deviceID);
            if (device == null) {
                HIDDeviceDisconnected(deviceID);
                return;
            }

            device.close();
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
    }


    private void HIDDeviceRegisterCallback() {
        Log.v(TAG, "HIDDeviceRegisterCallback");

    }

    private void HIDDeviceReleaseCallback() {
        Log.v(TAG, "HIDDeviceReleaseCallback");
    }

    void HIDDeviceConnected(int deviceID, String identifier, int vendorId, int productId, String serial_number, int release_number, String manufacturer_string, String product_string, int interface_number, int interface_class, int interface_subclass, int interface_protocol) {
        Log.v(TAG, "HIDDeviceConnected deviceID: "+ deviceID + " identifier: "+ identifier);

    }

    void HIDDeviceOpenPending(int deviceID) {

    }

    void HIDDeviceOpenResult(int deviceID, boolean opened) {

    }

    void HIDDeviceDisconnected(int deviceID) {
        Log.v(TAG, "HIDDeviceDisconnected deviceID: "+ deviceID);

    }

    void HIDDeviceInputReport(int deviceID, byte[] report) {
        Log.v(TAG, "HIDDeviceInputReport deviceID: "+ deviceID);
    }

    void HIDDeviceFeatureReport(int deviceID, byte[] report) {

    }
}
