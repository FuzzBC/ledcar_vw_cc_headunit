package com.ledcar01.controller;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Application-scoped singleton managing an unbounded set of simultaneous
 * LEDCAR-01 BLE connections. Every command is broadcast to every currently
 * connected device. Survives Activity recreation (rotation) since it is never
 * torn down from Activity.onDestroy(); it only ends when the process does.
 */
public class BleDeviceManager {

    public interface Listener {
        void onStatus(String status);

        void onDevicesChanged(List<BleConnection> connections);

        /**
         * Fires exactly when scanning actually starts/stops - unlike
         * onStatus(), whose free-text messages also cover lots of
         * per-device events (connecting, connected, disconnected) that
         * aren't about scan state at all. A listener that inferred
         * "scanning" from onStatus() substring-matching would see it flip
         * off the moment the first device starts connecting, even though
         * the scan itself is still running in the background looking for
         * more.
         */
        default void onScanningChanged(boolean scanning) {
        }
    }

    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int MAX_CONNECTIONS = 6;
    private static final long CONNECT_STAGGER_MS = 220;
    /** Absolute cap on a scan with nothing found at all - not the common case, just a safety net. */
    private static final long SCAN_TIMEOUT_MS = 12000;
    /**
     * Once at least one device is found there's no reason to keep the full
     * scan window open - stop this soon after instead, so a single nearby
     * module doesn't force a multi-second wait it doesn't need. Long enough
     * to still catch a second/third LEDCAR-01 unit advertising a beat later
     * (multiple simultaneous connections are supported - see MAX_CONNECTIONS),
     * short enough that "found fast" actually feels fast.
     */
    private static final long SCAN_FOUND_GRACE_MS = 2500;
    /**
     * Some head-unit BLE stacks never deliver onCharacteristicWrite for a
     * write that silently failed at the driver level (no error, no
     * callback) - without a watchdog, `writeInFlight` latches true forever
     * and every command after that point queues up and is never sent
     * again, even though the device still shows "connected". If this fires,
     * something upstream is wrong and getting unstuck late beats staying
     * stuck forever.
     */
    private static final long WRITE_WATCHDOG_MS = 4000;

    private static volatile BleDeviceManager instance;

