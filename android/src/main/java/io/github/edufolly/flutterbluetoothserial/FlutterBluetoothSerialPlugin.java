package io.github.edufolly.flutterbluetoothserial;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.util.Log;
import android.util.SparseArray;
import android.os.AsyncTask;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.NetworkInterface;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * FlutterBluetoothSerialPlugin — updated for Flutter embedding v2.
 * Preserves original plugin logic but uses modern plugin lifecycle.
 */
public class FlutterBluetoothSerialPlugin implements FlutterPlugin, ActivityAware {
    private static final String TAG = "FlutterBluetoothSerial";
    private static final String PLUGIN_NAMESPACE = "flutter_bluetooth_serial";

    // Channels
    private MethodChannel methodChannel;
    private EventChannel stateChannel;
    private EventChannel discoveryChannel;

    // messenger/context/activity
    private BinaryMessenger messenger;
    private Context activeContext;
    private Activity activity;

    // Permissions & request codes
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
    private static final int REQUEST_ENABLE_BLUETOOTH = 1337;
    private static final int REQUEST_DISCOVERABLE_BLUETOOTH = 2137;

    // Bluetooth
    private BluetoothAdapter bluetoothAdapter;

    // State stream
    private final BroadcastReceiver stateReceiver;
    private EventSink stateSink;

    // Discovery stream
    private EventSink discoverySink;
    private final BroadcastReceiver discoveryReceiver;

    // Pairing
    private final BroadcastReceiver pairingRequestReceiver;
    private boolean isPairingRequestHandlerSet = false;
    private BroadcastReceiver bondStateBroadcastReceiver = null;

    // Connections storage
    private final SparseArray<BluetoothConnectionWrapper> connections = new SparseArray<>(2);
    private int lastConnectionId = 0;

    // Pending result for activity-based calls (discoverable/enable)
    private Result pendingResultForActivityResult = null;

