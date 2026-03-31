package com.cordova.plugin;

import android.hardware.usb.UsbDevice;

import java.util.ArrayList;
import java.util.List;

public class CompositeHighResPhotoCaptureBackend implements HighResPhotoCaptureBackend {
    private final List<HighResPhotoCaptureBackend> backends = new ArrayList<>();

    public CompositeHighResPhotoCaptureBackend(List<HighResPhotoCaptureBackend> backends) {
        if (backends != null) {
            this.backends.addAll(backends);
        }
    }

    @Override
    public String getBackendName() {
        return "composite-high-res-backend";
    }

    @Override
    public boolean supportsDevice(UsbDevice device) {
        for (HighResPhotoCaptureBackend backend : backends) {
            if (backend != null && backend.supportsDevice(device)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void initialize() throws Exception {
        for (HighResPhotoCaptureBackend backend : backends) {
            if (backend != null) {
                backend.initialize();
            }
        }
    }

    @Override
    public HighResPhotoResult capture(UsbDevice device, HighResPhotoRequest request) throws Exception {
        Exception lastException = null;
        for (HighResPhotoCaptureBackend backend : backends) {
            if (backend == null || !backend.supportsDevice(device)) {
                continue;
            }
            try {
                return backend.capture(device, request);
            } catch (Exception exception) {
                lastException = exception;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IllegalStateException("No compatible high-res photo backend available");
    }

    @Override
    public void release() {
        for (HighResPhotoCaptureBackend backend : backends) {
            if (backend != null) {
                backend.release();
            }
        }
    }
}
