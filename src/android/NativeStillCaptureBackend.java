package com.cordova.plugin;

import android.hardware.usb.UsbDevice;

public class NativeStillCaptureBackend implements HighResPhotoCaptureBackend {
    @Override
    public String getBackendName() {
        return "native-still-capture";
    }

    @Override
    public boolean supportsDevice(UsbDevice device) {
        return device != null;
    }

    @Override
    public void initialize() {
        // Placeholder backend for the next phase.
    }

    @Override
    public HighResPhotoResult capture(UsbDevice device, HighResPhotoRequest request) {
        throw new UnsupportedOperationException(
                "NativeStillCaptureBackend not implemented yet. " +
                "Next step: integrate a true still-image capture backend that can exceed 640x480."
        );
    }

    @Override
    public void release() {
        // Placeholder backend for the next phase.
    }
}