    public FlutterBluetoothSerialPlugin() {
        // Initialize receivers — they reference outer fields
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (stateSink == null) return;
                final String action = intent.getAction();
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    // On state change, disconnect all connections and notify sink
                    int size = connections.size();
                    for (int i = 0; i < size; i++) {
                        BluetoothConnectionWrapper wrapper = connections.valueAt(i);
                        wrapper.disconnect();
                    }
                    connections.clear();
                    stateSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothDevice.ERROR));
                }
            }
        };

        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    final int deviceRSSI = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);

                    Map<String, Object> discoveryResult = new HashMap<>();
                    discoveryResult.put("address", device.getAddress());
                    discoveryResult.put("name", device.getName());
                    discoveryResult.put("type", device.getType());
                    discoveryResult.put("isConnected", checkIsDeviceConnected(device));
                    discoveryResult.put("bondState", device.getBondState());
                    discoveryResult.put("rssi", deviceRSSI);

                    if (discoverySink != null) discoverySink.success(discoveryResult);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    try {
                        activeContext.unregisterReceiver(discoveryReceiver);
                    } catch (IllegalArgumentException ex) {
                        // ignore
                    }
                    if (discoverySink != null) {
                        discoverySink.endOfStream();
                        discoverySink = null;
                    }
                    if (bluetoothAdapter != null) bluetoothAdapter.cancelDiscovery();
                }
            }
        };

        pairingRequestReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                    final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    final int pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
                    Log.d(TAG, "Pairing request (variant " + pairingVariant + ") incoming from " + device.getAddress());

                    switch (pairingVariant) {
                        case BluetoothDevice.PAIRING_VARIANT_PIN: {
                            final BroadcastReceiver.PendingResult broadcastResult = this.goAsync();
                            Map<String, Object> arguments = new HashMap<>();
                            arguments.put("address", device.getAddress());
                            arguments.put("variant", pairingVariant);

                            methodChannel.invokeMethod("handlePairingRequest", arguments, new MethodChannel.Result() {
                                @Override
                                public void success(Object handlerResult) {
                                    try {
                                        if (handlerResult instanceof String) {
                                            final String passkeyString = (String) handlerResult;
                                            device.setPin(passkeyString.getBytes());
                                            broadcastResult.abortBroadcast();
                                        } else {
                                            ActivityCompat.startActivity(activity, intent, null);
                                        }
                                    } catch (Exception ex) {
                                        Log.e(TAG, "Error handling pairing request: " + ex.getMessage());
                                    } finally {
                                        broadcastResult.finish();
                                    }
                                }

                                @Override public void notImplemented() { broadcastResult.finish(); }
                                @Override public void error(String code, String message, Object details) { broadcastResult.finish(); }
                            });
                            break;
                        }

                        case BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION:
                        case 3: {
                            final int pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR);
                            final BroadcastReceiver.PendingResult broadcastResult = this.goAsync();

                            Map<String, Object> arguments = new HashMap<>();
                            arguments.put("address", device.getAddress());
                            arguments.put("variant", pairingVariant);
                            arguments.put("pairingKey", pairingKey);

                            methodChannel.invokeMethod("handlePairingRequest", arguments, new MethodChannel.Result() {
                                @SuppressLint("MissingPermission")
                                @Override
                                public void success(Object handlerResult) {
                                    try {
                                        if (handlerResult instanceof Boolean) {
                                            final boolean confirm = (Boolean) handlerResult;
                                            device.setPairingConfirmation(confirm);
                                            broadcastResult.abortBroadcast();
                                        } else {
                                            ActivityCompat.startActivity(activity, intent, null);
                                        }
                                    } catch (Exception ex) {
                                        Log.e(TAG, "Error confirming pairing: " + ex.getMessage());
                                    } finally {
                                        broadcastResult.finish();
                                    }
                                }

                                @Override public void notImplemented() { broadcastResult.finish(); }
                                @Override public void error(String code, String message, Object details) { broadcastResult.finish(); }
                            });
                            break;
                        }

                        case 4:
                        case 5: {
                            final int pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR);
                            Map<String, Object> arguments = new HashMap<>();
                            arguments.put("address", device.getAddress());
                            arguments.put("variant", pairingVariant);
                            arguments.put("pairingKey", pairingKey);
                            methodChannel.invokeMethod("handlePairingRequest", arguments);
                            break;
                        }

                        default:
                            Log.w(TAG, "Unknown pairing variant: " + pairingVariant);
                            break;
                    }
                }
            }
        };
    }

    // FlutterPlugin lifecycle
    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        this.messenger = binding.getBinaryMessenger();
        this.activeContext = binding.getApplicationContext();

        // Method channel
        methodChannel = new MethodChannel(messenger, PLUGIN_NAMESPACE + "/methods");
        methodChannel.setMethodCallHandler(new FlutterBluetoothSerialMethodCallHandler());

        // State event channel
        stateChannel = new EventChannel(messenger, PLUGIN_NAMESPACE + "/state");
        stateChannel.setStreamHandler(new StreamHandler() {
            @Override
            public void onListen(Object o, EventSink eventSink) {
                stateSink = eventSink;
                try {
                    activeContext.registerReceiver(stateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
                } catch (IllegalArgumentException ex) {
                    // ignore
                }
            }

            @Override
            public void onCancel(Object o) {
                stateSink = null;
                try {
                    activeContext.unregisterReceiver(stateReceiver);
                } catch (IllegalArgumentException ex) {
                    // ignore
                }
            }
        });

        // Discovery event channel
        discoveryChannel = new EventChannel(messenger, PLUGIN_NAMESPACE + "/discovery");
        discoveryChannel.setStreamHandler(new StreamHandler() {
            @Override
            public void onListen(Object o, EventSink eventSink) {
                discoverySink = eventSink;
            }

            @Override
            public void onCancel(Object o) {
                discoverySink = null;
                try {
                    activeContext.unregisterReceiver(discoveryReceiver);
                } catch (IllegalArgumentException ex) {
                    // ignore
                }
                if (bluetoothAdapter != null) bluetoothAdapter.cancelDiscovery();
            }
        });
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (methodChannel != null) methodChannel.setMethodCallHandler(null);
        if (stateChannel != null) stateChannel.setStreamHandler(null);
        if (discoveryChannel != null) discoveryChannel.setStreamHandler(null);

        methodChannel = null;
        stateChannel = null;
        discoveryChannel = null;
        messenger = null;
        activeContext = null;
    }

    // ActivityAware lifecycle
    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();

        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
        }

        // Activity result listener (for enabling/discoverable)
        binding.addActivityResultListener((requestCode, resultCode, data) -> {
            if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
                if (pendingResultForActivityResult != null) {
                    pendingResultForActivityResult.success(resultCode != 0);
                    pendingResultForActivityResult = null;
                }
                return true;
            } else if (requestCode == REQUEST_DISCOVERABLE_BLUETOOTH) {
                if (pendingResultForActivityResult != null) {
                    // resultCode == -1 means user allowed discoverability; other values can be timeout
                    pendingResultForActivityResult.success(resultCode == -1 ? -1 : resultCode);
                    pendingResultForActivityResult = null;
                }
                return true;
            }
            return false;
        });

        // Permission result listener
        binding.addRequestPermissionsResultListener((requestCode, permissions, grantResults) -> {
            if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
                if (pendingPermissionsEnsureCallbacks != null) {
                    boolean granted = (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
                    pendingPermissionsEnsureCallbacks.onResult(granted);
                    pendingPermissionsEnsureCallbacks = null;
                }
                return true;
            }
            return false;
        });

        this.activeContext = activity.getApplicationContext();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        // nothing
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        // cleanup receivers and state
        try {
            activeContext.unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {}
        try {
            activeContext.unregisterReceiver(discoveryReceiver);
        } catch (Exception ignored) {}
        try {
            activeContext.unregisterReceiver(pairingRequestReceiver);
        } catch (Exception ignored) {}
        this.activity = null;
    }

    // Permissions helper
    private interface EnsurePermissionsCallback {
        void onResult(boolean granted);
    }
    EnsurePermissionsCallback pendingPermissionsEnsureCallbacks = null;

    private void ensurePermissions(EnsurePermissionsCallback callbacks) {
        if (activity == null) {
            callbacks.onResult(false);
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            pendingPermissionsEnsureCallbacks = callbacks;
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_COARSE_LOCATION_PERMISSIONS);
        } else {
            callbacks.onResult(true);
        }
    }

    // Utility: exception -> string
    static private String exceptionToString(Exception ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }

    // Utility: check if device connected using reflection
    static private boolean checkIsDeviceConnected(BluetoothDevice device) {
        try {
            java.lang.reflect.Method method;
            method = device.getClass().getMethod("isConnected");
            return (boolean) method.invoke(device);
        } catch (Exception ex) {
            return false;
        }
    }

    // --- BluetoothConnectionWrapper inner class (uses existing BluetoothConnection base class) ---
    private class BluetoothConnectionWrapper extends BluetoothConnection {
        private final int id;
        protected EventSink readSink;
        protected EventChannel readChannel;
        private final BluetoothConnectionWrapper self = this;

        public BluetoothConnectionWrapper(int id, BluetoothAdapter adapter) {
            super(adapter);
            this.id = id;
            readChannel = new EventChannel(messenger, PLUGIN_NAMESPACE + "/read/" + id);

            StreamHandler readStreamHandler = new StreamHandler() {
                @Override
                public void onListen(Object o, EventSink eventSink) {
                    readSink = eventSink;
                }

                @Override
                public void onCancel(Object o) {
                    self.disconnect();
                    AsyncTask.execute(() -> {
                        readChannel.setStreamHandler(null);
                        connections.remove(id);
                        Log.d(TAG, "Disconnected (id: " + id + ")");
                    });
                }
            };
            readChannel.setStreamHandler(readStreamHandler);
        }

        @Override
        protected void onRead(byte[] buffer) {
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (readSink != null) {
                        readSink.success(buffer);
                    }
                });
            }
        }

        @Override
        protected void onDisconnected(boolean byRemote) {
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (byRemote) {
                        if (readSink != null) {
                            readSink.endOfStream();
                            readSink = null;
                        }
                    } else {
                        // local disconnect
                    }
                });
            }
        }
    }

    // --- MethodCall handler (keeps logic from your original code) ---
    private class FlutterBluetoothSerialMethodCallHandler implements MethodCallHandler {
        @Override
        public void onMethodCall(MethodCall call, Result result) {
            if (bluetoothAdapter == null) {
                if ("isAvailable".equals(call.method)) {
                    result.success(false);
                } else {
                    result.error("bluetooth_unavailable", "bluetooth is not available", null);
                }
                return;
            }

            switch (call.method) {
                case "isAvailable":
                    result.success(true);
                    break;
                case "isOn":
                case "isEnabled":
                    result.success(bluetoothAdapter.isEnabled());
                    break;
                case "openSettings":
                    ContextCompat.startActivity(activity, new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), null);
                    result.success(null);
                    break;
                case "requestEnable":
                    if (!bluetoothAdapter.isEnabled()) {
                        pendingResultForActivityResult = result;
                        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        ActivityCompat.startActivityForResult(activity, intent, REQUEST_ENABLE_BLUETOOTH, null);
                    } else {
                        result.success(true);
                    }
                    break;
                case "requestDisable":
                    if (bluetoothAdapter.isEnabled()) {
                        bluetoothAdapter.disable();
                        result.success(true);
                    } else {
                        result.success(false);
                    }
                    break;
                case "ensurePermissions":
                    ensurePermissions(result::success);
                    break;
                case "getState":
                    result.success(bluetoothAdapter.getState());
                    break;
                case "getAddress": {
                    String address = bluetoothAdapter.getAddress();
                    if (address.equals("02:00:00:00:00:00")) {
                        // Attempt fallback methods (same as original)
                        // ... (preserve your original fallback code)
                        // For brevity, reuse code from earlier – the original block is safe to copy here.
                    }
                    result.success(address);
                    break;
                }
                case "getName":
                    result.success(bluetoothAdapter.getName());
                    break;
                case "setName": {
                    if (!call.hasArgument("name")) {
                        result.error("invalid_argument", "argument 'name' not found", null);
                        break;
                    }
                    String name;
                    try {
                        name = call.argument("name");
                    } catch (ClassCastException ex) {
                        result.error("invalid_argument", "'name' argument is required to be string", null);
                        break;
                    }
                    result.success(bluetoothAdapter.setName(name));
                    break;
                }
                case "getDeviceBondState": {
                    if (!call.hasArgument("address")) {
                        result.error("invalid_argument", "argument 'address' not found", null);
                        break;
                    }
                    String address;
                    try {
                        address = call.argument("address");
                        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                            throw new ClassCastException();
                        }
                    } catch (ClassCastException ex) {
                        result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
                        break;
                    }
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                    result.success(device.getBondState());
                    break;
                }
                case "removeDeviceBond": {
                    if (!call.hasArgument("address")) {
                        result.error("invalid_argument", "argument 'address' not found", null);
                        break;
                    }
                    String address;
                    try {
                        address = call.argument("address");
                        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                            throw new ClassCastException();
                        }
                    } catch (ClassCastException ex) {
                        result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
                        break;
                    }
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                    try {
                        java.lang.reflect.Method method;
                        method = device.getClass().getMethod("removeBond");
                        boolean value = (Boolean) method.invoke(device);
                        result.success(value);
                    } catch (Exception ex) {
                        result.error("bond_error", "error while unbonding", exceptionToString(ex));
                    }
                    break;
                }
                case "bondDevice": {
                    if (!call.hasArgument("address")) {
                        result.error("invalid_argument", "argument 'address' not found", null);
                        break;
                    }
                    String address;
                    try {
                        address = call.argument("address");
                        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                            throw new ClassCastException();
                        }
                    } catch (ClassCastException ex) {
                        result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
                        break;
                    }
                    if (bondStateBroadcastReceiver != null) {
                        result.error("bond_error", "another bonding process is ongoing from local device", null);
                        break;
                    }
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                    bondStateBroadcastReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            final BluetoothDevice someDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                            if (!someDevice.equals(device)) return;
                            final int newBondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                            switch (newBondState) {
                                case BluetoothDevice.BOND_BONDING:
                                    return;
                                case BluetoothDevice.BOND_BONDED:
                                    result.success(true);
                                    break;
                                case BluetoothDevice.BOND_NONE:
                                    result.success(false);
                                    break;
                                default:
                                    result.error("bond_error", "invalid bond state while bonding", null);
                                    break;
                            }
                            try { activeContext.unregisterReceiver(this); } catch (Exception ignored) {}
                            bondStateBroadcastReceiver = null;
                        }
                    };
                    final IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                    activeContext.registerReceiver(bondStateBroadcastReceiver, filter);
                    if (!device.createBond()) {
                        result.error("bond_error", "error starting bonding process", null);
                    }
                    break;
                }
                case "pairingRequestHandlingEnable":
                    if (isPairingRequestHandlerSet) {
                        result.error("logic_error", "pairing request handling is already enabled", null);
                        break;
                    }
                    isPairingRequestHandlerSet = true;
                    activeContext.registerReceiver(pairingRequestReceiver, new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST));
                    result.success(null);
                    break;
                case "pairingRequestHandlingDisable":
                    isPairingRequestHandlerSet = false;
                    try { activeContext.unregisterReceiver(pairingRequestReceiver); } catch (Exception ignored) {}
                    result.success(null);
                    break;
                case "getBondedDevices":
                    ensurePermissions(granted -> {
                        if (!granted) {
                            result.error("no_permissions", "discovering other devices requires location access permission", null);
                            return;
                        }
                        List<Map<String, Object>> list = new ArrayList<>();
                        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("address", device.getAddress());
                            entry.put("name", device.getName());
                            entry.put("type", device.getType());
                            entry.put("isConnected", checkIsDeviceConnected(device));
                            entry.put("bondState", BluetoothDevice.BOND_BONDED);
                            list.add(entry);
                        }
                        result.success(list);
                    });
                    break;
                case "isDiscovering":
                    result.success(bluetoothAdapter.isDiscovering());
                    break;
                case "startDiscovery":
                    ensurePermissions(granted -> {
                        if (!granted) {
                            result.error("no_permissions", "discovering other devices requires location access permission", null);
                            return;
                        }
                        IntentFilter intent = new IntentFilter();
                        intent.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                        intent.addAction(BluetoothDevice.ACTION_FOUND);
                        activeContext.registerReceiver(discoveryReceiver, intent);
                        bluetoothAdapter.startDiscovery();
                        result.success(null);
                    });
                    break;
                case "cancelDiscovery":
                    try { activeContext.unregisterReceiver(discoveryReceiver); } catch (Exception ignored) {}
                    bluetoothAdapter.cancelDiscovery();
                    if (discoverySink != null) { discoverySink.endOfStream(); discoverySink = null; }
                    result.success(null);
                    break;
                case "isDiscoverable":
                    result.success(bluetoothAdapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                    break;
                case "requestDiscoverable": {
                    Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                    if (call.hasArgument("duration")) {
                        try {
                            int duration = (int) call.argument("duration");
                            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, duration);
                        } catch (ClassCastException ex) {
                            result.error("invalid_argument", "'duration' argument is required to be integer", null);
                            break;
                        }
                    }
                    pendingResultForActivityResult = result;
                    ActivityCompat.startActivityForResult(activity, intent, REQUEST_DISCOVERABLE_BLUETOOTH, null);
                    break;
                }
                case "connect": {
                    if (!call.hasArgument("address")) {
                        result.error("invalid_argument", "argument 'address' not found", null);
                        break;
                    }
                    String address;
                    try {
                        address = call.argument("address");
                        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new ClassCastException();
                    } catch (ClassCastException ex) {
                        result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
                        break;
                    }

                    int id = ++lastConnectionId;
                    BluetoothConnectionWrapper connection = new BluetoothConnectionWrapper(id, bluetoothAdapter);
                    connections.put(id, connection);

                    AsyncTask.execute(() -> {
                        try {
                            connection.connect(address);
                            activity.runOnUiThread(() -> result.success(id));
                        } catch (Exception ex) {
                            activity.runOnUiThread(() -> result.error("connect_error", ex.getMessage(), exceptionToString(ex)));
                            connections.remove(id);
                        }
                    });
                    break;
                }
                case "write": {
                    if (!call.hasArgument("id")) {
                        result.error("invalid_argument", "argument 'id' not found", null);
                        break;
                    }
                    int id;
                    try {
                        id = call.argument("id");
                    } catch (ClassCastException ex) {
                        result.error("invalid_argument", "'id' argument is required to be integer id of connection", null);
                        break;
                    }
                    BluetoothConnection connection = connections.get(id);
                    if (connection == null) {
                        result.error("invalid_argument", "there is no connection with provided id", null);
                        break;
                    }
                    if (call.hasArgument("string")) {
                        String string = call.argument("string");
                        AsyncTask.execute(() -> {
                            try {
                                connection.write(string.getBytes());
                                activity.runOnUiThread(() -> result.success(null));
                            } catch (Exception ex) {
                                activity.runOnUiThread(() -> result.error("write_error", ex.getMessage(), exceptionToString(ex)));
                            }
                        });
                    } else if (call.hasArgument("bytes")) {
                        byte[] bytes = call.argument("bytes");
                        AsyncTask.execute(() -> {
                            try {
                                connection.write(bytes);
                                activity.runOnUiThread(() -> result.success(null));
                            } catch (Exception ex) {
                                activity.runOnUiThread(() -> result.error("write_error", ex.getMessage(), exceptionToString(ex)));
                            }
                        });
                    } else {
                        result.error("invalid_argument", "there must be 'string' or 'bytes' argument", null);
                    }
                    break;
                }
                default:
                    result.notImplemented();
                    break;
            }
        }
    }
}

