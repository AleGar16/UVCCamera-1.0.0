package com.cordova.plugin;

import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.callback.IPreviewDataCallBack;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.camera.bean.PreviewSize;
import com.jiangdg.ausbc.utils.MediaUtils;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class UsbUvcCamera extends CordovaPlugin {
    private static final String TAG = "UsbUvcCamera";
    private static final int STABLE_CAPTURE_WIDTH = 1280;
    private static final int STABLE_CAPTURE_HEIGHT = 720;
    private static final String PREFS_NAME = "UsbUvcCameraPrefs";
    private static final String PREF_LAST_LOCKED_FOCUS = "lastLockedFocus";
    private static final int DEFAULT_SMART_FOCUS_LOCK_DELAY_MS = 1800;
    private static final int UVC_EXPOSURE_MODE_MANUAL = 1;
    private static final int UVC_EXPOSURE_MODE_AUTO = 2;
    private static final int MAX_TAKE_PHOTO_ATTEMPTS = 6;
    private static final int TAKE_PHOTO_RETRY_DELAY_MS = 350;
    private static final int TAKE_PHOTO_TIMEOUT_MS = 12000;
    private static final int HIGH_RES_CAPTURE_TIMEOUT_MS = 5000;
    private static final int HIGH_RES_CAPTURE_POLL_INTERVAL_MS = 200;
    private static final int HIGH_RES_CAPTURE_MIN_BYTES = 4096;
    private static final int RECONNECT_DELAY_MS = 1200;
    private static final int OPEN_RETRY_DELAY_MS = 600;
    private static final int MAX_OPEN_RETRIES = 2;
    private MultiCameraClient cameraClient;
    private MultiCameraClient.Camera currentCamera;
    private HighResPhotoCaptureBackend highResPhotoCaptureBackend;
    private UsbDevice currentDevice;
    private AspectRatioTextureView previewView;
    private ViewGroup previewContainer;
    private CallbackContext openCallback;
    private CallbackContext photoCallback;
    private int previewWidth = 1280;
    private int previewHeight = 720;
    private boolean preferHighestResolution = true;
    private boolean preferMjpeg = true;
    private int previewViewX = 0;
    private int previewViewY = 0;
    private int previewViewWidth = 1;
    private int previewViewHeight = 1;
    private int requestedPreviewWidth = 1280;
    private int requestedPreviewHeight = 720;
    private int preferredVendorId = -1;
    private int preferredProductId = -1;
    private boolean previewSurfaceReady = false;
    private boolean previewVisible = false;
    private UsbDevice pendingOpenDevice;
    private USBMonitor.UsbControlBlock pendingCtrlBlock;
    private boolean openingCamera = false;
    private int openRetryCount = 0;
    private boolean autoReconnectEnabled = false;
    private boolean reconnectScheduled = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingPhotoTimeout;
    private Runnable pendingReconnect;
    private Runnable pendingAutoFocusLock;
    private Runnable pendingSurfaceTextureUpdatedAction;
    private Runnable pendingSurfaceTextureUpdatedTimeout;
    private final Object previewFrameLock = new Object();
    private byte[] latestPreviewFrame;
    private int latestPreviewFrameWidth = -1;
    private int latestPreviewFrameHeight = -1;
    private boolean latestPreviewFrameFromUnderlying = false;
    private String latestPreviewFrameFormat = "unknown";
    private boolean loggedFirstPreviewFrame = false;
    private boolean loggedRejectedDarkFrame = false;
    private boolean loggedBackendApiSnapshot = false;
    private boolean loggedAdjustedPreviewFrameSize = false;
    private boolean loggedTextureCaptureMetrics = false;
    private boolean loggedPhotoSourceMetrics = false;
    private List<PreviewSize> currentPreviewSizes = new ArrayList<>();
    private boolean smartFocusEnabled = true;
    private int smartFocusLockDelayMs = DEFAULT_SMART_FOCUS_LOCK_DELAY_MS;

    private interface TextureCaptureCallback {
        void onCaptured(String encodedImage);
    }

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        cordova.getActivity().runOnUiThread(this::ensurePreviewView);
        ensureHighResPhotoCaptureBackend();
        ensureCameraClient();
    }

    @Override
    public void onPause(boolean multitasking) {
        if (cameraClient != null) {
            try {
                cameraClient.unRegister();
            } catch (Exception ignored) {}
        }
        super.onPause(multitasking);
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        if (cameraClient != null) {
            safeRegisterCameraClient();
        }
    }

    @Override
    public void onDestroy() {
        releaseCamera();
        if (cameraClient != null) {
            try {
                cameraClient.unRegister();
            } catch (Exception ignored) {}
            try {
                cameraClient.destroy();
            } catch (Exception ignored) {}
            cameraClient = null;
        }
        if (highResPhotoCaptureBackend != null) {
            highResPhotoCaptureBackend.release();
            highResPhotoCaptureBackend = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        switch (action) {
            case "open":
                return open(args, callbackContext);
            case "close":
                releaseCamera();
                callbackContext.success("closed");
                return true;
            case "takePhoto":
                return takePhoto(callbackContext);
            case "recoverCamera":
                releaseCamera();
                if (currentDevice == null) {
                    callbackContext.error("No previous USB camera to recover");
                    return true;
                }
                openCallback = callbackContext;
                openingCamera = true;
                openRetryCount = 0;
                autoReconnectEnabled = true;
                ensureCameraClient();
                if (cameraClient != null) {
                    safeRegisterCameraClient();
                    cameraClient.requestPermission(currentDevice);
                }
                return true;
            case "showPreview":
                return showPreview(args, callbackContext);
            case "hidePreview":
                return hidePreview(callbackContext);
            case "updatePreviewBounds":
                return updatePreviewBounds(args, callbackContext);
            case "applyStableCameraProfile":
                return applyStableCameraProfile(args, callbackContext);
            case "listUsbDevices":
                return listUsbDevices(callbackContext);
            case "getCameraCapabilities":
                return getCameraCapabilities(callbackContext);
            case "inspectUvcDescriptors":
                return inspectUvcDescriptors(callbackContext);
            case "inspectBackendApi":
                return inspectBackendApi(callbackContext);
            case "setAutoFocus":
                return setAutoFocus(args, callbackContext);
            case "refocus":
                return refocus(args, callbackContext);
            case "setFocus":
                return setFocus(args, callbackContext);
            case "setZoom":
                return setZoom(args, callbackContext);
            case "setBrightness":
                return setBrightness(args, callbackContext);
            case "setContrast":
                return setContrast(args, callbackContext);
            case "setSharpness":
                return setSharpness(args, callbackContext);
            case "setGain":
                return setGain(args, callbackContext);
            case "setAutoExposure":
                return setAutoExposure(args, callbackContext);
            case "setExposure":
                return setExposure(args, callbackContext);
            case "setAutoWhiteBalance":
                return setAutoWhiteBalance(args, callbackContext);
            case "setWhiteBalance":
                return setWhiteBalance(args, callbackContext);
            default:
                return false;
        }
    }

    private boolean open(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options != null) {
            previewWidth = options.optInt("width", 1280);
            previewHeight = options.optInt("height", 720);
            requestedPreviewWidth = previewWidth;
            requestedPreviewHeight = previewHeight;
            preferHighestResolution = options.optBoolean("preferHighestResolution", true);
            preferMjpeg = options.optBoolean("preferMjpeg", true);
            String preferredId = options.optString("cameraId", null);
            if (preferredId != null && preferredId.startsWith("uvc:")) {
                String[] parts = preferredId.split(":");
                if (parts.length == 3) {
                    try {
                        preferredVendorId = Integer.parseInt(parts[1]);
                        preferredProductId = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {
                        preferredVendorId = -1;
                        preferredProductId = -1;
                    }
                }
            }
        }

        Log.i(TAG, "open requested with width=" + previewWidth + ", height=" + previewHeight + ", preferHighestResolution=" + preferHighestResolution + ", preferMjpeg=" + preferMjpeg);

        openCallback = callbackContext;
        openingCamera = true;
        openRetryCount = 0;
        autoReconnectEnabled = true;
        ensureCameraClient();
        cordova.getActivity().runOnUiThread(this::ensurePreviewView);

        UsbDevice device = selectPreferredDevice(options);
        if (device == null) {
            Log.w(TAG, "open failed: no compatible USB UVC camera found");
            callbackContext.error("No compatible USB UVC camera found");
            return true;
        }

        Log.i(TAG, "selected device for open: " + device.getDeviceName() + " (" + device.getVendorId() + ":" + device.getProductId() + ")");
        currentDevice = device;
        if (cameraClient != null) {
            safeRegisterCameraClient();
            cameraClient.requestPermission(device);
        } else {
            callbackContext.error("MultiCameraClient not initialized");
        }
        return true;
    }

    private boolean showPreview(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options != null) {
            previewViewX = Math.max(0, options.optInt("x", previewViewX));
            previewViewY = Math.max(0, options.optInt("y", previewViewY));
            previewViewWidth = Math.max(1, options.optInt("width", previewViewWidth));
            previewViewHeight = Math.max(1, options.optInt("height", previewViewHeight));
        }
        previewVisible = true;
        Log.i(TAG, "showPreview requested x=" + previewViewX + ", y=" + previewViewY + ", width=" + previewViewWidth + ", height=" + previewViewHeight);
        cordova.getActivity().runOnUiThread(() -> {
            ensurePreviewView();
            applyPreviewLayout();
            JSONObject result = new JSONObject();
            try {
                result.put("visible", true);
                result.put("x", previewViewX);
                result.put("y", previewViewY);
                result.put("width", previewViewWidth);
                result.put("height", previewViewHeight);
            } catch (JSONException ignored) {
            }
            callbackContext.success(result);
        });
        return true;
    }

    private boolean hidePreview(CallbackContext callbackContext) {
        previewVisible = false;
        Log.i(TAG, "hidePreview requested");
        cordova.getActivity().runOnUiThread(() -> {
            ensurePreviewView();
            applyPreviewLayout();
            callbackContext.success("preview-hidden");
        });
        return true;
    }

    private boolean updatePreviewBounds(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options == null) {
            callbackContext.error("Preview bounds options are required");
            return true;
        }
        previewViewX = Math.max(0, options.optInt("x", previewViewX));
        previewViewY = Math.max(0, options.optInt("y", previewViewY));
        previewViewWidth = Math.max(1, options.optInt("width", previewViewWidth));
        previewViewHeight = Math.max(1, options.optInt("height", previewViewHeight));
        Log.i(TAG, "updatePreviewBounds requested x=" + previewViewX + ", y=" + previewViewY + ", width=" + previewViewWidth + ", height=" + previewViewHeight);
        cordova.getActivity().runOnUiThread(() -> {
            ensurePreviewView();
            applyPreviewLayout();
            JSONObject result = new JSONObject();
            try {
                result.put("visible", previewVisible);
                result.put("x", previewViewX);
                result.put("y", previewViewY);
                result.put("width", previewViewWidth);
                result.put("height", previewViewHeight);
            } catch (JSONException ignored) {
            }
            callbackContext.success(result);
        });
        return true;
    }

    private boolean takePhoto(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            photoCallback = callbackContext;
            String fileName = "USB_UVC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".jpg";
            File baseDir = cordova.getActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (baseDir == null) {
                callbackContext.error("External files directory not available");
                photoCallback = null;
                return;
            }
            File storageDir = new File(baseDir, "UsbUvcCamera");
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }
            File photoFile = new File(storageDir, fileName);

            mainHandler.post(this::schedulePhotoTimeout);
            attemptHighResTakePhoto(photoFile, 1);
        });
        return true;
    }

    private void attemptHighResTakePhoto(File photoFile, int attempt) {
        refreshCurrentDeviceReference();
        if (currentCamera == null) {
            if (currentDevice != null && attempt < MAX_TAKE_PHOTO_ATTEMPTS) {
                Log.d(TAG, "Camera instance missing before high-res photo, trying reopen, attempt " + attempt);
                ensureCameraClient();
                safeRegisterCameraClient();
                cameraClient.requestPermission(currentDevice);
                mainHandler.postDelayed(() -> attemptHighResTakePhoto(photoFile, attempt + 1), TAKE_PHOTO_RETRY_DELAY_MS);
                return;
            }
            failPendingPhoto("USB UVC camera not initialized");
            return;
        }

        if (!currentCamera.isCameraOpened()) {
            if (attempt >= MAX_TAKE_PHOTO_ATTEMPTS) {
                Log.w(TAG, "Camera still not opened before high-res photo after retries");
                failPendingPhoto("USB UVC camera not opened");
                return;
            }
            Log.d(TAG, "Camera not ready for high-res photo yet, retry attempt " + attempt);
            mainHandler.postDelayed(() -> attemptHighResTakePhoto(photoFile, attempt + 1), TAKE_PHOTO_RETRY_DELAY_MS);
            return;
        }

        int[] negotiatedPreviewSize = getNegotiatedPreviewSize();
        if (negotiatedPreviewSize[0] > 0 && negotiatedPreviewSize[1] > 0) {
            int negotiatedPixels = negotiatedPreviewSize[0] * negotiatedPreviewSize[1];
            int requestedPixels = Math.max(1, requestedPreviewWidth) * Math.max(1, requestedPreviewHeight);
            if (negotiatedPixels >= requestedPixels || negotiatedPixels > (640 * 480)) {
                Log.i(TAG, "Skipping captureImage backend because negotiated preview stream is already high-res: "
                        + negotiatedPreviewSize[0] + "x" + negotiatedPreviewSize[1]);
                mainHandler.post(() -> {
                    schedulePhotoTimeout();
                    attemptTakePhoto(photoFile, 1);
                });
                return;
            }
        }

        if (photoFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            photoFile.delete();
        }

        ensureHighResPhotoCaptureBackend();
        HighResPhotoCaptureBackend backend = highResPhotoCaptureBackend;
        if (backend == null || currentDevice == null || !backend.supportsDevice(currentDevice)) {
            Log.w(TAG, "No high-res photo backend available, falling back to preview frame");
            attemptTakePhoto(photoFile, 1);
            return;
        }

        int[] preferredStillCaptureSize = resolvePreferredStillCaptureSize(negotiatedPreviewSize);

        HighResPhotoRequest request = new HighResPhotoRequest(
                preferredStillCaptureSize[0],
                preferredStillCaptureSize[1],
                100,
                "uvc:" + currentDevice.getVendorId() + ":" + currentDevice.getProductId(),
                photoFile.getAbsolutePath(),
                HIGH_RES_CAPTURE_TIMEOUT_MS
        );

        cordova.getThreadPool().execute(() -> {
            try {
                HighResPhotoResult result = backend.capture(currentDevice, request);
                mainHandler.post(() -> {
                    clearPhotoTimeout();
                    if (photoCallback != null) {
                        photoCallback.success(result.getBase64Jpeg());
                        photoCallback = null;
                    }
                });
            } catch (Exception exception) {
                Log.w(TAG, "High-res backend failed, falling back to preview frame", exception);
                mainHandler.post(() -> {
                    if (photoCallback == null) {
                        Log.w(TAG, "Skipping preview fallback because photo callback has already been resolved");
                        return;
                    }
                    schedulePhotoTimeout();
                    attemptTakePhoto(photoFile, 1);
                });
            }
        });
    }

    private void attemptTakePhoto(File photoFile, int attempt) {
        refreshCurrentDeviceReference();
        if (currentCamera == null) {
            if (currentDevice != null && attempt < MAX_TAKE_PHOTO_ATTEMPTS) {
                Log.d(TAG, "Camera instance missing, trying to reopen device before photo, attempt " + attempt);
                ensureCameraClient();
                safeRegisterCameraClient();
                cameraClient.requestPermission(currentDevice);
                mainHandler.postDelayed(() -> attemptTakePhoto(photoFile, attempt + 1), TAKE_PHOTO_RETRY_DELAY_MS);
                return;
            }
            failPendingPhoto("USB UVC camera not initialized");
            return;
        }

        if (!currentCamera.isCameraOpened()) {
            if (attempt >= MAX_TAKE_PHOTO_ATTEMPTS) {
                Log.w(TAG, "Camera still not opened after retries");
                failPendingPhoto("USB UVC camera not opened");
                return;
            }
            Log.d(TAG, "Camera not ready for photo yet, retry attempt " + attempt);
            mainHandler.postDelayed(() -> attemptTakePhoto(photoFile, attempt + 1), TAKE_PHOTO_RETRY_DELAY_MS);
            return;
        }

        byte[] frameCopy;
        int frameWidth;
        int frameHeight;
        boolean frameFromUnderlying;
        String frameFormat;
        synchronized (previewFrameLock) {
            frameCopy = latestPreviewFrame != null ? latestPreviewFrame.clone() : null;
            frameWidth = latestPreviewFrameWidth;
            frameHeight = latestPreviewFrameHeight;
            frameFromUnderlying = latestPreviewFrameFromUnderlying;
            frameFormat = latestPreviewFrameFormat;
        }

        if (frameCopy == null) {
            if (attempt < MAX_TAKE_PHOTO_ATTEMPTS) {
                Log.d(TAG, "Waiting for AUSBC preview frame before retrying takePhoto");
                Log.d(TAG, "Preview frame not ready yet, retry attempt " + attempt);
                mainHandler.postDelayed(() -> attemptTakePhoto(photoFile, attempt + 1), TAKE_PHOTO_RETRY_DELAY_MS);
                return;
            }

            Log.w(TAG, "No camera-backed preview frame available after retries");
            failPendingPhoto("No camera-backed preview frame available");
            return;
        }

        PreviewSize frameSize;
        if (frameWidth > 0 && frameHeight > 0) {
            frameSize = new PreviewSize(frameWidth, frameHeight);
        } else {
            frameSize = resolvePreviewSizeForFrame(frameCopy.length);
        }
        if (frameSize == null) {
            Log.e(TAG, "Unable to resolve preview size for frame length " + frameCopy.length);
            failPendingPhoto("Unable to resolve preview size");
            return;
        }

        int[] negotiatedPreviewSize = getNegotiatedPreviewSize();
        int textureCaptureWidth = negotiatedPreviewSize[0] > 0 ? negotiatedPreviewSize[0] : frameSize.getWidth();
        int textureCaptureHeight = negotiatedPreviewSize[1] > 0 ? negotiatedPreviewSize[1] : frameSize.getHeight();
        if (!loggedPhotoSourceMetrics) {
            loggedPhotoSourceMetrics = true;
            Log.i(TAG, "Photo source metrics negotiatedPreview="
                    + negotiatedPreviewSize[0] + "x" + negotiatedPreviewSize[1]
                    + ", latestPreviewFrame=" + frameSize.getWidth() + "x" + frameSize.getHeight()
                    + ", latestPreviewFormat=" + frameFormat
                    + ", latestPreviewFromUnderlying=" + frameFromUnderlying
                    + ", textureView=" + (previewView != null ? (previewView.getWidth() + "x" + previewView.getHeight()) : "null"));
        }
        capturePreviewTextureOrRawFallback(textureCaptureWidth, textureCaptureHeight, frameCopy, frameSize, frameFromUnderlying, frameFormat);
    }

    private void failPendingPhoto(String message) {
        clearPhotoTimeout();
        if (photoCallback != null) {
            photoCallback.error(message);
            photoCallback = null;
        }
    }

    private void capturePreviewTextureOrRawFallback(int textureCaptureWidth, int textureCaptureHeight,
            byte[] frameCopy, PreviewSize frameSize, boolean frameFromUnderlying, String frameFormat) {
        capturePreviewTextureAsBase64Async(textureCaptureWidth, textureCaptureHeight, textureEncodedImage -> {
            if (textureEncodedImage != null) {
                clearPhotoTimeout();
                if (photoCallback != null) {
                    photoCallback.success(textureEncodedImage);
                    photoCallback = null;
                }
                return;
            }
            encodeRawPreviewFrameFallback(frameCopy, frameSize, frameFromUnderlying, frameFormat);
        });
    }

    private void encodeRawPreviewFrameFallback(byte[] frameCopy, PreviewSize frameSize, boolean frameFromUnderlying, String frameFormat) {
        final byte[] encodedFrameData;
        if (frameFromUnderlying) {
            if ("yuyv".equals(frameFormat)) {
                encodedFrameData = convertYuyvToNv21(frameCopy, frameSize.getWidth(), frameSize.getHeight());
            } else {
                encodedFrameData = convertNv12ToNv21(frameCopy, frameSize.getWidth(), frameSize.getHeight());
            }
        } else {
            encodedFrameData = frameCopy;
        }
        cordova.getThreadPool().execute(() -> {
            String encodedImage = encodePreviewFrameAsBase64(
                    encodedFrameData,
                    frameSize.getWidth(),
                    frameSize.getHeight()
            );
            mainHandler.post(() -> {
                if (encodedImage == null) {
                    Log.e(TAG, "Preview frame base64 encoding failed");
                    failPendingPhoto("Failed to encode preview frame");
                    return;
                }
                clearPhotoTimeout();
                if (photoCallback != null) {
                    photoCallback.success(encodedImage);
                    photoCallback = null;
                }
            });
        });
    }

    private void capturePreviewTextureAsBase64Async(int width, int height, TextureCaptureCallback callback) {
        if (callback == null) {
            return;
        }
        Runnable armCapture = () -> {
            if (previewView == null || width <= 0 || height <= 0) {
                callback.onCaptured(null);
                return;
            }
            if (!previewView.isAvailable()) {
                Log.d(TAG, "Skipping TextureView capture because previewView is not available");
                callback.onCaptured(null);
                return;
            }
            clearPendingSurfaceTextureCapture();
            AtomicReference<Boolean> completed = new AtomicReference<>(false);
            Runnable performCapture = () -> {
                if (Boolean.TRUE.equals(completed.get())) {
                    return;
                }
                completed.set(true);
                callback.onCaptured(capturePreviewTextureAsBase64OnMainThread(width, height));
            };
            pendingSurfaceTextureUpdatedAction = performCapture;
            pendingSurfaceTextureUpdatedTimeout = () -> {
                pendingSurfaceTextureUpdatedAction = null;
                pendingSurfaceTextureUpdatedTimeout = null;
                performCapture.run();
            };
            mainHandler.postDelayed(pendingSurfaceTextureUpdatedTimeout, 700);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            armCapture.run();
        } else {
            mainHandler.post(armCapture);
        }
    }

    private void clearPendingSurfaceTextureCapture() {
        pendingSurfaceTextureUpdatedAction = null;
        if (pendingSurfaceTextureUpdatedTimeout != null) {
            mainHandler.removeCallbacks(pendingSurfaceTextureUpdatedTimeout);
            pendingSurfaceTextureUpdatedTimeout = null;
        }
    }

    private String capturePreviewTextureAsBase64OnMainThread(int width, int height) {
        Bitmap bitmap = null;
        try {
            if (previewView == null || !previewView.isAvailable()) {
                Log.d(TAG, "Skipping TextureView capture because previewView is not available");
                return null;
            }
            boolean canCaptureRequestedSize = previewView.getWidth() >= width && previewView.getHeight() >= height;
            if (canCaptureRequestedSize) {
                bitmap = previewView.getBitmap(width, height);
            }
            if (bitmap == null) {
                bitmap = previewView.getBitmap();
            }
            if (bitmap == null) {
                Log.d(TAG, "TextureView bitmap capture returned null for view size "
                        + previewView.getWidth() + "x" + previewView.getHeight()
                        + ", requestedTarget=" + width + "x" + height);
            }
            if (bitmap == null) {
                return null;
            }
            if (!loggedTextureCaptureMetrics) {
                loggedTextureCaptureMetrics = true;
                Log.i(TAG, "Texture capture metrics view="
                        + previewView.getWidth() + "x" + previewView.getHeight()
                        + ", requestedTarget=" + width + "x" + height
                        + ", bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + ", usedRequestedSize=" + canCaptureRequestedSize);
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                if (compressed) {
                    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
                }
                return null;
            } finally {
                try {
                    outputStream.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception exception) {
            Log.w(TAG, "Unable to capture preview TextureView bitmap", exception);
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }


    private void schedulePhotoTimeout() {
        clearPhotoTimeout();
        pendingPhotoTimeout = () -> {
            Log.e(TAG, "UVC capture timeout after " + TAKE_PHOTO_TIMEOUT_MS + " ms");
            failPendingPhoto("UVC capture timeout");
        };
        mainHandler.postDelayed(pendingPhotoTimeout, TAKE_PHOTO_TIMEOUT_MS);
    }

    private void clearPhotoTimeout() {
        if (pendingPhotoTimeout != null) {
            mainHandler.removeCallbacks(pendingPhotoTimeout);
            pendingPhotoTimeout = null;
        }
    }

    private int[] getNegotiatedPreviewSize() {
        try {
            UVCCamera uvcCamera = getUnderlyingUvcCamera();
            if (uvcCamera == null) {
                return new int[] { -1, -1 };
            }
            Object previewSize = uvcCamera.getPreviewSize();
            if (previewSize == null) {
                return new int[] { -1, -1 };
            }

            Integer width = extractDimension(previewSize, "width", "mWidth");
            Integer height = extractDimension(previewSize, "height", "mHeight");
            if (width != null && height != null) {
                return new int[] { width, height };
            }

            String serialized = previewSize.toString();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(serialized);
            if (matcher.find()) {
                return new int[] {
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                };
            }
        } catch (Exception exception) {
            Log.w(TAG, "Unable to read negotiated preview size", exception);
        }
        return new int[] { -1, -1 };
    }

    private Integer extractDimension(Object previewSize, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = previewSize.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(previewSize);
                if (value instanceof Integer) {
                    return (Integer) value;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean listUsbDevices(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
                if (usbManager == null) {
                    callbackContext.error("UsbManager not available");
                    return;
                }

                JSONArray devices = new JSONArray();
                HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
                for (UsbDevice device : deviceList.values()) {
                    JSONObject json = new JSONObject();
                    json.put("deviceName", device.getDeviceName());
                    json.put("productName", device.getProductName());
                    json.put("manufacturerName", device.getManufacturerName());
                    json.put("vendorId", device.getVendorId());
                    json.put("productId", device.getProductId());
                    json.put("interfaceCount", device.getInterfaceCount());
                    json.put("isLogitech", device.getVendorId() == 0x046d);

                    boolean hasVideoInterface = false;
                    JSONArray interfaces = new JSONArray();
                    for (int i = 0; i < device.getInterfaceCount(); i++) {
                        UsbInterface usbInterface = device.getInterface(i);
                        JSONObject interfaceJson = new JSONObject();
                        interfaceJson.put("id", usbInterface.getId());
                        interfaceJson.put("interfaceClass", usbInterface.getInterfaceClass());
                        interfaceJson.put("interfaceSubclass", usbInterface.getInterfaceSubclass());
                        interfaceJson.put("interfaceProtocol", usbInterface.getInterfaceProtocol());
                        if (usbInterface.getInterfaceClass() == 14) {
                            hasVideoInterface = true;
                        }
                        interfaces.put(interfaceJson);
                    }

                    json.put("hasVideoInterface", hasVideoInterface);
                    json.put("interfaces", interfaces);
                    devices.put(json);
                }

                callbackContext.success(devices);
            } catch (Exception e) {
                Log.e(TAG, "Error listing USB devices", e);
                callbackContext.error("Error listing USB devices: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean getCameraCapabilities(CallbackContext callbackContext) {
        try {
            if (currentCamera == null || !currentCamera.isCameraOpened()) {
                callbackContext.error("USB UVC camera not opened");
                return true;
            }

            UVCCamera uvcCamera = getUnderlyingUvcCamera();
            if (uvcCamera == null) {
                callbackContext.error("Underlying UVCCamera not available");
                return true;
            }

            JSONObject result = new JSONObject();
            result.put("deviceName", currentDevice != null ? currentDevice.getDeviceName() : JSONObject.NULL);
            result.put("vendorId", currentDevice != null ? currentDevice.getVendorId() : JSONObject.NULL);
            result.put("productId", currentDevice != null ? currentDevice.getProductId() : JSONObject.NULL);

            JSONObject support = new JSONObject();
            support.put("focusAuto", uvcCamera.checkSupportFlag(UVCCamera.CTRL_FOCUS_AUTO));
            support.put("focusAbsolute", uvcCamera.checkSupportFlag(UVCCamera.CTRL_FOCUS_ABS));
            support.put("zoomAbsolute", uvcCamera.checkSupportFlag(UVCCamera.CTRL_ZOOM_ABS));
            support.put("exposureAuto", uvcCamera.checkSupportFlag(UVCCamera.CTRL_AE));
            support.put("exposureAbsolute", uvcCamera.checkSupportFlag(UVCCamera.CTRL_AE_ABS));
            support.put("brightness", uvcCamera.checkSupportFlag(UVCCamera.PU_BRIGHTNESS));
            support.put("contrast", uvcCamera.checkSupportFlag(UVCCamera.PU_CONTRAST));
            support.put("sharpness", uvcCamera.checkSupportFlag(UVCCamera.PU_SHARPNESS));
            support.put("gamma", uvcCamera.checkSupportFlag(UVCCamera.PU_GAMMA));
            support.put("saturation", uvcCamera.checkSupportFlag(UVCCamera.PU_SATURATION));
            support.put("hue", uvcCamera.checkSupportFlag(UVCCamera.PU_HUE));
            support.put("whiteBalanceAuto", uvcCamera.checkSupportFlag(UVCCamera.PU_WB_TEMP_AUTO));
            support.put("whiteBalance", uvcCamera.checkSupportFlag(UVCCamera.PU_WB_TEMP));
            support.put("backlight", uvcCamera.checkSupportFlag(UVCCamera.PU_BACKLIGHT));
            support.put("powerLineFrequency", uvcCamera.checkSupportFlag(UVCCamera.PU_POWER_LF));
            result.put("support", support);

            JSONObject current = new JSONObject();
            current.put("autoFocus", uvcCamera.getAutoFocus());
            current.put("focus", uvcCamera.getFocus());
            current.put("zoom", uvcCamera.getZoom());
            current.put("autoExposure", getAutoExposure(uvcCamera));
            current.put("exposure", getExposurePercent(uvcCamera));
            current.put("brightness", uvcCamera.getBrightness());
            current.put("contrast", uvcCamera.getContrast());
            current.put("sharpness", uvcCamera.getSharpness());
            current.put("gamma", uvcCamera.getGamma());
            current.put("saturation", uvcCamera.getSaturation());
            current.put("hue", uvcCamera.getHue());
            current.put("autoWhiteBalance", uvcCamera.getAutoWhiteBlance());
            current.put("whiteBalance", uvcCamera.getWhiteBlance());
            current.put("powerLineFrequency", uvcCamera.getPowerlineFrequency());
            result.put("current", current);

            JSONObject ranges = new JSONObject();
            ranges.put("focus", buildRangeJson(uvcCamera, "mFocusMin", "mFocusMax", "mFocusDef"));
            ranges.put("zoom", buildRangeJson(uvcCamera, "mZoomMin", "mZoomMax", "mZoomDef"));
            ranges.put("exposure", buildRangeJson(uvcCamera, "mExposureMin", "mExposureMax", "mExposureDef"));
            ranges.put("brightness", buildRangeJson(uvcCamera, "mBrightnessMin", "mBrightnessMax", "mBrightnessDef"));
            ranges.put("contrast", buildRangeJson(uvcCamera, "mContrastMin", "mContrastMax", "mContrastDef"));
            ranges.put("sharpness", buildRangeJson(uvcCamera, "mSharpnessMin", "mSharpnessMax", "mSharpnessDef"));
            ranges.put("gamma", buildRangeJson(uvcCamera, "mGammaMin", "mGammaMax", "mGammaDef"));
            ranges.put("saturation", buildRangeJson(uvcCamera, "mSaturationMin", "mSaturationMax", "mSaturationDef"));
            ranges.put("hue", buildRangeJson(uvcCamera, "mHueMin", "mHueMax", "mHueDef"));
            ranges.put("whiteBalance", buildRangeJson(uvcCamera, "mWhiteBlanceMin", "mWhiteBlanceMax", "mWhiteBlanceDef"));
            result.put("ranges", ranges);

            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "getCameraCapabilities failed", exception);
            callbackContext.error("getCameraCapabilities failed: " + exception.getMessage());
        }
        return true;
    }

    private boolean inspectUvcDescriptors(CallbackContext callbackContext) {
        UsbDeviceConnection connection = null;
        try {
            UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                callbackContext.error("UsbManager not available");
                return true;
            }

            UsbDevice device = currentDevice != null ? currentDevice : selectPreferredDevice(null);
            if (device == null) {
                callbackContext.error("No compatible USB UVC camera found");
                return true;
            }

            connection = usbManager.openDevice(device);
            if (connection == null) {
                callbackContext.error("Unable to open USB device for descriptor inspection");
                return true;
            }

            byte[] rawDescriptors = connection.getRawDescriptors();
            if (rawDescriptors == null || rawDescriptors.length == 0) {
                callbackContext.error("USB raw descriptors not available");
                return true;
            }

            JSONObject result = new JSONObject();
            result.put("deviceName", device.getDeviceName());
            result.put("vendorId", device.getVendorId());
            result.put("productId", device.getProductId());
            result.put("rawDescriptorLength", rawDescriptors.length);
            result.put("interfaces", buildInterfaceSummary(device));

            ParsedUvcDescriptors parsed = parseUvcDescriptors(rawDescriptors);
            result.put("videoControlInterfaceCount", parsed.videoControlInterfaceCount);
            result.put("videoStreamingInterfaceCount", parsed.videoStreamingInterfaceCount);
            result.put("stillImageDescriptorCount", parsed.stillImageDescriptorCount);
            result.put("formatDescriptorCount", parsed.formatDescriptorCount);
            result.put("frameDescriptorCount", parsed.frameDescriptorCount);
            result.put("frameFormats", new JSONArray(parsed.frameFormats));
            result.put("frameSizes", new JSONArray(parsed.frameSizes));
            result.put("descriptorSubtypes", new JSONArray(parsed.descriptorSubtypes));
            result.put("hasStillImageDescriptor", parsed.stillImageDescriptorCount > 0);
            result.put("canAttemptNativeStillPath", parsed.stillImageDescriptorCount > 0);
            result.put("notes", buildDescriptorNotes(parsed));

            Log.i(TAG, "inspectUvcDescriptors result: stillImageDescriptorCount=" + parsed.stillImageDescriptorCount
                    + ", frameDescriptorCount=" + parsed.frameDescriptorCount
                    + ", frameSizes=" + parsed.frameSizes);
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "inspectUvcDescriptors failed", exception);
            callbackContext.error("inspectUvcDescriptors failed: " + exception.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
        return true;
    }

    private boolean inspectBackendApi(CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            result.put("cameraRequestBuilderMethods", collectMatchingMethodNames(CameraRequest.Builder.class,
                    "preview", "format", "raw", "capture", "frame", "mjpeg", "yuv", "render"));
            result.put("multiCameraClientCameraMethods", collectMatchingMethodNames(MultiCameraClient.Camera.class,
                    "preview", "format", "raw", "capture", "frame", "mjpeg", "yuv", "render", "size"));
            result.put("uvcCameraMethods", collectMatchingMethodNames(UVCCamera.class,
                    "preview", "format", "raw", "capture", "frame", "mjpeg", "yuv", "size", "mode"));
            result.put("uvcCameraFields", collectMatchingFieldNames(UVCCamera.class,
                    "preview", "format", "raw", "capture", "frame", "mjpeg", "yuv", "size", "mode"));
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "inspectBackendApi failed", exception);
            callbackContext.error("inspectBackendApi failed: " + exception.getMessage());
        }
        return true;
    }

    private void logBackendApiSnapshotOnce() {
        if (loggedBackendApiSnapshot) {
            return;
        }
        loggedBackendApiSnapshot = true;
    }

    private boolean applyStableCameraProfile(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }

            JSONObject options = args.optJSONObject(0);
            boolean autoFocus = options != null && options.has("autoFocus") ? options.optBoolean("autoFocus", false) : false;
            int focus = options != null ? clampPercent(options.optInt("focus", 0)) : 0;
            boolean smartFocus = options == null || !options.has("smartFocus") || options.optBoolean("smartFocus", true);
            int focusLockDelayMs = options != null ? Math.max(300, options.optInt("focusLockDelayMs", DEFAULT_SMART_FOCUS_LOCK_DELAY_MS)) : DEFAULT_SMART_FOCUS_LOCK_DELAY_MS;
            boolean autoExposure = options != null && options.has("autoExposure") ? options.optBoolean("autoExposure", true) : true;
            int exposure = options != null ? clampPercent(options.optInt("exposure", 50)) : 50;
            boolean autoWhiteBalance = options != null && options.has("autoWhiteBalance") ? options.optBoolean("autoWhiteBalance", true) : true;
            int whiteBalance = options != null ? clampPercent(options.optInt("whiteBalance", 50)) : 50;

            smartFocusEnabled = smartFocus;
            smartFocusLockDelayMs = focusLockDelayMs;

            uvcCamera.setAutoFocus(autoFocus);
            if (!autoFocus) {
                uvcCamera.setFocus(focus);
                persistLockedFocus(focus);
            }
            setAutoExposureInternal(uvcCamera, autoExposure);
            if (!autoExposure) {
                setExposureInternal(uvcCamera, exposure);
            }
            uvcCamera.setAutoWhiteBlance(autoWhiteBalance);
            if (!autoWhiteBalance) {
                uvcCamera.setWhiteBlance(whiteBalance);
            }

            JSONObject result = new JSONObject();
            result.put("smartFocus", smartFocusEnabled);
            result.put("focusLockDelayMs", smartFocusLockDelayMs);
            result.put("autoFocus", autoFocus);
            result.put("focus", autoFocus ? getLastLockedFocus() : focus);
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "applyStableCameraProfile failed", exception);
            callbackContext.error("applyStableCameraProfile failed: " + exception.getMessage());
        }
        return true;
    }

    private void ensureCameraClient() {
        if (cameraClient != null) {
            return;
        }
        cameraClient = new MultiCameraClient(cordova.getActivity(), new IDeviceConnectCallBack() {
            @Override
            public void onAttachDev(UsbDevice device) {
                Log.d(TAG, "USB attach: " + (device != null ? device.getDeviceName() : "null"));
                if (autoReconnectEnabled && currentCamera == null) {
                    refreshCurrentDeviceReference();
                    scheduleReconnect();
                }
            }

            @Override
            public void onDetachDec(UsbDevice device) {
                Log.d(TAG, "USB detach: " + (device != null ? device.getDeviceName() : "null"));
                if (device != null && currentDevice != null && device.getDeviceId() == currentDevice.getDeviceId()) {
                    if (openingCamera) {
                        Log.w(TAG, "Ignoring detach callback while camera is opening");
                        return;
                    }
                    releaseCamera();
                    scheduleReconnect();
                }
            }

            @Override
            public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                if (device == null || ctrlBlock == null) {
                    Log.w(TAG, "onConnectDev ignored because device or ctrlBlock is null");
                    return;
                }
                if (currentDevice == null || device.getDeviceId() != currentDevice.getDeviceId()) {
                    Log.d(TAG, "onConnectDev ignored for non-selected device: " + device.getDeviceName());
                    return;
                }
                Log.i(TAG, "onConnectDev for selected device: " + device.getDeviceName());
                pendingOpenDevice = device;
                pendingCtrlBlock = ctrlBlock;
                maybeOpenPendingDevice();
            }

            @Override
            public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                Log.d(TAG, "USB disconnect callback: " + (device != null ? device.getDeviceName() : "null"));
                if (device != null && currentDevice != null && device.getDeviceId() == currentDevice.getDeviceId()) {
                    if (openingCamera) {
                        Log.w(TAG, "Ignoring disconnect callback while camera is opening");
                        return;
                    }
                    releaseCamera();
                    scheduleReconnect();
                }
            }

            @Override
            public void onCancelDev(UsbDevice device) {
                openingCamera = false;
                if (openCallback != null) {
                    openCallback.error("USB permission request canceled");
                    openCallback = null;
                }
            }
        });
        safeRegisterCameraClient();
    }

    private void ensureHighResPhotoCaptureBackend() {
        if (highResPhotoCaptureBackend != null) {
            return;
        }
        List<HighResPhotoCaptureBackend> backends = new ArrayList<>();
        backends.add(new AusbcHighResPhotoCaptureBackend(new AusbcCameraHandleProvider() {
            @Override
            public MultiCameraClient.Camera getCurrentCamera() {
                return currentCamera;
            }
        }));
        highResPhotoCaptureBackend = new CompositeHighResPhotoCaptureBackend(backends);
        try {
            highResPhotoCaptureBackend.initialize();
        } catch (Exception exception) {
            Log.w(TAG, "High-res photo backend initialization failed", exception);
            highResPhotoCaptureBackend = null;
        }
    }

    private void ensurePreviewView() {
        if (previewView != null) {
            previewSurfaceReady = previewView.isAvailable();
            if (previewSurfaceReady) {
                Log.d(TAG, "ensurePreviewView found existing available preview surface");
                maybeOpenPendingDevice();
            }
            return;
        }
        previewView = new AspectRatioTextureView(cordova.getActivity());
        previewView.setAlpha(1.0f);
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                updatePreviewSurfaceDefaultBufferSize();
                previewSurfaceReady = true;
                maybeOpenPendingDevice();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                updatePreviewSurfaceDefaultBufferSize();
                previewSurfaceReady = true;
                maybeOpenPendingDevice();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                previewSurfaceReady = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                if (pendingSurfaceTextureUpdatedAction != null) {
                    Runnable action = pendingSurfaceTextureUpdatedAction;
                    pendingSurfaceTextureUpdatedAction = null;
                    if (pendingSurfaceTextureUpdatedTimeout != null) {
                        mainHandler.removeCallbacks(pendingSurfaceTextureUpdatedTimeout);
                        pendingSurfaceTextureUpdatedTimeout = null;
                    }
                    action.run();
                }
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM | Gravity.END);
        ViewGroup root = cordova.getActivity().findViewById(android.R.id.content);
        if (root instanceof FrameLayout) {
            ((FrameLayout) root).addView(previewView, params);
            previewContainer = root;
        } else if (root != null) {
            root.addView(previewView, params);
            previewContainer = root;
        }
        applyPreviewLayout();
        mainHandler.post(() -> {
            if (previewView != null && previewView.isAvailable()) {
                previewSurfaceReady = true;
                Log.d(TAG, "Preview surface became available immediately after attach");
                maybeOpenPendingDevice();
            } else {
                Log.d(TAG, "Preview surface still not available after attach");
            }
        });
    }

    private void applyPreviewLayout() {
        if (previewView == null || previewContainer == null) {
            return;
        }

        int hiddenSurfaceWidth = Math.max(Math.max(requestedPreviewWidth, previewWidth), STABLE_CAPTURE_WIDTH);
        int hiddenSurfaceHeight = Math.max(Math.max(requestedPreviewHeight, previewHeight), STABLE_CAPTURE_HEIGHT);
        int width = previewVisible ? previewViewWidth : hiddenSurfaceWidth;
        int height = previewVisible ? previewViewHeight : hiddenSurfaceHeight;
        int leftMargin = previewVisible ? previewViewX : -hiddenSurfaceWidth;
        int topMargin = previewVisible ? previewViewY : 0;
        float alpha = 1.0f;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = leftMargin;
        params.topMargin = topMargin;

        // Keep the TextureView attached and opaque to preserve a usable
        // SurfaceTexture/bitmap source even when the preview is hidden offscreen.
        previewView.setAlpha(alpha);
        previewView.setVisibility(View.VISIBLE);
        previewView.setLayoutParams(params);
        if (Build.VERSION.SDK_INT >= 21) {
            previewView.setElevation(10000f);
        }
        if (previewContainer instanceof FrameLayout) {
            ((FrameLayout) previewContainer).bringChildToFront(previewView);
        }
        previewView.bringToFront();
        previewView.requestLayout();
        updatePreviewSurfaceDefaultBufferSize();
        Log.i(TAG, "applyPreviewLayout visible=" + previewVisible + ", x=" + leftMargin + ", y=" + topMargin + ", width=" + width + ", height=" + height);
    }

    private void updatePreviewSurfaceDefaultBufferSize() {
        if (previewView == null || !previewView.isAvailable()) {
            return;
        }
        try {
            SurfaceTexture surfaceTexture = previewView.getSurfaceTexture();
            if (surfaceTexture == null) {
                return;
            }
            int targetWidth = Math.max(previewWidth, Math.max(requestedPreviewWidth, STABLE_CAPTURE_WIDTH));
            int targetHeight = Math.max(previewHeight, Math.max(requestedPreviewHeight, STABLE_CAPTURE_HEIGHT));
            surfaceTexture.setDefaultBufferSize(targetWidth, targetHeight);
            Log.i(TAG, "Updated preview SurfaceTexture default buffer size to " + targetWidth + "x" + targetHeight);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to update preview SurfaceTexture default buffer size", exception);
        }
    }

    private UsbDevice selectPreferredDevice(JSONObject options) {
        if (cameraClient == null) {
            return null;
        }
        List<UsbDevice> devices = cameraClient.getDeviceList(null);
        if (devices == null || devices.isEmpty()) {
            return null;
        }

        String preferredId = options != null ? options.optString("cameraId", null) : null;
        if (preferredId != null && preferredId.startsWith("uvc:")) {
            String[] parts = preferredId.split(":");
            if (parts.length == 3) {
                try {
                    int vendorId = Integer.parseInt(parts[1]);
                    int productId = Integer.parseInt(parts[2]);
                    for (UsbDevice device : devices) {
                        if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                            return device;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        for (UsbDevice device : devices) {
            if (device.getVendorId() == 0x046d) {
                return device;
            }
        }

        for (UsbDevice device : devices) {
            if (hasVideoInterface(device)) {
                return device;
            }
        }
        return null;
    }

    private void refreshCurrentDeviceReference() {
        UsbDevice refreshed = resolveReconnectTargetDevice();
        if (refreshed != null) {
            currentDevice = refreshed;
        }
    }

    private UsbDevice resolveReconnectTargetDevice() {
        if (cameraClient == null) {
            return null;
        }
        List<UsbDevice> devices = cameraClient.getDeviceList(null);
        if (devices == null || devices.isEmpty()) {
            return null;
        }

        if (preferredVendorId > -1 && preferredProductId > -1) {
            for (UsbDevice device : devices) {
                if (device.getVendorId() == preferredVendorId && device.getProductId() == preferredProductId) {
                    return device;
                }
            }
        }

        if (currentDevice != null) {
            for (UsbDevice device : devices) {
                if (device.getVendorId() == currentDevice.getVendorId() && device.getProductId() == currentDevice.getProductId()) {
                    return device;
                }
            }
        }

        for (UsbDevice device : devices) {
            if (device.getVendorId() == 0x046d) {
                return device;
            }
        }

        for (UsbDevice device : devices) {
            if (hasVideoInterface(device)) {
                return device;
            }
        }

        return null;
    }

    private void openConnectedDevice(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        try {
            ensurePreviewView();
            if (!previewSurfaceReady) {
                pendingOpenDevice = device;
                pendingCtrlBlock = ctrlBlock;
                Log.d(TAG, "Preview surface not ready yet, delaying UVC open");
                return;
            }
            closeCurrentCamera(false);
            Log.i(TAG, "Creating MultiCameraClient.Camera for device: " + device.getDeviceName());
            currentCamera = new MultiCameraClient.Camera(cordova.getActivity(), device);
            List<PreviewSize> initialPreviewSizes = currentCamera.getAllPreviewSizes(null);
            logPreviewSizes("initial-preview-sizes", initialPreviewSizes);
            PreviewSize targetPreviewSize = resolveTargetPreviewSize(initialPreviewSizes);
            if (targetPreviewSize != null) {
                previewWidth = targetPreviewSize.getWidth();
                previewHeight = targetPreviewSize.getHeight();
                Log.i(TAG, "Using target preview size " + previewWidth + "x" + previewHeight + " for open");
            }
            updatePreviewSurfaceDefaultBufferSize();
            currentCamera.addPreviewDataCallBack(new IPreviewDataCallBack() {
                @Override
                public void onPreviewData(byte[] data, DataFormat format) {
                    if (data == null || format != DataFormat.NV21) {
                        return;
                    }
                    PreviewSize callbackFrameSize = resolvePreviewCallbackFrameSize(data.length);
                    int callbackFrameWidth = callbackFrameSize != null ? callbackFrameSize.getWidth() : previewWidth;
                    int callbackFrameHeight = callbackFrameSize != null ? callbackFrameSize.getHeight() : previewHeight;
                    if (callbackFrameSize != null
                            && (callbackFrameWidth != previewWidth || callbackFrameHeight != previewHeight)
                            && !loggedAdjustedPreviewFrameSize) {
                        loggedAdjustedPreviewFrameSize = true;
                        Log.i(TAG, "Preview callback frame size adjusted from negotiated "
                                + previewWidth + "x" + previewHeight + " to "
                                + callbackFrameWidth + "x" + callbackFrameHeight
                                + " based on byteLength=" + data.length);
                    }
                    if (isLikelyDarkNv21Frame(data, callbackFrameWidth, callbackFrameHeight)) {
                        if (!loggedRejectedDarkFrame) {
                            loggedRejectedDarkFrame = true;
                            Log.w(TAG, "Rejecting dark preview frame from preview callback size="
                                    + callbackFrameWidth + "x" + callbackFrameHeight + ", bytes=" + data.length);
                        }
                        return;
                    }
                    synchronized (previewFrameLock) {
                        latestPreviewFrame = data.clone();
                        latestPreviewFrameWidth = callbackFrameWidth;
                        latestPreviewFrameHeight = callbackFrameHeight;
                        latestPreviewFrameFromUnderlying = false;
                        latestPreviewFrameFormat = "nv21";
                        if (!loggedFirstPreviewFrame) {
                            loggedFirstPreviewFrame = true;
                        }
                    }
                }
            });
            currentCamera.setUsbControlBlock(ctrlBlock);
            currentCamera.setCameraStateCallBack(new ICameraStateCallBack() {
                @Override
                public void onCameraState(MultiCameraClient.Camera self, State code, String msg) {
                    Log.i(TAG, "onCameraState code=" + code + ", msg=" + msg);
                    if (code == State.OPENED) {
                        UVCCamera uvcCamera = getUnderlyingUvcCamera();
                        if (uvcCamera != null) {
                            configureUnderlyingPreviewStream(uvcCamera);
                            applyPreviewLayout();
                            applyStoredFocusIfAvailable(uvcCamera);
                            scheduleSmartAutoFocusLock("camera-opened");
                            logBackendApiSnapshotOnce();
                        }
                        currentPreviewSizes = self.getAllPreviewSizes(null);
                        openingCamera = false;
                        if (openCallback != null) {
                            JSONObject result = new JSONObject();
                            try {
                                result.put("backend", "uvc");
                                result.put("deviceName", device.getDeviceName());
                                result.put("productName", device.getProductName());
                                result.put("vendorId", device.getVendorId());
                                result.put("productId", device.getProductId());
                                result.put("previewWidth", previewWidth);
                                result.put("previewHeight", previewHeight);
                            } catch (JSONException ignored) {
                            }
                            openCallback.success(result);
                            openCallback = null;
                        }
                    } else if (code == State.ERROR) {
                        if (shouldRetryOpen(msg)) {
                            Log.w(TAG, "Retryable UVC open error received: " + msg);
                            scheduleOpenRetry();
                            return;
                        }
                        openingCamera = false;
                        openRetryCount = 0;
                        if (openCallback != null) {
                            openCallback.error(msg != null ? msg : "Failed to open UVC camera");
                            openCallback = null;
                        }
                    } else if (code == State.CLOSED) {
                        openingCamera = false;
                        openRetryCount = 0;
                        Log.d(TAG, "UVC camera closed: " + msg);
                    }
                }
            });

            CameraRequest.Builder requestBuilder = new CameraRequest.Builder()
                    .setPreviewWidth(previewWidth)
                    .setPreviewHeight(previewHeight);
            applyPreferredCameraRequestOptions(requestBuilder);
            CameraRequest request = requestBuilder.create();
            Log.i(TAG, "Calling openCamera on MultiCameraClient.Camera");
            currentCamera.openCamera(previewView, request);
            pendingOpenDevice = null;
            pendingCtrlBlock = null;
        } catch (Exception e) {
            if (shouldRetryOpen(e.getMessage())) {
                Log.w(TAG, "Retryable exception while opening connected UVC device", e);
                scheduleOpenRetry();
                return;
            }
            openingCamera = false;
            openRetryCount = 0;
            Log.e(TAG, "Failed to open connected UVC device", e);
            if (openCallback != null) {
                openCallback.error("Failed to open connected UVC device: " + e.getMessage());
                openCallback = null;
            }
            pendingOpenDevice = null;
            pendingCtrlBlock = null;
        }
    }

    private void releaseCamera() {
        clearPhotoTimeout();
        clearReconnect();
        cancelSmartAutoFocusLock();
        closeCurrentCamera(true);
    }

    private void closeCurrentCamera(boolean resetOpeningFlag) {
        if (currentCamera != null) {
            try {
                Log.i(TAG, "Releasing currentCamera");
                try {
                    UVCCamera uvcCamera = getUnderlyingUvcCamera();
                    if (uvcCamera != null) {
                        try {
                            uvcCamera.stopPreview();
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
                clearUnderlyingFrameCallback();
                currentCamera.closeCamera();
            } catch (Exception ignored) {
            }
            currentCamera = null;
        }
        synchronized (previewFrameLock) {
            latestPreviewFrame = null;
            latestPreviewFrameWidth = -1;
            latestPreviewFrameHeight = -1;
            latestPreviewFrameFromUnderlying = false;
            latestPreviewFrameFormat = "unknown";
            loggedFirstPreviewFrame = false;
            loggedRejectedDarkFrame = false;
            loggedAdjustedPreviewFrameSize = false;
            loggedTextureCaptureMetrics = false;
            loggedPhotoSourceMetrics = false;
        }
        currentPreviewSizes = new ArrayList<>();
        if (resetOpeningFlag) {
            openingCamera = false;
        }
    }

    private void scheduleSmartAutoFocusLock(String reason) {
        cancelSmartAutoFocusLock();
        if (!smartFocusEnabled) {
            Log.i(TAG, "Smart focus disabled, skipping autofocus lock scheduling");
            return;
        }
        pendingAutoFocusLock = () -> cordova.getThreadPool().execute(() -> lockCurrentAutoFocus(reason));
        try {
            UVCCamera uvcCamera = getUnderlyingUvcCamera();
            if (uvcCamera != null) {
                uvcCamera.setAutoFocus(true);
                Log.i(TAG, "Smart focus autofocus pulse started, reason=" + reason + ", lockDelayMs=" + smartFocusLockDelayMs);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Unable to start autofocus pulse before lock", exception);
        }
        mainHandler.postDelayed(pendingAutoFocusLock, smartFocusLockDelayMs);
    }

    private void cancelSmartAutoFocusLock() {
        if (pendingAutoFocusLock != null) {
            mainHandler.removeCallbacks(pendingAutoFocusLock);
            pendingAutoFocusLock = null;
        }
    }

    private void lockCurrentAutoFocus(String reason) {
        try {
            UVCCamera uvcCamera = getUnderlyingUvcCamera();
            if (uvcCamera == null || currentCamera == null || !currentCamera.isCameraOpened()) {
                Log.w(TAG, "Skipping smart focus lock because camera is not opened");
                return;
            }
            int lockedFocus = readCurrentFocusPercent(uvcCamera);
            uvcCamera.setAutoFocus(false);
            setPercentControlInternal(uvcCamera, lockedFocus, "Focus", "mFocusMin", "mFocusMax");
            persistLockedFocus(lockedFocus);
            Log.i(TAG, "Smart focus lock applied, reason=" + reason + ", focus=" + lockedFocus);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to lock current autofocus", exception);
        } finally {
            pendingAutoFocusLock = null;
        }
    }

    private int readCurrentFocusPercent(UVCCamera uvcCamera) {
        int focus = getPercentControlValue(uvcCamera, "Focus", "mFocusMin", "mFocusMax");
        if (focus < 0) {
            focus = getLastLockedFocus();
        }
        if (focus < 0) {
            focus = 50;
        }
        return clampPercent(focus);
    }

    private void applyStoredFocusIfAvailable(UVCCamera uvcCamera) {
        int savedFocus = getLastLockedFocus();
        if (savedFocus < 0 || uvcCamera == null) {
            return;
        }
        try {
            uvcCamera.setAutoFocus(false);
            setPercentControlInternal(uvcCamera, savedFocus, "Focus", "mFocusMin", "mFocusMax");
            Log.i(TAG, "Applied stored locked focus on open, focus=" + savedFocus);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to apply stored locked focus on open", exception);
        }
    }

    private void persistLockedFocus(int focusPercent) {
        try {
            SharedPreferences preferences = cordova.getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            preferences.edit().putInt(PREF_LAST_LOCKED_FOCUS, clampPercent(focusPercent)).apply();
        } catch (Exception exception) {
            Log.w(TAG, "Unable to persist locked focus", exception);
        }
    }

    private int getLastLockedFocus() {
        try {
            SharedPreferences preferences = cordova.getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!preferences.contains(PREF_LAST_LOCKED_FOCUS)) {
                return -1;
            }
            return clampPercent(preferences.getInt(PREF_LAST_LOCKED_FOCUS, 50));
        } catch (Exception exception) {
            Log.w(TAG, "Unable to read persisted locked focus", exception);
            return -1;
        }
    }

    private void maybeOpenPendingDevice() {
        if (!previewSurfaceReady || pendingOpenDevice == null || pendingCtrlBlock == null) {
            Log.d(TAG, "maybeOpenPendingDevice skipped: previewReady=" + previewSurfaceReady + ", pendingDevice=" + (pendingOpenDevice != null) + ", pendingCtrlBlock=" + (pendingCtrlBlock != null));
            return;
        }
        UsbDevice device = pendingOpenDevice;
        USBMonitor.UsbControlBlock ctrlBlock = pendingCtrlBlock;
        pendingOpenDevice = null;
        pendingCtrlBlock = null;
        Log.i(TAG, "maybeOpenPendingDevice proceeding with device " + device.getDeviceName());
        openConnectedDevice(device, ctrlBlock);
    }

    private boolean hasVideoInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == 14) {
                return true;
            }
        }
        return false;
    }

    private JSONArray buildInterfaceSummary(UsbDevice device) throws JSONException {
        JSONArray interfaces = new JSONArray();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            JSONObject interfaceJson = new JSONObject();
            interfaceJson.put("id", usbInterface.getId());
            interfaceJson.put("alternateSetting", usbInterface.getAlternateSetting());
            interfaceJson.put("interfaceClass", usbInterface.getInterfaceClass());
            interfaceJson.put("interfaceSubclass", usbInterface.getInterfaceSubclass());
            interfaceJson.put("interfaceProtocol", usbInterface.getInterfaceProtocol());
            interfaceJson.put("endpointCount", usbInterface.getEndpointCount());
            interfaces.put(interfaceJson);
        }
        return interfaces;
    }

    private JSONArray collectMatchingMethodNames(Class<?> type, String... keywords) throws JSONException {
        JSONArray methods = new JSONArray();
        if (type == null) {
            return methods;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            if (matchesKeyword(name, keywords)) {
                names.add(name + buildMethodSignature(method));
            }
        }
        for (java.lang.reflect.Method method : type.getMethods()) {
            String name = method.getName();
            if (matchesKeyword(name, keywords)) {
                names.add(name + buildMethodSignature(method));
            }
        }
        for (String name : names) {
            methods.put(name);
        }
        return methods;
    }

    private JSONArray collectMatchingFieldNames(Class<?> type, String... keywords) throws JSONException {
        JSONArray fields = new JSONArray();
        if (type == null) {
            return fields;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (matchesKeyword(field.getName(), keywords)) {
                names.add(field.getType().getSimpleName() + " " + field.getName());
            }
        }
        for (String name : names) {
            fields.put(name);
        }
        return fields;
    }

    private boolean matchesKeyword(String value, String... keywords) {
        if (value == null || keywords == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String buildMethodSignature(java.lang.reflect.Method method) {
        StringBuilder builder = new StringBuilder("(");
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameterTypes[i].getSimpleName());
        }
        builder.append("): ").append(method.getReturnType().getSimpleName());
        return builder.toString();
    }

    private ParsedUvcDescriptors parseUvcDescriptors(byte[] rawDescriptors) {
        ParsedUvcDescriptors parsed = new ParsedUvcDescriptors();
        int currentInterfaceClass = -1;
        int currentInterfaceSubclass = -1;
        int currentInterfaceNumber = -1;
        int currentAlternateSetting = -1;

        for (int index = 0; index + 1 < rawDescriptors.length; ) {
            int length = rawDescriptors[index] & 0xff;
            if (length <= 0 || index + length > rawDescriptors.length) {
                break;
            }

            int descriptorType = rawDescriptors[index + 1] & 0xff;
            if (descriptorType == 0x04 && length >= 9) {
                currentInterfaceNumber = rawDescriptors[index + 2] & 0xff;
                currentAlternateSetting = rawDescriptors[index + 3] & 0xff;
                currentInterfaceClass = rawDescriptors[index + 5] & 0xff;
                currentInterfaceSubclass = rawDescriptors[index + 6] & 0xff;
                if (currentInterfaceClass == 14) {
                    if (currentInterfaceSubclass == 1) {
                        parsed.videoControlInterfaceCount++;
                    } else if (currentInterfaceSubclass == 2) {
                        parsed.videoStreamingInterfaceCount++;
                    }
                }
            } else if (descriptorType == 0x24 && length >= 3 && currentInterfaceClass == 14) {
                int descriptorSubtype = rawDescriptors[index + 2] & 0xff;
                parsed.descriptorSubtypes.add(descriptorSubtypeName(currentInterfaceSubclass, descriptorSubtype));
                if (currentInterfaceSubclass == 2) {
                    if (descriptorSubtype == 0x03) {
                        parsed.stillImageDescriptorCount++;
                    } else if (descriptorSubtype == 0x04 || descriptorSubtype == 0x06 || descriptorSubtype == 0x10) {
                        parsed.formatDescriptorCount++;
                        parsed.frameFormats.add(descriptorSubtypeName(currentInterfaceSubclass, descriptorSubtype));
                    } else if (descriptorSubtype == 0x05 || descriptorSubtype == 0x07 || descriptorSubtype == 0x11) {
                        parsed.frameDescriptorCount++;
                        if (length >= 9) {
                            int width = ((rawDescriptors[index + 6] & 0xff) << 8) | (rawDescriptors[index + 5] & 0xff);
                            int height = ((rawDescriptors[index + 8] & 0xff) << 8) | (rawDescriptors[index + 7] & 0xff);
                            parsed.frameSizes.add(width + "x" + height + " [" + descriptorSubtypeName(currentInterfaceSubclass, descriptorSubtype)
                                    + ", if=" + currentInterfaceNumber + ", alt=" + currentAlternateSetting + "]");
                        }
                    }
                }
            }

            index += length;
        }

        return parsed;
    }

    private JSONArray buildDescriptorNotes(ParsedUvcDescriptors parsed) {
        JSONArray notes = new JSONArray();
        if (parsed.stillImageDescriptorCount == 0) {
            notes.put("No UVC still-image descriptors were found in the raw USB descriptors.");
        } else {
            notes.put("UVC still-image descriptors were found. A true still-image backend may be possible.");
        }
        if (parsed.frameSizes.isEmpty()) {
            notes.put("No class-specific frame descriptors were parsed from the video streaming interfaces.");
        } else {
            notes.put("Parsed frame descriptors from USB video streaming interfaces: " + Arrays.toString(parsed.frameSizes.toArray()));
        }
        return notes;
    }

    private String descriptorSubtypeName(int interfaceSubclass, int descriptorSubtype) {
        if (interfaceSubclass == 1) {
            switch (descriptorSubtype) {
                case 0x01: return "VC_HEADER";
                case 0x02: return "VC_INPUT_TERMINAL";
                case 0x03: return "VC_OUTPUT_TERMINAL";
                case 0x04: return "VC_SELECTOR_UNIT";
                case 0x05: return "VC_PROCESSING_UNIT";
                case 0x06: return "VC_EXTENSION_UNIT";
                default: return "VC_UNKNOWN_" + descriptorSubtype;
            }
        }

        if (interfaceSubclass == 2) {
            switch (descriptorSubtype) {
                case 0x01: return "VS_INPUT_HEADER";
                case 0x02: return "VS_OUTPUT_HEADER";
                case 0x03: return "VS_STILL_IMAGE_FRAME";
                case 0x04: return "VS_FORMAT_UNCOMPRESSED";
                case 0x05: return "VS_FRAME_UNCOMPRESSED";
                case 0x06: return "VS_FORMAT_MJPEG";
                case 0x07: return "VS_FRAME_MJPEG";
                case 0x0d: return "VS_COLORFORMAT";
                case 0x10: return "VS_FORMAT_FRAME_BASED";
                case 0x11: return "VS_FRAME_FRAME_BASED";
                default: return "VS_UNKNOWN_" + descriptorSubtype;
            }
        }

        return "UNKNOWN_" + descriptorSubtype;
    }

    private static final class ParsedUvcDescriptors {
        int videoControlInterfaceCount;
        int videoStreamingInterfaceCount;
        int stillImageDescriptorCount;
        int formatDescriptorCount;
        int frameDescriptorCount;
        final LinkedHashSet<String> frameFormats = new LinkedHashSet<>();
        final LinkedHashSet<String> frameSizes = new LinkedHashSet<>();
        final LinkedHashSet<String> descriptorSubtypes = new LinkedHashSet<>();
    }

    private PreviewSize resolvePreviewSizeForFrame(int frameLength) {
        int expectedPixels = (frameLength * 2) / 3;
        List<PreviewSize> sizes = currentPreviewSizes != null ? currentPreviewSizes : new ArrayList<>();

        PreviewSize requestedSize = findMatchingPreviewSize(sizes, previewWidth, previewHeight);
        if (requestedSize != null && requestedSize.getWidth() * requestedSize.getHeight() == expectedPixels) {
            return requestedSize;
        }

        for (PreviewSize size : sizes) {
            if (size.getWidth() * size.getHeight() == expectedPixels) {
                return size;
            }
        }

        int[][] commonSizes = new int[][] {
                {640, 480},
                {1280, 720},
                {800, 600},
                {320, 240},
                {1920, 1080}
        };
        for (int[] candidate : commonSizes) {
            if (candidate[0] * candidate[1] == expectedPixels) {
                return new PreviewSize(candidate[0], candidate[1]);
            }
        }

        Log.w(TAG, "resolvePreviewSizeForFrame could not match frameLength=" + frameLength + " (expectedPixels=" + expectedPixels + ")");
        return null;
    }

    private PreviewSize findMatchingPreviewSize(List<PreviewSize> sizes, int width, int height) {
        if (sizes == null) {
            return null;
        }
        for (PreviewSize size : sizes) {
            if (size.getWidth() == width && size.getHeight() == height) {
                return size;
            }
        }
        return null;
    }

    private int[] resolvePreferredStillCaptureSize(int[] negotiatedPreviewSize) {
        int bestWidth = Math.max(requestedPreviewWidth, previewWidth);
        int bestHeight = Math.max(requestedPreviewHeight, previewHeight);
        int bestPixels = bestWidth * bestHeight;

        if (negotiatedPreviewSize != null && negotiatedPreviewSize.length >= 2
                && negotiatedPreviewSize[0] > 0 && negotiatedPreviewSize[1] > 0) {
            int negotiatedPixels = negotiatedPreviewSize[0] * negotiatedPreviewSize[1];
            if (negotiatedPixels > bestPixels) {
                bestWidth = negotiatedPreviewSize[0];
                bestHeight = negotiatedPreviewSize[1];
                bestPixels = negotiatedPixels;
            }
        }

        List<PreviewSize> sizes = currentPreviewSizes != null ? currentPreviewSizes : new ArrayList<>();
        for (PreviewSize size : sizes) {
            if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
                continue;
            }
            int pixels = size.getWidth() * size.getHeight();
            if (pixels > bestPixels) {
                bestWidth = size.getWidth();
                bestHeight = size.getHeight();
                bestPixels = pixels;
            }
        }

        return new int[] { bestWidth, bestHeight };
    }

    private PreviewSize resolveTargetPreviewSize(List<PreviewSize> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        PreviewSize requestedSize = findMatchingPreviewSize(sizes, requestedPreviewWidth, requestedPreviewHeight);
        if (!preferHighestResolution) {
            return requestedSize;
        }

        int[][] preferredCandidates = new int[][] {
                {1920, 1080},
                {1600, 896},
                {1280, 720},
                {1024, 576},
                {960, 720},
                {864, 480},
                {800, 600},
                {640, 480}
        };
        for (int[] candidate : preferredCandidates) {
            PreviewSize candidateSize = findMatchingPreviewSize(sizes, candidate[0], candidate[1]);
            if (candidateSize != null) {
                return candidateSize;
            }
        }

        if (requestedSize != null) {
            return requestedSize;
        }

        PreviewSize highestSize = null;
        int highestPixels = -1;
        for (PreviewSize size : sizes) {
            int pixels = size.getWidth() * size.getHeight();
            if (pixels > highestPixels) {
                highestPixels = pixels;
                highestSize = size;
            }
        }

        if (highestSize == null) {
            return requestedSize;
        }

        return highestSize;
    }

    private void logPreviewSizes(String label, List<PreviewSize> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            Log.w(TAG, label + ": none");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (PreviewSize size : sizes) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(size.getWidth()).append("x").append(size.getHeight());
        }
        Log.i(TAG, label + ": " + builder);
    }

    private void configureUnderlyingPreviewStream(UVCCamera uvcCamera) {
        if (uvcCamera == null) {
            return;
        }
        try {
            List<Integer> frameFormats = buildPreferredFrameFormats(uvcCamera);
            List<int[]> candidates = buildPreviewNegotiationCandidates();
            Log.i(TAG, "Configuring underlying UVCCamera preview stream frameFormats=" + frameFormats
                    + ", preferMjpeg=" + preferMjpeg + ", candidates=" + describeCandidates(candidates));

            int bestWidth = -1;
            int bestHeight = -1;
            int bestPixels = -1;
            int bestFrameFormat = -1;

            for (Integer frameFormat : frameFormats) {
                if (frameFormat == null) {
                    continue;
                }
                for (int[] candidate : candidates) {
                    int candidateWidth = candidate[0];
                    int candidateHeight = candidate[1];
                    invokeSetPreviewSizePreferSpecificOverload(uvcCamera, candidateWidth, candidateHeight, frameFormat);
                    if (previewView != null && previewView.isAvailable()) {
                        SurfaceTexture surfaceTexture = previewView.getSurfaceTexture();
                        if (surfaceTexture != null) {
                            try {
                                surfaceTexture.setDefaultBufferSize(candidateWidth, candidateHeight);
                                Log.i(TAG, "Set preview SurfaceTexture default buffer size for negotiation to "
                                        + candidateWidth + "x" + candidateHeight);
                            } catch (Exception exception) {
                                Log.w(TAG, "Unable to set SurfaceTexture default buffer size for negotiation", exception);
                            }
                        }
                    }
                    int[] negotiated = getNegotiatedPreviewSize();
                    if (negotiated[0] > 0 && negotiated[1] > 0) {
                        int negotiatedPixels = negotiated[0] * negotiated[1];
                        if (negotiatedPixels > bestPixels) {
                            bestWidth = negotiated[0];
                            bestHeight = negotiated[1];
                            bestPixels = negotiatedPixels;
                            bestFrameFormat = frameFormat;
                        }
                        if (negotiated[0] >= requestedPreviewWidth && negotiated[1] >= requestedPreviewHeight) {
                            previewWidth = negotiated[0];
                            previewHeight = negotiated[1];
                            return;
                        }
                    }
                }
            }

            if (bestWidth > 0 && bestHeight > 0) {
                previewWidth = bestWidth;
                previewHeight = bestHeight;
                Log.i(TAG, "Underlying UVCCamera preview stream configured using best negotiated size="
                        + bestWidth + "x" + bestHeight + ", frameFormat=" + bestFrameFormat);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Unable to reconfigure underlying UVCCamera preview stream", exception);
        }
    }

    private void clearUnderlyingFrameCallback() {
        synchronized (previewFrameLock) {
            latestPreviewFrame = null;
            latestPreviewFrameWidth = -1;
            latestPreviewFrameHeight = -1;
            latestPreviewFrameFromUnderlying = false;
            latestPreviewFrameFormat = "unknown";
            loggedFirstPreviewFrame = false;
            loggedRejectedDarkFrame = false;
            loggedAdjustedPreviewFrameSize = false;
            loggedTextureCaptureMetrics = false;
            loggedPhotoSourceMetrics = false;
        }
    }

    private PreviewSize resolvePreviewCallbackFrameSize(int frameLength) {
        if (matchesNv21Length(previewWidth, previewHeight, frameLength)) {
            return new PreviewSize(previewWidth, previewHeight);
        }
        return resolvePreviewSizeForFrame(frameLength);
    }

    private boolean matchesNv21Length(int width, int height, int frameLength) {
        if (width <= 0 || height <= 0 || frameLength <= 0) {
            return false;
        }
        return width * height * 3 / 2 == frameLength;
    }

    private List<Integer> buildUnderlyingFrameCallbackPixelFormats() {
        LinkedHashSet<Integer> formats = new LinkedHashSet<>();
        addUnderlyingFrameCallbackPixelFormat(formats, "PIXEL_FORMAT_NV21", Integer.MIN_VALUE);
        addUnderlyingFrameCallbackPixelFormat(formats, "PIXEL_FORMAT_YUV420SP", Integer.MIN_VALUE);
        addUnderlyingFrameCallbackPixelFormat(formats, "PIXEL_FORMAT_YUV420P", Integer.MIN_VALUE);
        addUnderlyingFrameCallbackPixelFormat(formats, "PIXEL_FORMAT_NV12", Integer.MIN_VALUE);
        addUnderlyingFrameCallbackPixelFormat(formats, "PIXEL_FORMAT_YUYV", Integer.MIN_VALUE);
        if (formats.isEmpty()) {
            formats.add(4);
        }
        return new ArrayList<>(formats);
    }

    private void addUnderlyingFrameCallbackPixelFormat(LinkedHashSet<Integer> formats, String fieldName, int fallback) {
        int value = getUvcStaticInt(UVCCamera.class, fieldName, fallback);
        if (value != fallback) {
            formats.add(value);
        }
    }

    private String detectUnderlyingFrameFormat(int frameLength, int width, int height) {
        if (width <= 0 || height <= 0) {
            return "unknown";
        }
        int nv21Length = width * height * 3 / 2;
        int yuyvLength = width * height * 2;
        if (frameLength == yuyvLength) {
            return "yuyv";
        }
        if (frameLength == nv21Length) {
            return "yuv420sp";
        }
        return "unknown";
    }

    private byte[] convertNv12ToNv21(byte[] data, int width, int height) {
        if (data == null || data.length < 4) {
            return data;
        }
        byte[] converted = data.clone();
        int uvStart = width * height;
        if (uvStart < 0 || uvStart >= data.length - 1) {
            return converted;
        }
        for (int index = uvStart; index + 1 < converted.length; index += 2) {
            byte u = converted[index];
            converted[index] = converted[index + 1];
            converted[index + 1] = u;
        }
        return converted;
    }

    private byte[] convertYuyvToNv21(byte[] data, int width, int height) {
        int frameSize = width * height;
        int expectedLength = frameSize * 2;
        if (data == null || width <= 0 || height <= 0 || data.length < expectedLength) {
            return data;
        }

        byte[] output = new byte[frameSize + (frameSize / 2)];
        int yIndex = 0;
        int uvIndex = frameSize;

        for (int row = 0; row < height; row++) {
            int rowStart = row * width * 2;
            for (int col = 0; col < width; col += 2) {
                int offset = rowStart + (col * 2);
                byte y0 = data[offset];
                byte u = data[offset + 1];
                byte y1 = data[offset + 2];
                byte v = data[offset + 3];

                output[yIndex++] = y0;
                output[yIndex++] = y1;

                if ((row & 1) == 0) {
                    output[uvIndex++] = v;
                    output[uvIndex++] = u;
                }
            }
        }

        return output;
    }

    private List<int[]> buildPreviewNegotiationCandidates() {
        List<int[]> candidates = new ArrayList<>();
        addCandidate(candidates, requestedPreviewWidth, requestedPreviewHeight);
        if (preferHighestResolution) {
            addCandidate(candidates, 1920, 1080);
            addCandidate(candidates, 1600, 896);
            addCandidate(candidates, 1280, 720);
            addCandidate(candidates, 1024, 576);
            addCandidate(candidates, 960, 720);
            addCandidate(candidates, 864, 480);
            addCandidate(candidates, 800, 600);
        } else {
            addCandidate(candidates, 1280, 720);
            addCandidate(candidates, 960, 720);
        }
        addCandidate(candidates, 640, 480);
        return candidates;
    }

    private void addCandidate(List<int[]> candidates, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        for (int[] candidate : candidates) {
            if (candidate[0] == width && candidate[1] == height) {
                return;
            }
        }
        candidates.add(new int[] { width, height });
    }

    private String describeCandidates(List<int[]> candidates) {
        StringBuilder builder = new StringBuilder();
        for (int[] candidate : candidates) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(candidate[0]).append("x").append(candidate[1]);
        }
        return builder.toString();
    }

    private int resolvePreferredFrameFormat(UVCCamera uvcCamera) {
        if (!preferMjpeg) {
            return getUvcStaticInt(UVCCamera.class, "FRAME_FORMAT_YUYV", 0);
        }
        int mjpegFormat = getUvcStaticInt(UVCCamera.class, "FRAME_FORMAT_MJPEG", -1);
        if (mjpegFormat != -1) {
            return mjpegFormat;
        }
        return getUvcStaticInt(UVCCamera.class, "FRAME_FORMAT_YUYV", 0);
    }

    private List<Integer> buildPreferredFrameFormats(UVCCamera uvcCamera) {
        List<Integer> formats = new ArrayList<>();
        int mjpegFormat = getUvcStaticInt(UVCCamera.class, "FRAME_FORMAT_MJPEG", -1);
        int yuyvFormat = getUvcStaticInt(UVCCamera.class, "FRAME_FORMAT_YUYV", 0);

        if (preferMjpeg) {
            addFrameFormat(formats, mjpegFormat);
            addFrameFormat(formats, yuyvFormat);
        } else {
            addFrameFormat(formats, yuyvFormat);
            addFrameFormat(formats, mjpegFormat);
        }

        addFrameFormat(formats, resolvePreferredFrameFormat(uvcCamera));
        return formats;
    }

    private void addFrameFormat(List<Integer> formats, int frameFormat) {
        if (formats == null || frameFormat < 0) {
            return;
        }
        if (!formats.contains(frameFormat)) {
            formats.add(frameFormat);
        }
    }

    private int getUvcStaticInt(Class<?> type, String fieldName, int fallback) {
        try {
            Field field = type.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to read UVCCamera static field " + fieldName, exception);
            return fallback;
        }
    }

    private void invokeSetPreviewSizePreferSpecificOverload(UVCCamera uvcCamera, int width, int height, int frameFormat) throws Exception {
        List<java.lang.reflect.Method> candidates = new ArrayList<>();
        for (java.lang.reflect.Method method : UVCCamera.class.getMethods()) {
            if ("setPreviewSize".equals(method.getName())) {
                candidates.add(method);
            }
        }

        java.lang.reflect.Method selected = null;
        for (java.lang.reflect.Method candidate : candidates) {
            Class<?>[] parameterTypes = candidate.getParameterTypes();
            if (parameterTypes.length >= 3
                    && parameterTypes[0] == int.class
                    && parameterTypes[1] == int.class
                    && parameterTypes[2] == int.class) {
                selected = candidate;
                break;
            }
        }

        if (selected == null) {
            Log.w(TAG, "No UVCCamera.setPreviewSize overload with frameFormat found, falling back to setPreviewSize(width,height)");
            uvcCamera.setPreviewSize(width, height);
            return;
        }

        Class<?>[] parameterTypes = selected.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        args[0] = width;
        args[1] = height;
        args[2] = frameFormat;
        for (int i = 3; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == int.class) {
                if (i == 3) {
                    args[i] = getUvcStaticInt(UVCCamera.class, "DEFAULT_PREVIEW_FRAME_INTERVAL", 1);
                } else if (i == 4) {
                    args[i] = getUvcStaticInt(UVCCamera.class, "DEFAULT_PREVIEW_BANDWIDTH", 1);
                } else {
                    args[i] = 1;
                }
            } else if (parameterTypes[i] == float.class) {
                args[i] = 1.0f;
            } else {
                throw new IllegalStateException("Unsupported UVCCamera.setPreviewSize parameter type: " + parameterTypes[i]);
            }
        }
        Log.i(TAG, "Invoking UVCCamera.setPreviewSize overload=" + buildMethodSignature(selected));
        selected.invoke(uvcCamera, args);
    }

    private void applyPreferredCameraRequestOptions(CameraRequest.Builder requestBuilder) {
        if (requestBuilder == null) {
            return;
        }

        if (preferMjpeg) {
            boolean previewFormatApplied = tryApplyPreviewFormat(requestBuilder, "MJPEG");
            Log.i(TAG, "preferred preview format MJPEG applied=" + previewFormatApplied);
        }

        boolean rawPreviewApplied = tryInvokeBuilderBoolean(requestBuilder, "setRawPreviewData", true);
        boolean rawCaptureApplied = tryInvokeBuilderBoolean(requestBuilder, "setCaptureRawImage", true);
        Log.i(TAG, "camera request tuning rawPreviewApplied=" + rawPreviewApplied + ", rawCaptureApplied=" + rawCaptureApplied);
    }

    private boolean tryApplyPreviewFormat(CameraRequest.Builder requestBuilder, String preferredFormatName) {
        try {
            Class<?> previewFormatClass = Class.forName("com.jiangdg.ausbc.camera.bean.CameraRequest$PreviewFormat");
            Object selectedValue = null;
            Object[] constants = previewFormatClass.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (!(constant instanceof Enum)) {
                        continue;
                    }
                    String name = ((Enum<?>) constant).name();
                    if (preferredFormatName.equalsIgnoreCase("MJPEG") && name.toUpperCase(Locale.ROOT).contains("MJPEG")) {
                        selectedValue = constant;
                        break;
                    }
                }
                if (selectedValue == null) {
                    for (Object constant : constants) {
                        if (!(constant instanceof Enum)) {
                            continue;
                        }
                        String name = ((Enum<?>) constant).name();
                        if (name.toUpperCase(Locale.ROOT).contains("YUYV")) {
                            selectedValue = constant;
                            break;
                        }
                    }
                }
            }
            if (selectedValue == null) {
                Log.w(TAG, "No matching CameraRequest.PreviewFormat constant found for " + preferredFormatName);
                return false;
            }
            java.lang.reflect.Method method = requestBuilder.getClass().getMethod("setPreviewFormat", previewFormatClass);
            method.invoke(requestBuilder, selectedValue);
            Log.i(TAG, "Applied CameraRequest preview format constant=" + ((Enum<?>) selectedValue).name());
            return true;
        } catch (ClassNotFoundException exception) {
            Log.w(TAG, "CameraRequest.PreviewFormat class not available", exception);
            return false;
        } catch (NoSuchMethodException exception) {
            Log.w(TAG, "CameraRequest.Builder.setPreviewFormat not available", exception);
            return false;
        } catch (Exception exception) {
            Log.w(TAG, "Failed applying CameraRequest preview format", exception);
            return false;
        }
    }

    private boolean tryInvokeBuilderBoolean(CameraRequest.Builder requestBuilder, String methodName, boolean value) {
        try {
            java.lang.reflect.Method method = requestBuilder.getClass().getMethod(methodName, boolean.class);
            method.invoke(requestBuilder, value);
            return true;
        } catch (NoSuchMethodException exception) {
            Log.w(TAG, "CameraRequest.Builder." + methodName + " not available", exception);
            return false;
        } catch (Exception exception) {
            Log.w(TAG, "Failed invoking CameraRequest.Builder." + methodName, exception);
            return false;
        }
    }

    private boolean setAutoFocus(JSONArray args, CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
                if (uvcCamera == null) {
                    return;
                }
                boolean enabled = args.optBoolean(0, true);
                uvcCamera.setAutoFocus(enabled);
                callbackContext.success("ok");
            } catch (Exception exception) {
                callbackContext.error("setAutoFocus failed: " + exception.getMessage());
            }
        });
        return true;
    }

    private boolean refocus(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options != null) {
            smartFocusLockDelayMs = Math.max(300, options.optInt("focusLockDelayMs", smartFocusLockDelayMs));
        }
        smartFocusEnabled = true;
        scheduleSmartAutoFocusLock("manual-refocus");
        JSONObject result = new JSONObject();
        try {
            result.put("scheduled", true);
            result.put("focusLockDelayMs", smartFocusLockDelayMs);
        } catch (JSONException ignored) {
        }
        callbackContext.success(result);
        return true;
    }

    private boolean setFocus(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }
            int value = clampPercent(args.optInt(0, 0));
            Log.i(TAG, "setFocus requestedPercent=" + value);
            uvcCamera.setAutoFocus(false);
            setPercentControlInternal(uvcCamera, value, "Focus", "mFocusMin", "mFocusMax");
            JSONObject result = new JSONObject();
            result.put("requested", value);
            result.put("applied", getPercentControlValue(uvcCamera, "Focus", "mFocusMin", "mFocusMax"));
            result.put("autoFocus", uvcCamera.getAutoFocus());
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "setFocus failed", exception);
            callbackContext.error("setFocus failed: " + exception.getMessage());
        }
        return true;
    }

    private boolean setZoom(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }
            int value = clampPercent(args.optInt(0, 0));
            invokeDeclaredVoidMethod(uvcCamera, "nativeUpdateZoomLimit");
            int min = getIntField(uvcCamera, "mZoomMin");
            int max = getIntField(uvcCamera, "mZoomMax");
            int absoluteValue = scalePercentToAbsolute(value, min, max);
            Log.i(TAG, "setZoom requestedPercent=" + value + ", min=" + min + ", max=" + max + ", absoluteValue=" + absoluteValue);
            invokeDeclaredVoidMethod(uvcCamera, "nativeSetZoom", absoluteValue);
            int appliedAbsolute = invokeDeclaredIntMethod(uvcCamera, "nativeGetZoom");
            int appliedPercent = scaleAbsoluteToPercent(appliedAbsolute, min, max);
            JSONObject result = new JSONObject();
            result.put("requested", value);
            result.put("min", min);
            result.put("max", max);
            result.put("absoluteValue", absoluteValue);
            result.put("appliedAbsolute", appliedAbsolute);
            result.put("applied", appliedPercent);
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "setZoom failed", exception);
            callbackContext.error("setZoom failed: " + exception.getMessage());
        }
        return true;
    }

    private boolean setBrightness(JSONArray args, CallbackContext callbackContext) {
        return setPercentControl(args, callbackContext, "Brightness", "mBrightnessMin", "mBrightnessMax");
    }

    private boolean setContrast(JSONArray args, CallbackContext callbackContext) {
        return setPercentControl(args, callbackContext, "Contrast", "mContrastMin", "mContrastMax");
    }

    private boolean setSharpness(JSONArray args, CallbackContext callbackContext) {
        return setPercentControl(args, callbackContext, "Sharpness", "mSharpnessMin", "mSharpnessMax");
    }

    private boolean setGain(JSONArray args, CallbackContext callbackContext) {
        return setPercentControl(args, callbackContext, "Gain", "mGainMin", "mGainMax");
    }

    private boolean setAutoExposure(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }
            boolean enabled = args.optBoolean(0, true);
            Log.i(TAG, "setAutoExposure requestedEnabled=" + enabled);
            int modeApplied = setAutoExposureInternal(uvcCamera, enabled);
            JSONObject result = new JSONObject();
            result.put("requested", enabled);
            result.put("applied", getAutoExposure(uvcCamera));
            result.put("mode", getExposureModeRaw(uvcCamera));
            result.put("modeApplied", modeApplied);
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "setAutoExposure failed", exception);
            callbackContext.error("setAutoExposure failed: " + exception.getMessage());
        }
        return true;
    }

    private boolean setExposure(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }
            int value = clampPercent(args.optInt(0, 0));
            Log.i(TAG, "setExposure requestedPercent=" + value);
            setExposureInternal(uvcCamera, value);
            JSONObject result = new JSONObject();
            result.put("requested", value);
            result.put("applied", getExposurePercent(uvcCamera));
            result.put("autoExposure", getAutoExposure(uvcCamera));
            result.put("mode", getExposureModeRaw(uvcCamera));
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "setExposure failed", exception);
            callbackContext.error("setExposure failed: " + exception.getMessage());
        }
        return true;
    }

    private boolean setAutoWhiteBalance(JSONArray args, CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
                if (uvcCamera == null) {
                    return;
                }
                boolean enabled = args.optBoolean(0, true);
                Log.i(TAG, "setAutoWhiteBalance requestedEnabled=" + enabled);
                uvcCamera.setAutoWhiteBlance(enabled);
                JSONObject result = new JSONObject();
                result.put("requested", enabled);
                result.put("applied", uvcCamera.getAutoWhiteBlance());
                callbackContext.success(result);
            } catch (Exception exception) {
                callbackContext.error("setAutoWhiteBalance failed: " + exception.getMessage());
            }
        });
        return true;
    }

    private boolean setWhiteBalance(JSONArray args, CallbackContext callbackContext) {
        return setPercentControl(args, callbackContext, "WhiteBlance", "mWhiteBlanceMin", "mWhiteBlanceMax");
    }

    private boolean setPercentControl(JSONArray args, CallbackContext callbackContext, String controlName, String minField, String maxField) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }
            int value = clampPercent(args.optInt(0, 0));
            Log.i(TAG, "set" + controlName + " requestedPercent=" + value);
            setPercentControlInternal(uvcCamera, value, controlName, minField, maxField);
            JSONObject result = new JSONObject();
            result.put("requested", value);
            result.put("applied", getPercentControlValue(uvcCamera, controlName, minField, maxField));
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "set" + controlName + " failed", exception);
            callbackContext.error("set" + controlName + " failed: " + exception.getMessage());
        }
        return true;
    }

    private UVCCamera getUnderlyingUvcCamera() {
        if (currentCamera == null) {
            return null;
        }
        try {
            Field uvcCameraField = MultiCameraClient.Camera.class.getDeclaredField("mUvcCamera");
            uvcCameraField.setAccessible(true);
            Object underlying = uvcCameraField.get(currentCamera);
            if (underlying instanceof UVCCamera) {
                return (UVCCamera) underlying;
            }
        } catch (Exception exception) {
            Log.e(TAG, "getUnderlyingUvcCamera failed", exception);
        }
        return null;
    }

    private UVCCamera requireOpenedUvcCamera(CallbackContext callbackContext) {
        if (currentCamera == null || !currentCamera.isCameraOpened()) {
            callbackContext.error("USB UVC camera not opened");
            return null;
        }
        UVCCamera uvcCamera = getUnderlyingUvcCamera();
        if (uvcCamera == null) {
            callbackContext.error("Underlying UVCCamera not available");
            return null;
        }
        return uvcCamera;
    }

    private int setAutoExposureInternal(UVCCamera uvcCamera, boolean enabled) throws Exception {
        invokeDeclaredVoidMethod(uvcCamera, "nativeUpdateExposureModeLimit");
        int[] candidates = enabled
                ? new int[] { UVC_EXPOSURE_MODE_AUTO, 8, 4 }
                : new int[] { UVC_EXPOSURE_MODE_MANUAL, 1 };
        Exception lastException = null;
        for (int candidate : candidates) {
            try {
                Log.i(TAG, "Trying exposure mode candidate=" + candidate + " for enabled=" + enabled);
                invokeDeclaredVoidMethod(uvcCamera, "nativeSetExposureMode", candidate);
                return candidate;
            } catch (Exception exception) {
                lastException = exception;
                Log.w(TAG, "nativeSetExposureMode candidate failed: " + candidate, exception);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IllegalStateException("No exposure mode candidate available");
    }

    private void setExposureInternal(UVCCamera uvcCamera, int percent) throws Exception {
        invokeDeclaredVoidMethod(uvcCamera, "nativeUpdateExposureLimit");
        int min = getIntField(uvcCamera, "mExposureMin");
        int max = getIntField(uvcCamera, "mExposureMax");
        int absoluteValue = scalePercentToAbsolute(percent, min, max);
        invokeDeclaredVoidMethod(uvcCamera, "nativeSetExposure", absoluteValue);
    }

    private boolean getAutoExposure(UVCCamera uvcCamera) {
        try {
            int mode = getExposureModeRaw(uvcCamera);
            return mode != UVC_EXPOSURE_MODE_MANUAL && mode != 1;
        } catch (Exception exception) {
            Log.w(TAG, "getAutoExposure failed", exception);
            return true;
        }
    }

    private int getExposureModeRaw(UVCCamera uvcCamera) {
        try {
            return invokeDeclaredIntMethod(uvcCamera, "nativeGetExposureMode");
        } catch (Exception exception) {
            Log.w(TAG, "getExposureModeRaw failed", exception);
            return -1;
        }
    }

    private int getExposurePercent(UVCCamera uvcCamera) {
        try {
            invokeDeclaredVoidMethod(uvcCamera, "nativeUpdateExposureLimit");
            int exposureAbsolute = invokeDeclaredIntMethod(uvcCamera, "nativeGetExposure");
            int min = getIntField(uvcCamera, "mExposureMin");
            int max = getIntField(uvcCamera, "mExposureMax");
            return scaleAbsoluteToPercent(exposureAbsolute, min, max);
        } catch (Exception exception) {
            Log.w(TAG, "getExposurePercent failed", exception);
            return 0;
        }
    }

    private void setPercentControlInternal(UVCCamera camera, int percent, String controlName, String minField, String maxField) throws Exception {
        invokeDeclaredVoidMethod(camera, "nativeUpdate" + controlName + "Limit");
        int min = getIntField(camera, minField);
        int max = getIntField(camera, maxField);
        int absoluteValue = scalePercentToAbsolute(percent, min, max);
        invokeDeclaredVoidMethod(camera, "nativeSet" + controlName, absoluteValue);
    }

    private int getPercentControlValue(UVCCamera camera, String controlName, String minField, String maxField) {
        try {
            invokeDeclaredVoidMethod(camera, "nativeUpdate" + controlName + "Limit");
            int absoluteValue = invokeDeclaredIntMethod(camera, "nativeGet" + controlName);
            int min = getIntField(camera, minField);
            int max = getIntField(camera, maxField);
            return scaleAbsoluteToPercent(absoluteValue, min, max);
        } catch (Exception exception) {
            Log.w(TAG, "getPercentControlValue failed for " + controlName, exception);
            return -1;
        }
    }

    private int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 100) {
            return 100;
        }
        return value;
    }

    private int scalePercentToAbsolute(int percent, int min, int max) {
        int clampedPercent = clampPercent(percent);
        float range = Math.abs(max - min);
        if (range <= 0) {
            return min;
        }
        return (int) ((clampedPercent / 100.0f) * range) + min;
    }

    private int scaleAbsoluteToPercent(int absoluteValue, int min, int max) {
        float range = Math.abs(max - min);
        if (range <= 0) {
            return 0;
        }
        return clampPercent((int) ((absoluteValue - min) * 100.0f / range));
    }

    private JSONObject buildRangeJson(UVCCamera camera, String minField, String maxField, String defField) throws Exception {
        JSONObject range = new JSONObject();
        range.put("min", getIntField(camera, minField));
        range.put("max", getIntField(camera, maxField));
        range.put("default", getIntField(camera, defField));
        return range;
    }

    private int getIntField(UVCCamera camera, String fieldName) throws Exception {
        Field field = UVCCamera.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(camera);
    }

    private void invokeDeclaredVoidMethod(UVCCamera camera, String methodName) throws Exception {
        java.lang.reflect.Method method = UVCCamera.class.getDeclaredMethod(methodName, long.class);
        method.setAccessible(true);
        Field nativePtrField = UVCCamera.class.getDeclaredField("mNativePtr");
        nativePtrField.setAccessible(true);
        long nativePtr = nativePtrField.getLong(camera);
        method.invoke(camera, nativePtr);
    }

    private void invokeDeclaredVoidMethod(UVCCamera camera, String methodName, int value) throws Exception {
        java.lang.reflect.Method method = UVCCamera.class.getDeclaredMethod(methodName, long.class, int.class);
        method.setAccessible(true);
        Field nativePtrField = UVCCamera.class.getDeclaredField("mNativePtr");
        nativePtrField.setAccessible(true);
        long nativePtr = nativePtrField.getLong(camera);
        method.invoke(camera, nativePtr, value);
    }

    private int invokeDeclaredIntMethod(UVCCamera camera, String methodName) throws Exception {
        java.lang.reflect.Method method = UVCCamera.class.getDeclaredMethod(methodName, long.class);
        method.setAccessible(true);
        Field nativePtrField = UVCCamera.class.getDeclaredField("mNativePtr");
        nativePtrField.setAccessible(true);
        long nativePtr = nativePtrField.getLong(camera);
        Object result = method.invoke(camera, nativePtr);
        return result instanceof Integer ? (Integer) result : 0;
    }

    private String encodePreviewFrameAsBase64(byte[] data, int width, int height) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
        try {
            YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, width, height, null);
            boolean compressed = yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, outputStream);
            if (!compressed) {
                return null;
            }
            byte[] jpegBytes = outputStream.toByteArray();
            return Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
        } catch (Exception exception) {
            Log.e(TAG, "encodePreviewFrameAsBase64 failed", exception);
            return null;
        } finally {
            try {
                outputStream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isLikelyDarkNv21Frame(byte[] data, int width, int height) {
        int luminanceLength = width * height;
        if (data == null || width <= 0 || height <= 0 || data.length < luminanceLength || luminanceLength < 64) {
            return false;
        }

        int sampleStep = Math.max(1, luminanceLength / 512);
        long sum = 0;
        int max = 0;
        int samples = 0;
        for (int index = 0; index < luminanceLength; index += sampleStep) {
            int value = data[index] & 0xff;
            sum += value;
            if (value > max) {
                max = value;
            }
            samples++;
        }
        if (samples == 0) {
            return false;
        }

        double average = (double) sum / samples;
        return average < 8.0 && max < 24;
    }

    private String encodeFileAsBase64(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
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
        } catch (Exception exception) {
            Log.e(TAG, "encodeFileAsBase64 failed", exception);
            return null;
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (Exception ignored) {
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

    private void safeRegisterCameraClient() {
        if (cameraClient == null) {
            return;
        }
        try {
            cameraClient.register();
        } catch (SecurityException securityException) {
            Log.w(TAG, "Default USBMonitor.register failed, trying Android 13+ compatible registration", securityException);
            tryManualUsbMonitorRegister();
        } catch (Exception exception) {
            Log.w(TAG, "cameraClient.register failed", exception);
        }
    }

    private void scheduleReconnect() {
        if (!autoReconnectEnabled || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        pendingReconnect = () -> {
            reconnectScheduled = false;
            if (!autoReconnectEnabled) {
                return;
            }
            refreshCurrentDeviceReference();
            if (currentDevice == null) {
                Log.w(TAG, "Automatic reconnect skipped: no matching USB device currently available");
                return;
            }
            Log.i(TAG, "Attempting automatic reconnect for device " + currentDevice.getDeviceName());
            openingCamera = true;
            ensureCameraClient();
            safeRegisterCameraClient();
            cameraClient.requestPermission(currentDevice);
        };
        Log.i(TAG, "Scheduling automatic reconnect in " + RECONNECT_DELAY_MS + " ms");
        mainHandler.postDelayed(pendingReconnect, RECONNECT_DELAY_MS);
    }

    private void clearReconnect() {
        if (pendingReconnect != null) {
            mainHandler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
        reconnectScheduled = false;
    }

    private boolean shouldRetryOpen(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("nativeconnect")
                || normalized.contains("result=-99")
                || normalized.contains("returned -99");
    }

    private void scheduleOpenRetry() {
        if (openRetryCount >= MAX_OPEN_RETRIES) {
            Log.w(TAG, "UVC open retry limit reached");
            openingCamera = false;
            openRetryCount = 0;
            if (openCallback != null) {
                openCallback.error("Failed to open UVC camera after retries");
                openCallback = null;
            }
            return;
        }

        openRetryCount++;
        closeCurrentCamera(false);
        refreshCurrentDeviceReference();
        if (currentDevice == null || cameraClient == null) {
            openingCamera = false;
            openRetryCount = 0;
            if (openCallback != null) {
                openCallback.error("Unable to retry opening UVC camera: device not available");
                openCallback = null;
            }
            return;
        }

        Log.i(TAG, "Scheduling UVC open retry " + openRetryCount + " in " + OPEN_RETRY_DELAY_MS + " ms for " + currentDevice.getDeviceName());
        mainHandler.postDelayed(() -> {
            if (cameraClient == null || currentDevice == null) {
                return;
            }
            ensureCameraClient();
            safeRegisterCameraClient();
            cameraClient.requestPermission(currentDevice);
        }, OPEN_RETRY_DELAY_MS);
    }

    private void tryManualUsbMonitorRegister() {
        try {
            Field usbMonitorField = MultiCameraClient.class.getDeclaredField("mUsbMonitor");
            usbMonitorField.setAccessible(true);
            Object usbMonitor = usbMonitorField.get(cameraClient);
            if (!(usbMonitor instanceof USBMonitor)) {
                Log.w(TAG, "Unable to access USBMonitor for manual registration");
                return;
            }

            USBMonitor monitor = (USBMonitor) usbMonitor;
            Context context = cordova.getActivity();

            Field permissionIntentField = USBMonitor.class.getDeclaredField("mPermissionIntent");
            permissionIntentField.setAccessible(true);
            PendingIntent permissionIntent = (PendingIntent) permissionIntentField.get(monitor);

            Field actionField = USBMonitor.class.getDeclaredField("ACTION_USB_PERMISSION");
            actionField.setAccessible(true);
            String actionUsbPermission = (String) actionField.get(monitor);

            if (permissionIntent == null) {
                int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0;
                permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(actionUsbPermission), flags);
                permissionIntentField.set(monitor, permissionIntent);
            }

            Field receiverField = USBMonitor.class.getDeclaredField("mUsbReceiver");
            receiverField.setAccessible(true);
            BroadcastReceiver usbReceiver = (BroadcastReceiver) receiverField.get(monitor);

            IntentFilter filter = new IntentFilter(actionUsbPermission);
            filter.addAction(USBMonitor.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(usbReceiver, filter);
            }

            Field deviceCountsField = USBMonitor.class.getDeclaredField("mDeviceCounts");
            deviceCountsField.setAccessible(true);
            deviceCountsField.setInt(monitor, 0);

            Field asyncHandlerField = USBMonitor.class.getDeclaredField("mAsyncHandler");
            asyncHandlerField.setAccessible(true);
            Handler asyncHandler = (Handler) asyncHandlerField.get(monitor);

            Field deviceCheckRunnableField = USBMonitor.class.getDeclaredField("mDeviceCheckRunnable");
            deviceCheckRunnableField.setAccessible(true);
            Runnable deviceCheckRunnable = (Runnable) deviceCheckRunnableField.get(monitor);

            asyncHandler.postDelayed(deviceCheckRunnable, 1000);
            Log.i(TAG, "Manual USBMonitor registration completed");
        } catch (Exception reflectionException) {
            Log.e(TAG, "Manual USBMonitor registration failed", reflectionException);
        }
    }
}
