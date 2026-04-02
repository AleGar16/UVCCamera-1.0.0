package com.cordova.plugin;

import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.util.Base64;
import android.util.Log;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICaptureCallBack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicReference;

public class AusbcHighResPhotoCaptureBackend implements HighResPhotoCaptureBackend {
    private static final String TAG = "AusbcHighResBackend";
    private static final int POLL_INTERVAL_MS = 200;
    private static final int MIN_BYTES = 4096;

    private final AusbcCameraHandleProvider cameraHandleProvider;

    public AusbcHighResPhotoCaptureBackend(AusbcCameraHandleProvider cameraHandleProvider) {
        this.cameraHandleProvider = cameraHandleProvider;
    }

    @Override
    public String getBackendName() {
        return "ausbc-capture-image";
    }

    @Override
    public boolean supportsDevice(UsbDevice device) {
        return device != null;
    }

    @Override
    public void initialize() {
        // No-op for current AUSBC implementation.
    }

    @Override
    public HighResPhotoResult capture(UsbDevice device, HighResPhotoRequest request) throws Exception {
        MultiCameraClient.Camera currentCamera = cameraHandleProvider.getCurrentCamera();
        if (currentCamera == null || !currentCamera.isCameraOpened()) {
            throw new IllegalStateException("USB UVC camera not opened");
        }

        File photoFile = new File(request.getOutputPath());
        if (photoFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            photoFile.delete();
        }

        Log.i(TAG, "Starting captureImage backend flow for " + photoFile.getAbsolutePath());
        AtomicReference<String> captureError = new AtomicReference<>();
        currentCamera.captureImage(new ICaptureCallBack() {
            @Override
            public void onBegin() {
                Log.i(TAG, "captureImage onBegin");
            }

            @Override
            public void onComplete(String path) {
                Log.i(TAG, "captureImage onComplete path=" + path);
            }

            @Override
            public void onError(String error) {
                captureError.set(error);
                Log.w(TAG, "captureImage onError " + error);
            }
        }, photoFile.getAbsolutePath());

        int elapsedMs = 0;
        while (elapsedMs < request.getTimeoutMs()) {
            if (captureError.get() != null) {
                throw new IllegalStateException("captureImage failed before file creation: " + captureError.get());
            }
            if (photoFile.exists() && photoFile.length() >= MIN_BYTES) {
                int[] dimensions = decodeImageDimensions(photoFile);
                if (!meetsRequestedResolution(dimensions, request)) {
                    throw new IllegalStateException("captureImage produced insufficient resolution: "
                            + dimensions[0] + "x" + dimensions[1]
                            + ", requested at least " + request.getRequestedWidth() + "x" + request.getRequestedHeight());
                }
                String base64 = encodeFileAsBase64(photoFile);
                if (base64 == null) {
                    throw new IllegalStateException("Failed to encode captured file as base64");
                }
                Log.i(TAG, "captureImage file detected size=" + photoFile.length() + ", width=" + dimensions[0] + ", height=" + dimensions[1]);
                return new HighResPhotoResult(
                        base64,
                        dimensions[0],
                        dimensions[1],
                        photoFile.length(),
                        getBackendName()
                );
            }
            Thread.sleep(POLL_INTERVAL_MS);
            elapsedMs += POLL_INTERVAL_MS;
        }

        throw new IllegalStateException("High-res capture file not available within timeout");
    }

    @Override
    public void release() {
        // No-op for current AUSBC implementation.
    }

    private String encodeFileAsBase64(File file) throws Exception {
        FileInputStream inputStream = null;
        ByteArrayOutputStream outputStream = null;
        try {
            inputStream = new FileInputStream(file);
            outputStream = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private int[] decodeImageDimensions(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return new int[] { options.outWidth, options.outHeight };
        } catch (Exception exception) {
            Log.w(TAG, "decodeImageDimensions failed", exception);
            return new int[] { -1, -1 };
        }
    }

    private boolean meetsRequestedResolution(int[] dimensions, HighResPhotoRequest request) {
        if (dimensions == null || dimensions.length < 2 || request == null) {
            return false;
        }
        return dimensions[0] >= request.getRequestedWidth()
                && dimensions[1] >= request.getRequestedHeight();
    }
}
