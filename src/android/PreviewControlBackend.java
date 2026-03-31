package com.cordova.plugin;

import android.hardware.usb.UsbDevice;

public interface PreviewControlBackend {
    String getBackendName();

    boolean supportsDevice(UsbDevice device);

    void initialize() throws Exception;

    void openPreview(UsbDevice device, int width, int height) throws Exception;

    void closePreview();

    boolean isPreviewOpened();
}