    public static BleDeviceManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BleDeviceManager.class) {
                if (instance == null) {
                    instance = new BleDeviceManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, BleConnection> connections = new LinkedHashMap<>();
    private final Map<String, byte[]> lastCommands = new LinkedHashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean scanFoundGraceArmed;
    private final Runnable stopScanRunnable = this::stopScan;
    private int connectScheduleCounter;
    private boolean writeInFlight;
    private int writeGeneration;
    private Listener listener;

    private BleDeviceManager(Context appContext) {
        this.appContext = appContext;
        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            adapter = manager.getAdapter();
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onDevicesChanged(new ArrayList<>(connections.values()));
            // Sync a freshly (re)attached listener - e.g. after Activity
            // recreation mid-scan - to the real current scan state, rather
            // than leaving it assuming "not scanning" until the next event.
            listener.onScanningChanged(scanning);
        }
    }

    @SuppressLint("MissingPermission")
    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean isScanning() {
        return scanning;
    }

    public List<BleConnection> getConnections() {
        return new ArrayList<>(connections.values());
    }

    public int getConnectedCount() {
        int count = 0;
        for (BleConnection c : connections.values()) {
            if (c.state == BleConnection.State.CONNECTED) {
                count++;
            }
        }
        return count;
    }

    /** Groups connected devices by a normalised label, e.g. "LEDCAR01 x2". */
    public String summaryText() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BleConnection c : connections.values()) {
            if (c.state != BleConnection.State.CONNECTED) {
                continue;
            }
            String label = displayLabel(c.name);
            Integer existing = counts.get(label);
            counts.put(label, existing == null ? 1 : existing + 1);
        }
        if (counts.isEmpty()) {
            return "No devices connected";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey());
            if (entry.getValue() > 1) {
                sb.append(" x").append(entry.getValue());
            }
        }
        return sb.toString();
    }

    private String displayLabel(String rawName) {
        if (rawName != null && rawName.toUpperCase(Locale.US).startsWith("LEDCAR-01")) {
            return "LEDCAR01";
        }
        return rawName != null ? rawName : "device";
    }

    // -- Scanning / connecting --

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (adapter == null || !adapter.isEnabled() || scanning) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            notifyStatus("Bluetooth scanner unavailable");
            return;
        }
        scanning = true;
        scanFoundGraceArmed = false;
        connectScheduleCounter = 0;
        notifyStatus("Scanning for LEDCAR devices...");
        notifyScanningChanged(true);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        // Unfiltered on purpose: some chipsets only apply a hardware ScanFilter
        // against the primary advertising packet and drop peripherals that
        // carry their service UUID in the scan response instead. Matching is
        // done manually below against the merged scan record.
        scanner.startScan(null, settings, scanCallback);
        mainHandler.removeCallbacks(stopScanRunnable);
        mainHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!scanning) {
            return;
        }
        scanning = false;
        mainHandler.removeCallbacks(stopScanRunnable);
        if (scanner != null) {
            scanner.stopScan(scanCallback);
        }
        notifyStatus(connections.isEmpty() ? "No LEDCAR devices found nearby" : summaryText() + " connected");
        notifyScanningChanged(false);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            // This callback fires on a Binder thread (unlike the GATT callbacks,
            // which are bound to mainHandler via connectGatt's Handler overload).
            // Everything that touches `connections`/`connectScheduleCounter` must
            // be pushed onto the main thread to avoid racing with the rest of
            // this class, which assumes single-threaded (main-thread-only) access.
            ScanRecord record = result.getScanRecord();
            List<ParcelUuid> uuids = record != null ? record.getServiceUuids() : null;
            boolean advertisesServiceUuid = uuids != null && uuids.contains(new ParcelUuid(SERVICE_UUID));
            // Some real units (confirmed via nRF Connect against a live LEDCAR-01-4000)
            // don't advertise the service UUID at all - only Flags + Complete Local
            // Name. Falling back to the same name-prefix check used elsewhere
            // (displayLabel()) means those units are still found instead of the
            // scan silently matching nothing and "Connect" never firing.
            String advertisedName = record != null ? record.getDeviceName() : null;
            boolean nameMatches = advertisedName != null
                    && advertisedName.toUpperCase(Locale.US).startsWith("LEDCAR-01");
            if (!advertisesServiceUuid && !nameMatches) {
                return;
            }
            BluetoothDevice device = result.getDevice();
            mainHandler.post(() -> {
                if (connections.containsKey(device.getAddress()) || connections.size() >= MAX_CONNECTIONS) {
                    return;
                }
                long delay = connectScheduleCounter * CONNECT_STAGGER_MS;
                connectScheduleCounter++;
                mainHandler.postDelayed(() -> attemptConnect(device), delay);

                // Found something - no need to keep scanning for the full
                // SCAN_TIMEOUT_MS anymore. Replace the long safety-net stop
                // with a short grace window instead, so a scan that finds
                // its device in the first second or two actually stops
                // quickly instead of visibly "still scanning" for several
                // more seconds it doesn't need.
                if (!scanFoundGraceArmed) {
                    scanFoundGraceArmed = true;
                    mainHandler.removeCallbacks(stopScanRunnable);
                    mainHandler.postDelayed(stopScanRunnable, SCAN_FOUND_GRACE_MS);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            mainHandler.post(() -> {
                scanning = false;
                notifyStatus("Scan failed (code " + errorCode + ")");
            });
        }
    };

    @SuppressLint("MissingPermission")
    private void attemptConnect(BluetoothDevice device) {
        String address = device.getAddress();
        if (connections.containsKey(address)) {
            return;
        }
        if (connections.size() >= MAX_CONNECTIONS) {
            notifyStatus("Connection limit (" + MAX_CONNECTIONS + ") reached, skipping " + safeName(device));
            return;
        }
        BleConnection conn = new BleConnection(address, safeName(device));
        conn.state = BleConnection.State.CONNECTING;
        connections.put(address, conn);
        notifyDevicesChanged();
        notifyStatus("Connecting to " + conn.name + "...");
        conn.gatt = connectGattCompat(device, false);
    }

    /**
     * The 5-arg connectGatt(..., TRANSPORT_LE, PHY_LE_1M_MASK, Handler)
     * overload - which lets GATT callbacks run on mainHandler instead of an
     * arbitrary binder thread - needs API 26. Below that (this app's
     * minSdk 21, for older head units), fall back to the classic 3-arg
     * overload; its callbacks arrive on a binder thread instead, which is
     * why every GATT/scan callback in this class already pushes its real
     * work through mainHandler.post() rather than assuming it's already on
     * the main thread.
     */
    @SuppressLint("MissingPermission")
    private BluetoothGatt connectGattCompat(BluetoothDevice device, boolean autoConnect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return device.connectGatt(appContext, autoConnect, gattCallback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, mainHandler);
        }
        return device.connectGatt(appContext, autoConnect, gattCallback);
    }

    private String safeName(BluetoothDevice device) {
        String name = device.getName();
        return name != null ? name : device.getAddress();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BleConnection conn = connections.get(gatt.getDevice().getAddress());
            if (conn == null) {
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleDisconnect(conn, gatt, status);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BleConnection conn = connections.get(gatt.getDevice().getAddress());
            if (conn == null) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyStatus(conn.name + ": service discovery failed");
                return;
            }
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                notifyStatus(conn.name + ": LED service not found");
                return;
            }
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
            if (characteristic == null) {
                notifyStatus(conn.name + ": LED characteristic not found");
                return;
            }
            conn.writeCharacteristic = characteristic;
            // LEDCAR-01 modules are HM-10/CC41-style BLE-UART bridges - they
            // almost always only declare "Write Without Response"
            // (PROPERTY_WRITE_NO_RESPONSE), not "Write With Response". Left
            // unset, a characteristic's write type defaults to
            // WRITE_TYPE_DEFAULT ("with response"); some head-unit BLE
            // stacks (seen in the field on Junsun-class hardware) refuse
            // that outright when the peripheral doesn't declare support for
            // it - gatt.writeCharacteristic() returns false and no callback
            // ever fires, which without the writeInFlight fixes below would
            // silently stall every command after the very first one, while
            // the device still reports "connected". Explicitly matching the
            // characteristic's actual declared properties is the real fix;
            // the checks in drainNext()/watchdog below are the safety net
            // for stacks that misbehave in other ways.
            int properties = characteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                // finishConnectionSetup() (which starts replaying/writing real
                // commands) only runs once onDescriptorWrite confirms this write
                // landed - Android's BLE stack allows exactly one outstanding GATT
                // operation per connection, so calling writeCharacteristic while
                // this descriptor write is still in flight fails immediately with
                // "prior command is not finished" and the command is silently lost.
                gatt.writeDescriptor(descriptor);
            } else {
                finishConnectionSetup(conn);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            BleConnection conn = connections.get(gatt.getDevice().getAddress());
            if (conn == null) {
                return;
            }
            finishConnectionSetup(conn);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            mainHandler.post(() -> {
                writeInFlight = false;
                writeGeneration++; // invalidates any watchdog still pending for this write
                drainNext();
            });
        }
    };

    private void finishConnectionSetup(BleConnection conn) {
        conn.state = BleConnection.State.CONNECTED;
        replayLastStateTo(conn);
        notifyDevicesChanged();
        notifyStatus(conn.name + " connected");
    }

    @SuppressLint("MissingPermission")
    private void handleDisconnect(BleConnection conn, BluetoothGatt gatt, int status) {
        BluetoothDevice device = gatt.getDevice();
        gatt.close();
        conn.gatt = null;
        conn.writeCharacteristic = null;
        conn.writeQueue.clear();

        boolean wasConnected = conn.state == BleConnection.State.CONNECTED;
        if (wasConnected) {
            notifyStatus(conn.name + " disconnected, reconnecting...");
            conn.state = BleConnection.State.CONNECTING;
            conn.gatt = connectGattCompat(device, true);
        } else {
            notifyStatus(conn.name + " failed to connect (status " + status + ")");
            connections.remove(conn.address);
        }
        notifyDevicesChanged();
    }

    // -- Sending commands --

    public void broadcastCommand(byte[] packet) {
        mainHandler.post(() -> {
            cacheLastCommand(packet);
            for (BleConnection conn : connections.values()) {
                if (conn.state == BleConnection.State.CONNECTED && conn.writeCharacteristic != null) {
                    conn.writeQueue.add(packet);
                }
            }
            drainNext();
        });
    }

    @SuppressLint("MissingPermission")
    private void drainNext() {
        if (writeInFlight) {
            return;
        }
        for (BleConnection conn : connections.values()) {
            if (conn.state != BleConnection.State.CONNECTED || conn.gatt == null || conn.writeCharacteristic == null) {
                continue;
            }
            if (!conn.writeQueue.isEmpty()) {
                byte[] next = conn.writeQueue.poll();
                conn.writeCharacteristic.setValue(next);
                boolean started = conn.gatt.writeCharacteristic(conn.writeCharacteristic);
                if (!started) {
                    // The BLE stack rejected the write synchronously (seen on
                    // some head-unit chipsets) - onCharacteristicWrite will
                    // never fire for it, so don't set writeInFlight at all.
                    // Put the command back at the front of its queue and
                    // retry shortly rather than dropping it or spinning
                    // synchronously in a tight loop.
                    conn.writeQueue.addFirst(next);
                    notifyStatus(conn.name + ": write rejected, retrying");
                    mainHandler.postDelayed(this::drainNext, 300);
                    return;
                }
                writeInFlight = true;
                int gen = ++writeGeneration;
                mainHandler.postDelayed(() -> onWriteWatchdog(gen), WRITE_WATCHDOG_MS);
                return;
            }
        }
    }

    /**
     * Fires WRITE_WATCHDOG_MS after a write started. If onCharacteristicWrite
     * still hasn't landed for that same write by then, the callback is
     * presumed lost (a real, observed failure mode on some head-unit BLE
     * stacks) - force the queue unstuck rather than let every command from
     * here on silently queue up forever while the device still shows
     * "connected".
     */
    private void onWriteWatchdog(int gen) {
        if (writeInFlight && gen == writeGeneration) {
            writeInFlight = false;
            notifyStatus("Device stopped responding to writes, retrying");
            drainNext();
        }
    }

    private void replayLastStateTo(BleConnection conn) {
        for (byte[] cmd : lastCommands.values()) {
            conn.writeQueue.add(cmd);
        }
        drainNext();
    }

    /** Only caches "visual state" families (not one-shot settings commands like Welcome/config). */
    private void cacheLastCommand(byte[] packet) {
        String key = classify(packet);
        if (key != null) {
            lastCommands.put(key, packet);
        }
    }

    private String classify(byte[] p) {
        if (p.length != 9) {
            return null;
        }
        int header = p[0] & 0xFF;
        int opcode = p[2] & 0xFF;
        if (header == 0x7B && opcode == 0x04) return "power";
        if (header == 0x7B && opcode == 0x0D) return "direction";
        if (header == 0x7B && opcode == 0x07) return "color_dmx";
        if (header == 0x7E && opcode == 0x05) return "color_rgb";
        if (header == 0x7B && opcode == 0x01) return "brightness_dmx";
        if (header == 0x7E && opcode == 0x01) return "brightness_rgb";
        if (header == 0x7B && opcode == 0x02) return "speed_dmx";
        if (header == 0x7E && opcode == 0x02) return "speed_rgb";
        if (header == 0x7B && opcode == 0x03) return "mode_dmx";
        if (header == 0x7E && opcode == 0x03) return "mode_rgb";
        return null;
    }

    private void notifyStatus(String status) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onStatus(status);
            }
        });
    }

    private void notifyDevicesChanged() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onDevicesChanged(new ArrayList<>(connections.values()));
            }
        });
    }

    private void notifyScanningChanged(boolean isScanningNow) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onScanningChanged(isScanningNow);
            }
        });
    }
}
