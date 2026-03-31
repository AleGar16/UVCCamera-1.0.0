package com.cordova.plugin;

import android.hardware.usb.UsbDevice;

public interface HighResPhotoCaptureBackend {
    String getBackendName();

    boolean supportsDevice(UsbDevice device);

    void initialize() throws Exception;

    HighResPhotoResult capture(UsbDevice device, HighResPhotoRequest request) throws Exception;

    void release();
}
