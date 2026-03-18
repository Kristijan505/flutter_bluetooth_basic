package com.tablemi.flutter_bluetooth_basic;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

public class FlutterBluetoothBasicPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware, RequestPermissionsResultListener {
    private static final String TAG = "BluetoothBasicPlugin";
    private static final String NAMESPACE = "flutter_bluetooth_basic";
    private static final int REQUEST_SCAN_PERMISSIONS = 1451;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTED = 1;
    private static final long CONNECT_TIMEOUT_MS = 12_000L;
    private static final long WRITE_TIMEOUT_MS = 60_000L;
    private static final long WRITE_RETRY_DELAY_MS = 250L;
    private static final long CHUNK_PAUSE_MS = 20L;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Object connectionLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();

    private Activity activity;
    private ActivityPluginBinding activityPluginBinding;
    private MethodChannel channel;
    private EventChannel stateChannel;
    private BluetoothAdapter bluetoothAdapter;

    private volatile BluetoothSocket activeSocket;
    private volatile OutputStream activeOutputStream;
    private volatile String connectedAddress;
    private volatile boolean connected;
    private volatile boolean scanning;
    private volatile boolean scanReceiverRegistered;

    private final Set<String> seenScanAddresses = new HashSet<>();
    private final Map<String, BluetoothDevice> discoveredDevices = new LinkedHashMap<>();

    private Result pendingPermissionResult;
    private PendingOperation pendingOperation = PendingOperation.NONE;
    private String pendingAddress;
    private String pendingPermissionDeniedMessage;

    private EventSink stateSink;

