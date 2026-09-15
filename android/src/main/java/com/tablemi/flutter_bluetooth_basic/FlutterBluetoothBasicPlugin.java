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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final int CHUNK_SIZE_BYTES = 128;
    private static final long CHUNK_PAUSE_MS = 50L;
    private static final long READ_POLL_MS = 10L;
    private static final long QUIET_DRAIN_CAP_MS = 1000L;
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
    private volatile InputStream activeInputStream;
    private volatile String connectedAddress;
    private volatile boolean connected;
    // Set when a status query's first phase timed out without receiving a
    // single byte. ESC/POS replies carry no tag identifying which request
    // they answer, so a late reply to that timed-out query can still be on
    // its way in and land just as the NEXT query is drained and sent. While
    // set, the next query's drain waits for a quiet line instead of
    // stopping the instant available() reads zero.
    private volatile boolean previousStatusQueryUnanswered;
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
                // Used to be suppressed while a write was in flight, to
                // protect a freshly reconnected socket from a stale broadcast
                // left over by the retry loop that stood here.  There is no
                // retry any more — writeData never opens a new socket while
                // writing — so there is nothing left to protect, and
                // swallowing this broadcast only meant a mid-write
                // disconnect (e.g. right after the last successful chunk,
                // before writeData's own finally clears its state) could go
                // unnoticed: connected stayed true and sendInChunks reported
                // success even though the link was already gone.
                //
                // (Codex nalaz) Android salje ovaj broadcast za SVAKI
                // uredjaj koji se odspoji, ne samo za nas - slusalice ili
                // citac crtickog koda koji se ugase usred ispisa ne smiju
                // srusiti sasvim zdravu vezu s printerom.
                final BluetoothDevice device = getParcelableExtraCompat(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (!isOurConnectedDevice(device)) {
                    return;
                }
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
                    // Isto filtriranje kao ACTION_ACL_DISCONNECTED gore - i
                    // ovaj broadcast dolazi za svaku promjenu profila bilo
                    // kojeg uredjaja, ne samo naseg printera.
                    final BluetoothDevice device = getParcelableExtraCompat(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (!isOurConnectedDevice(device)) {
                        return;
                    }
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
            case "queryStatus":
                queryStatus(args, result);
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

    // Android salje ACL_DISCONNECTED i CONNECTION_STATE_CHANGED za SVAKI
    // Bluetooth uredjaj koji se odspoji, ne samo za onaj s kojim mi
    // razgovaramo - bez ovog filtera bi se odspajanje slusalica ili citaca
    // crtickog koda tumacilo kao prekid ispisa. connectedAddress se cita pod
    // istim connectionLock-om kojim se i postavlja (vidi connectInternal).
    private boolean isOurConnectedDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        final String currentAddress;
        synchronized (connectionLock) {
            currentAddress = connectedAddress;
        }
        return currentAddress != null && currentAddress.equals(device.getAddress());
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
                    activeInputStream = socket.getInputStream();
                    connectedAddress = address;
                    connected = true;
                    previousStatusQueryUnanswered = false;
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
                // Small chunks with generous pauses to avoid overflowing the
                // printer's receive buffer (cheap thermal printers ignore RFCOMM
                // flow control).
                //
                // One pass, no resend.  The retry loop that stood here started
                // again from byte 0, but the printer had already put the
                // beginning of the receipt on paper — so part of the receipt
                // came out twice.  A broken connection now goes straight back
                // to Dart; reprinting is the user's call, from a dialog.
                sendInChunks(data, CHUNK_SIZE_BYTES);

                if (completed.compareAndSet(false, true)) {
                    postSuccess(result, true);
                }
            } catch (IOException e) {
                if (completed.compareAndSet(false, true)) {
                    // The socket is unusable after a failed write.  Drop it so
                    // the next print starts from a clean connection instead of
                    // inheriting a half-written stream.
                    disconnect();
                    postError(result, classifyWriteError(e), e.getMessage() != null ? e.getMessage() : "Write failed");
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

    private void queryStatus(Map<String, Object> args, Result result) {
        if (args == null || !args.containsKey("bytes")) {
            result.error("bytes_empty", "Bytes param is empty", null);
            return;
        }

        final byte[] request = toByteArray(args.get("bytes"));
        if (request.length == 0) {
            result.error("bytes_empty", "Bytes param is empty", null);
            return;
        }

        if (!connected || connectedAddress == null) {
            result.error("device_disconnected", "Printer is not connected", null);
            return;
        }

        final int timeoutMs = toIntArg(args.get("timeoutMs"));
        final int graceMs = toIntArg(args.get("graceMs"));
        final int quietMs = toIntArg(args.get("quietMs"));
        final int maxBytes = toIntArg(args.get("maxBytes"));

        // ioExecutor is a single-thread executor, so a status query can never
        // interrupt a print job that is already in flight - it simply queues
        // up behind it and runs once the write finishes. This is intentional:
        // the RFCOMM stream is shared, and interleaving a read with an
        // in-progress write would corrupt both.
        ioExecutor.execute(() -> {
            final AtomicBoolean completed = new AtomicBoolean(false);
            // Phase 2 (see readStatusResponse) can renew its graceMs window
            // once per byte actually received, so in the worst case it
            // renews up to maxBytes times - not just once. The guard timeout
            // has to cover that whole worst case: timeoutMs for phase 1, plus
            // maxBytes * graceMs for phase 2, plus QUIET_DRAIN_CAP_MS for the
            // quiet-line drain, plus another 5s because the write of the
            // request itself could theoretically block too. Computed in long
            // so large caller-supplied values can't overflow it.
            final long guardTimeoutMs = (long) timeoutMs + (long) maxBytes * (long) graceMs + QUIET_DRAIN_CAP_MS + 5000L;
            final ScheduledFuture<?> timeoutFuture = timeoutExecutor.schedule(() -> {
                if (completed.compareAndSet(false, true)) {
                    disconnect();
                    postError(result, "job_timeout", "Timed out while querying printer status");
                }
            }, guardTimeoutMs, TimeUnit.MILLISECONDS);

            try {
                final byte[] response = readStatusResponse(request, timeoutMs, graceMs, quietMs, maxBytes);

                if (completed.compareAndSet(false, true)) {
                    // An empty array is a legitimate result - it means the
                    // printer did not answer in time - and is not an error,
                    // so it must not trigger a disconnect.
                    postSuccess(result, response);
                }
            } catch (IOException e) {
                if (completed.compareAndSet(false, true)) {
                    // The socket is unusable after a failed read/write, same
                    // reasoning as writeData.
                    disconnect();
                    postError(result, classifyWriteError(e), e.getMessage() != null ? e.getMessage() : "Query failed");
                }
            } finally {
                timeoutFuture.cancel(true);
            }
        });
    }

    private byte[] readStatusResponse(byte[] request, int timeoutMs, int graceMs, int quietMs, int maxBytes) throws IOException {
        final InputStream inputStream;
        final OutputStream outputStream;
        synchronized (connectionLock) {
            if (!connected || activeSocket == null || activeInputStream == null || activeOutputStream == null) {
                throw new IOException("Printer disconnected");
            }
            inputStream = activeInputStream;
            outputStream = activeOutputStream;
        }

        // Drain whatever is already sitting in the input buffer before asking
        // our own question. The printer can push an unsolicited ASB packet on
        // its own, without being asked, and if we didn't drain it here we
        // could mistake it for the answer to this query.
        //
        // This is a mitigation, not a fix: ESC/POS replies carry no tag
        // saying which request they answer, so pairing a reply to its
        // request is fundamentally the caller's job (it knows which fixed
        // bits to expect back). If the PREVIOUS query timed out in phase 1
        // without a single byte, its answer may still be in flight and
        // land mid-drain or mid-send of this one - a plain "stop once
        // available() == 0" drain would miss it. In that case we instead
        // wait for the line to go quiet for quietMs, capped overall at
        // QUIET_DRAIN_CAP_MS so a printer that stays chatty can't stall
        // this query forever.
        int discarded = 0;
        final byte[] drainBuffer = new byte[64];
        if (previousStatusQueryUnanswered) {
            final long drainCapNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUIET_DRAIN_CAP_MS);
            long quietSinceNanos = System.nanoTime();
            while (System.nanoTime() < drainCapNanos) {
                if (inputStream.available() > 0) {
                    final int read = inputStream.read(drainBuffer, 0, Math.min(drainBuffer.length, inputStream.available()));
                    if (read <= 0) {
                        break;
                    }
                    discarded += read;
                    quietSinceNanos = System.nanoTime();
                } else if (System.nanoTime() - quietSinceNanos >= TimeUnit.MILLISECONDS.toNanos(quietMs)) {
                    break;
                } else {
                    sleepQuietly(READ_POLL_MS);
                }
            }
            if (discarded > 0) {
                Log.d(TAG, "Discarded " + discarded + " stale byte(s) from printer input while waiting for a quiet line after an unanswered query");
            }
        } else {
            while (inputStream.available() > 0) {
                final int read = inputStream.read(drainBuffer, 0, Math.min(drainBuffer.length, inputStream.available()));
                if (read <= 0) {
                    break;
                }
                discarded += read;
            }
            if (discarded > 0) {
                Log.d(TAG, "Discarded " + discarded + " stale byte(s) from printer input before status query");
            }
        }

        outputStream.write(request);
        outputStream.flush();

        final byte[] buffer = new byte[Math.max(maxBytes, 0)];
        int received = 0;

        // Phase 1: wait for the FIRST byte, up to timeoutMs. DLE EOT answers
        // from the printer's interrupt routine, so a healthy printer clears
        // this almost instantly; a queued GS r can take the whole timeout,
        // since it only answers once the printer works through everything
        // already ahead of it in the buffer.
        final long firstByteDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (received == 0 && received < buffer.length && System.nanoTime() < firstByteDeadlineNanos) {
            if (inputStream.available() > 0) {
                final int toRead = Math.min(inputStream.available(), buffer.length - received);
                final int read = inputStream.read(buffer, received, toRead);
                if (read > 0) {
                    received += read;
                }
            } else {
                // Poll instead of a blocking read(): BluetoothSocket's input
                // stream has no read timeout, so a blocking read() here would
                // stall this thread until the connection itself dies.
                sleepQuietly(READ_POLL_MS);
            }
        }

        // Phase 2: once the first byte has landed, keep collecting up to
        // maxBytes, but only for graceMs after the LAST byte received - not
        // the rest of timeoutMs. Without this, a one-byte DLE EOT reply
        // would block the call for the full (multi-second) timeout callers
        // use for queued GS r requests, even though the printer already
        // answered.
        if (received > 0) {
            long graceDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
            while (received < buffer.length && System.nanoTime() < graceDeadlineNanos) {
                if (inputStream.available() > 0) {
                    final int toRead = Math.min(inputStream.available(), buffer.length - received);
                    final int read = inputStream.read(buffer, received, toRead);
                    if (read <= 0) {
                        break;
                    }
                    received += read;
                    graceDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
                } else {
                    sleepQuietly(READ_POLL_MS);
                }
            }
        }

        // Remember whether this query got any bytes at all, so the NEXT
        // query knows whether a stale reply might still be in flight.
        previousStatusQueryUnanswered = received == 0;

        if (received == buffer.length) {
            return buffer;
        }
        final byte[] trimmed = new byte[received];
        System.arraycopy(buffer, 0, trimmed, 0, received);
        return trimmed;
    }

    private int toIntArg(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private byte[] toByteArray(Object bytesValue) {
        // StandardMessageCodec decodes a Dart Uint8List argument as a raw
        // byte[] on Android (not an ArrayList), and Int32List/Int64List as
        // int[]/long[]; without handling those, a caller passing a typed
        // list (e.g. Uint8List.fromList(...)) silently produced bytes_empty.
        if (bytesValue instanceof byte[]) {
            return Arrays.copyOf((byte[]) bytesValue, ((byte[]) bytesValue).length);
        }

        if (bytesValue instanceof int[]) {
            final int[] ints = (int[]) bytesValue;
            final byte[] data = new byte[ints.length];
            for (int i = 0; i < ints.length; i++) {
                data[i] = (byte) (ints[i] & 0xFF);
            }
            return data;
        }

        if (bytesValue instanceof long[]) {
            final long[] longs = (long[]) bytesValue;
            final byte[] data = new byte[longs.length];
            for (int i = 0; i < longs.length; i++) {
                data[i] = (byte) (longs[i] & 0xFF);
            }
            return data;
        }

        if (!(bytesValue instanceof List)) {
            return new byte[0];
        }

        final List<?> list = (List<?>) bytesValue;
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
        activeInputStream = null;
        connected = false;
        previousStatusQueryUnanswered = false;
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
