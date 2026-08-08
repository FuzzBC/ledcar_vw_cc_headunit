package com.ledcar01.controller;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import java.util.ArrayDeque;

/** Per-device connection state, owned and mutated only by BleDeviceManager on the main thread. */
public class BleConnection {

    public enum State {
        CONNECTING, CONNECTED, DISCONNECTED, FAILED
    }

    public final String address;
    public final String name;

    BluetoothGatt gatt;
    BluetoothGattCharacteristic writeCharacteristic;
    State state = State.CONNECTING;
    final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();

    BleConnection(String address, String name) {
        this.address = address;
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public State getState() {
        return state;
    }
}