    private enum PendingOperation {
        NONE,
        SCAN,
        CONNECT
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                final BluetoothDevice device = getParcelableExtraCompat(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (device != null) {
                    emitScanDevice(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                unregisterScanReceiver();
            }
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (adapterState == BluetoothAdapter.STATE_OFF || adapterState == BluetoothAdapter.STATE_TURNING_OFF) {
                    connected = false;
                    synchronized (connectionLock) {
                        closeActiveSocketLocked();
                    }
                    emitState(STATE_DISCONNECTED);
                } else if (adapterState == BluetoothAdapter.STATE_ON && connected) {
                    emitState(STATE_CONNECTED);
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                emitState(STATE_CONNECTED);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                connected = false;
                synchronized (connectionLock) {
                    closeActiveSocketLocked();
                }
                emitState(STATE_DISCONNECTED);
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                final int connectionState = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1);
                if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                    emitState(STATE_CONNECTED);
                } else if (connectionState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false;
                    synchronized (connectionLock) {
                        closeActiveSocketLocked();
                    }
                    emitState(STATE_DISCONNECTED);
                }
            }
        }
    };

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (bluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "Bluetooth is unavailable", null);
            return;
        }

        final Map<String, Object> args = call.arguments();

        switch (call.method) {
            case "state":
                state(result);
                break;
            case "isAvailable":
                result.success(bluetoothAdapter != null);
                break;
            case "isOn":
                result.success(bluetoothAdapter.isEnabled());
                break;
            case "isConnected":
                result.success(connected);
                break;
            case "startScan":
                ensureScanPermissions(result);
                break;
            case "stopScan":
                stopScan();
                result.success(null);
                break;
            case "connect":
                connect(args, result);
                break;
            case "disconnect":
                disconnect();
                result.success(true);
                break;
            case "destroy":
                destroy();
                result.success(true);
                break;
            case "writeData":
                writeData(args, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void state(Result result) {
        try {
            result.success(bluetoothAdapter.getState());
        } catch (SecurityException e) {
            result.error("invalid_argument", "Unable to read bluetooth state", null);
        }
    }

    private boolean hasScanPermissions() {
        if (activity == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermissions() {
        if (activity == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    private void ensureScanPermissions(Result result) {
        if (activity == null) {
            result.error("no_activity", "Cannot request permissions because no Activity is attached.", null);
            return;
        }

        if (hasScanPermissions()) {
            startScan(result);
            return;
        }

        final String[] requiredPermissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
            pendingPermissionDeniedMessage = "This app requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions for scanning";
        } else {
            requiredPermissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            pendingPermissionDeniedMessage = "This app requires location permissions for scanning on Android 11 and below";
        }

        pendingOperation = PendingOperation.SCAN;
        pendingPermissionResult = result;
        ActivityCompat.requestPermissions(activity, requiredPermissions, REQUEST_SCAN_PERMISSIONS);
    }

    private void ensureConnectPermissions(String address, Result result) {
        if (activity == null) {
            result.error("no_activity", "Cannot request permissions because no Activity is attached.", null);
            return;
        }

        if (hasConnectPermissions()) {
            connectInternal(address, result);
            return;
        }

        pendingOperation = PendingOperation.CONNECT;
        pendingAddress = address;
        pendingPermissionResult = result;
        pendingPermissionDeniedMessage = "This app requires BLUETOOTH_CONNECT permission for connecting";
        ActivityCompat.requestPermissions(
                activity,
                new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                REQUEST_SCAN_PERMISSIONS
        );
    }

    private void startScan(Result result) {
        Log.d(TAG, "start scan");
        synchronized (connectionLock) {
            seenScanAddresses.clear();
            discoveredDevices.clear();
        }

        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            registerScanReceiver();
            emitBondedDevices();
            scanning = bluetoothAdapter.startDiscovery();
            result.success(null);
        } catch (SecurityException e) {
            result.error("no_permissions", "Missing bluetooth permissions for scan", null);
        } catch (Exception e) {
            result.error("startScan", e.getMessage(), null);
        }
    }

    private void stopScan() {
        scanning = false;
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // best effort
        }
        unregisterScanReceiver();
    }

    private void emitBondedDevices() {
        try {
            for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                emitScanDevice(device);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to read bonded bluetooth devices", e);
        }
    }

    private void emitScanDevice(BluetoothDevice device) {
        if (device == null) {
            return;
        }

        final String address = device.getAddress();
        if (address == null) {
            return;
        }

        synchronized (connectionLock) {
            if (!seenScanAddresses.add(address)) {
                discoveredDevices.put(address, device);
                return;
            }
            discoveredDevices.put(address, device);
        }

        final Map<String, Object> payload = new HashMap<>();
        payload.put("address", address);
        payload.put("name", device.getName());
        payload.put("type", device.getType());

        if (channel != null) {
            mainHandler.post(() -> {
                if (channel != null) {
                    channel.invokeMethod("ScanResult", payload);
                }
            });
        }
    }

    private void registerScanReceiver() {
        if (scanReceiverRegistered || activity == null) {
            return;
        }

        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(activity, scanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        scanReceiverRegistered = true;
    }

    private void unregisterScanReceiver() {
        if (!scanReceiverRegistered || activity == null) {
            return;
        }

        try {
            activity.unregisterReceiver(scanReceiver);
        } catch (IllegalArgumentException ignored) {
            // receiver already unregistered
        }
        scanReceiverRegistered = false;
    }

    @SuppressWarnings("deprecation")
    private static <T extends Parcelable> T getParcelableExtraCompat(Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, clazz);
        }
        return intent.getParcelableExtra(key);
    }

    private void connect(Map<String, Object> args, Result result) {
        if (args == null || !args.containsKey("address")) {
            result.error("invalid_argument", "Argument 'address' not found", null);
            return;
        }

        final String address = String.valueOf(args.get("address"));
        ensureConnectPermissions(address, result);
    }

    private void connectInternal(final String address, final Result result) {
        ioExecutor.execute(() -> {
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicReference<BluetoothSocket> socketRef = new AtomicReference<>();
            final ScheduledFuture<?> timeoutFuture = timeoutExecutor.schedule(() -> {
                if (completed.compareAndSet(false, true)) {
                    closeSocket(socketRef.get());
                    postError(result, "connect_timeout", "Timed out connecting to printer");
                }
            }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            try {
                disconnect();
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                final BluetoothSocket socket = createSocket(device);
                socketRef.set(socket);
                socket.connect();

                if (!completed.compareAndSet(false, true)) {
                    closeSocket(socket);
                    return;
                }

                synchronized (connectionLock) {
                    activeSocket = socket;
                    activeOutputStream = socket.getOutputStream();
                    connectedAddress = address;
                    connected = true;
                }
                emitState(STATE_CONNECTED);
                postSuccess(result, true);
            } catch (IOException | SecurityException e) {
                if (completed.compareAndSet(false, true)) {
                    closeSocket(socketRef.get());
                    postError(result, classifyConnectError(e), e.getMessage());
                }
            } finally {
                timeoutFuture.cancel(true);
            }
        });
    }

    private BluetoothSocket createSocket(BluetoothDevice device) throws IOException {
        try {
            return device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
        } catch (IOException | SecurityException firstError) {
            try {
                return device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException | SecurityException secondError) {
                if (secondError instanceof IOException) {
                    throw (IOException) secondError;
                }
                throw new IOException(secondError);
            }
        }
    }

    private void disconnect() {
        stopScan();
        synchronized (connectionLock) {
            closeActiveSocketLocked();
        }
        connected = false;
        connectedAddress = null;
        emitState(STATE_DISCONNECTED);
    }

    private boolean reconnectForWrite() {
        final String address;
        synchronized (connectionLock) {
            address = connectedAddress;
        }

        if (address == null) {
            return false;
        }

        synchronized (connectionLock) {
            closeActiveSocketLocked();
        }

        try {
            final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            final BluetoothSocket socket = createSocket(device);
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            socket.connect();
            synchronized (connectionLock) {
                activeSocket = socket;
                activeOutputStream = socket.getOutputStream();
                connectedAddress = address;
                connected = true;
            }
            emitState(STATE_CONNECTED);
            return true;
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Reconnect failed", e);
            synchronized (connectionLock) {
                closeActiveSocketLocked();
            }
            connected = false;
            return false;
        }
    }

    private void writeData(Map<String, Object> args, Result result) {
        if (args == null || !args.containsKey("bytes")) {
            result.error("bytes_empty", "Bytes param is empty", null);
            return;
        }

        final byte[] data = toByteArray(args.get("bytes"));
        if (data.length == 0) {
            result.error("bytes_empty", "Bytes param is empty", null);
            return;
        }

        if (!connected || connectedAddress == null) {
            result.error("device_disconnected", "Printer is not connected", null);
            return;
        }

        ioExecutor.execute(() -> {
            final AtomicBoolean completed = new AtomicBoolean(false);
            final ScheduledFuture<?> timeoutFuture = timeoutExecutor.schedule(() -> {
                if (completed.compareAndSet(false, true)) {
                    disconnect();
                    postError(result, "job_timeout", "Timed out while writing print job");
                }
            }, WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            try {
                final int[] chunkSizes = new int[]{256, 128, 64};
                IOException lastError = null;

                for (int attempt = 0; attempt < chunkSizes.length; attempt++) {
                    if (completed.get()) {
                        return;
                    }

                    if (attempt > 0 && !reconnectForWrite()) {
                        lastError = new IOException("Reconnect failed");
                        break;
                    }

                    try {
                        sendInChunks(data, chunkSizes[attempt]);
                        if (completed.compareAndSet(false, true)) {
                            postSuccess(result, true);
                        }
                        return;
                    } catch (IOException e) {
                        lastError = e;
                        if (attempt < chunkSizes.length - 1) {
                            closeActiveSocketLocked();
                            sleepQuietly(WRITE_RETRY_DELAY_MS);
                        }
                    }
                }

                if (completed.compareAndSet(false, true)) {
                    final String code = classifyWriteError(lastError);
                    postError(result, code, lastError != null ? lastError.getMessage() : "Write failed");
                }
            } finally {
                timeoutFuture.cancel(true);
            }
        });
    }

    private void sendInChunks(byte[] data, int chunkSize) throws IOException {
        final OutputStream outputStream;
        synchronized (connectionLock) {
            if (!connected || activeSocket == null || activeOutputStream == null) {
                throw new IOException("Printer disconnected");
            }
            outputStream = activeOutputStream;
        }

        for (int offset = 0; offset < data.length; offset += chunkSize) {
            final int end = Math.min(offset + chunkSize, data.length);
            outputStream.write(data, offset, end - offset);
            outputStream.flush();
            if (end < data.length) {
                sleepQuietly(CHUNK_PAUSE_MS);
            }
        }
    }

    private byte[] toByteArray(Object bytesValue) {
        if (!(bytesValue instanceof ArrayList)) {
            return new byte[0];
        }

        final ArrayList<?> list = (ArrayList<?>) bytesValue;
        final byte[] data = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final Object item = list.get(i);
            if (item instanceof Number) {
                data[i] = (byte) (((Number) item).intValue() & 0xFF);
            } else {
                data[i] = Byte.parseByte(String.valueOf(item));
            }
        }
        return data;
    }

    private String classifyConnectError(Throwable throwable) {
        final String message = throwable != null && throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
        if (message.contains("timeout") || message.contains("timed out")) {
            return "connect_timeout";
        }
        if (message.contains("disconnected") || message.contains("closed") || message.contains("broken pipe") || message.contains("reset") || message.contains("refused")) {
            return "device_disconnected";
        }
        return "write_failed";
    }

    private String classifyWriteError(Throwable throwable) {
        final String message = throwable != null && throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
        if (message.contains("disconnected") || message.contains("closed") || message.contains("broken pipe") || message.contains("reset") || message.contains("not connected")) {
            return "device_disconnected";
        }
        return "write_failed";
    }

    private void emitState(int state) {
        final EventSink sink = stateSink;
        if (sink == null) {
            return;
        }

        mainHandler.post(() -> {
            if (stateSink != null) {
                stateSink.success(state);
            }
        });
    }

    private void postSuccess(Result result, Object value) {
        mainHandler.post(() -> result.success(value));
    }

    private void postError(Result result, String code, String message) {
        mainHandler.post(() -> result.error(code, message, null));
    }

    private void closeSocket(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }

        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void closeActiveSocketLocked() {
        closeSocket(activeSocket);
        activeSocket = null;
        activeOutputStream = null;
        connected = false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void destroy() {
        disconnect();
        unregisterScanReceiver();
        synchronized (connectionLock) {
            seenScanAddresses.clear();
            discoveredDevices.clear();
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        createChannel(binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
        }
        if (stateChannel != null) {
            stateChannel.setStreamHandler(null);
        }
        channel = null;
        stateChannel = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityPluginBinding = binding;
        activity = binding.getActivity();

        final BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        detachFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        detachFromActivity();
    }

    private void detachFromActivity() {
        stopScan();
        disconnect();
        if (activityPluginBinding != null) {
            activityPluginBinding.removeRequestPermissionsResultListener(this);
        }
        activityPluginBinding = null;
        activity = null;
        bluetoothAdapter = null;
    }

    private void createChannel(BinaryMessenger binaryMessenger) {
        channel = new MethodChannel(binaryMessenger, NAMESPACE + "/methods");
        channel.setMethodCallHandler(this);

        stateChannel = new EventChannel(binaryMessenger, NAMESPACE + "/state");
        stateChannel.setStreamHandler(new StreamHandler() {
            @Override
            public void onListen(Object arguments, EventSink events) {
                stateSink = events;
                if (activity != null) {
                    final IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
                    filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
                    filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                    filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                    ContextCompat.registerReceiver(activity, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
                }
            }

            @Override
            public void onCancel(Object arguments) {
                stateSink = null;
                if (activity != null) {
                    try {
                        activity.unregisterReceiver(stateReceiver);
                    } catch (IllegalArgumentException ignored) {
                        // receiver already unregistered
                    }
                }
            }
        });
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_SCAN_PERMISSIONS) {
            return false;
        }

        if (pendingPermissionResult == null) {
            return true;
        }

        boolean allGranted = grantResults.length > 0;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        final Result result = pendingPermissionResult;
        final PendingOperation operation = pendingOperation;
        final String address = pendingAddress;
        final String deniedMessage = pendingPermissionDeniedMessage;

        pendingPermissionResult = null;
        pendingOperation = PendingOperation.NONE;
        pendingAddress = null;
        pendingPermissionDeniedMessage = null;

        if (!allGranted) {
            result.error("no_permissions", deniedMessage != null ? deniedMessage : "Permissions denied", null);
            return true;
        }

        if (operation == PendingOperation.SCAN) {
            startScan(result);
        } else if (operation == PendingOperation.CONNECT && address != null) {
            connectInternal(address, result);
        }

        return true;
    }
}
