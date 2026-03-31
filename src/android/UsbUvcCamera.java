package com.cordova.plugin;

import android.app.PendingIntent;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class UsbUvcCamera extends CordovaPlugin {
    private static final String TAG = "UsbUvcCamera";
    private static final int UVC_EXPOSURE_MODE_MANUAL = 1;
    private static final int UVC_EXPOSURE_MODE_AUTO = 2;
    private static final int MAX_TAKE_PHOTO_ATTEMPTS = 6;
    private static final int TAKE_PHOTO_RETRY_DELAY_MS = 350;
    private static final int TAKE_PHOTO_TIMEOUT_MS = 6000;
    private static final int HIGH_RES_CAPTURE_POLL_INTERVAL_MS = 200;
    private static final int HIGH_RES_CAPTURE_MIN_BYTES = 4096;
    private static final int RECONNECT_DELAY_MS = 1200;
    private static final int OPEN_RETRY_DELAY_MS = 600;
    private static final int MAX_OPEN_RETRIES = 2;
    private MultiCameraClient cameraClient;
    private MultiCameraClient.Camera currentCamera;
    private UsbDevice currentDevice;
    private AspectRatioTextureView previewView;
    private ViewGroup previewContainer;
    private CallbackContext openCallback;
    private CallbackContext photoCallback;
    private int previewWidth = 1280;
    private int previewHeight = 720;
    private boolean preferHighestResolution = true;
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
    private final Object previewFrameLock = new Object();
    private byte[] latestPreviewFrame;
    private List<PreviewSize> currentPreviewSizes = new ArrayList<>();

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        cordova.getActivity().runOnUiThread(this::ensurePreviewView);
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
            case "setAutoFocus":
                return setAutoFocus(args, callbackContext);
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

        Log.i(TAG, "open requested with width=" + previewWidth + ", height=" + previewHeight + ", preferHighestResolution=" + preferHighestResolution);

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
        Log.i(TAG, "takePhoto requested");
        photoCallback = callbackContext;
        String fileName = "USB_UVC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".jpg";
        File baseDir = cordova.getActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (baseDir == null) {
            callbackContext.error("External files directory not available");
            photoCallback = null;
            return true;
        }
        File storageDir = new File(baseDir, "UsbUvcCamera");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        File photoFile = new File(storageDir, fileName);
        Log.i(TAG, "takePhoto target file: " + photoFile.getAbsolutePath());

        schedulePhotoTimeout();
        attemptHighResTakePhoto(photoFile, 1);
        return true;
    }

    private void attemptHighResTakePhoto(File photoFile, int attempt) {
        Log.d(TAG, "attemptHighResTakePhoto attempt=" + attempt + ", currentCamera=" + (currentCamera != null) + ", currentDevice=" + (currentDevice != null ? currentDevice.getDeviceName() : "null"));
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

        if (photoFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            photoFile.delete();
        }

        try {
            Log.i(TAG, "Starting high-res captureImage flow");
            currentCamera.captureImage(new ICaptureCallBack() {
                @Override
                public void onBegin() {
                    Log.i(TAG, "High-res capture onBegin");
                }

                @Override
                public void onComplete(String path) {
                    Log.i(TAG, "High-res capture onComplete path=" + path);
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "High-res capture onError " + error);
                }
            }, photoFile.getAbsolutePath());
            pollHighResCaptureFile(photoFile, 1);
        } catch (Exception exception) {
            Log.w(TAG, "High-res captureImage flow failed, falling back to preview frame", exception);
            attemptTakePhoto(photoFile, 1);
        }
    }

    private void pollHighResCaptureFile(File photoFile, int pollAttempt) {
        if (photoFile.exists() && photoFile.length() >= HIGH_RES_CAPTURE_MIN_BYTES) {
            int[] dimensions = decodeImageDimensions(photoFile);
            Log.i(TAG, "High-res capture file detected size=" + photoFile.length()
                    + ", width=" + dimensions[0]
                    + ", height=" + dimensions[1]);
            cordova.getThreadPool().execute(() -> {
                String encodedImage = encodeFileAsBase64(photoFile);
                mainHandler.post(() -> {
                    if (encodedImage == null) {
                        Log.e(TAG, "High-res capture file encoding failed, falling back to preview");
                        attemptTakePhoto(photoFile, 1);
                        return;
                    }
                    Log.i(TAG, "High-res capture file base64 encoding complete");
                    clearPhotoTimeout();
                    if (photoCallback != null) {
                        photoCallback.success(encodedImage);
                        photoCallback = null;
                    }
                });
            });
            return;
        }

        int elapsedMs = pollAttempt * HIGH_RES_CAPTURE_POLL_INTERVAL_MS;
        if (elapsedMs >= TAKE_PHOTO_TIMEOUT_MS) {
            Log.w(TAG, "High-res capture file not available within timeout, falling back to preview frame");
            attemptTakePhoto(photoFile, 1);
            return;
        }

        mainHandler.postDelayed(() -> pollHighResCaptureFile(photoFile, pollAttempt + 1), HIGH_RES_CAPTURE_POLL_INTERVAL_MS);
    }

    private void attemptTakePhoto(File photoFile, int attempt) {
        Log.d(TAG, "attemptTakePhoto attempt=" + attempt + ", currentCamera=" + (currentCamera != null) + ", currentDevice=" + (currentDevice != null ? currentDevice.getDeviceName() : "null"));
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
        synchronized (previewFrameLock) {
            frameCopy = latestPreviewFrame != null ? latestPreviewFrame.clone() : null;
        }

        if (frameCopy == null) {
            if (attempt >= MAX_TAKE_PHOTO_ATTEMPTS) {
                Log.w(TAG, "No preview frame available after retries");
                failPendingPhoto("No preview frame available");
                return;
            }
            Log.d(TAG, "Preview frame not ready yet, retry attempt " + attempt);
            mainHandler.postDelayed(() -> attemptTakePhoto(photoFile, attempt + 1), TAKE_PHOTO_RETRY_DELAY_MS);
            return;
        }

        PreviewSize frameSize = resolvePreviewSizeForFrame(frameCopy.length);
        if (frameSize == null) {
            Log.e(TAG, "Unable to resolve preview size for frame length " + frameCopy.length);
            failPendingPhoto("Unable to resolve preview size");
            return;
        }

        Log.i(TAG, "Encoding preview frame as base64 JPEG using size " + frameSize.getWidth() + "x" + frameSize.getHeight());
        cordova.getThreadPool().execute(() -> {
            String encodedImage = encodePreviewFrameAsBase64(
                    frameCopy,
                    frameSize.getWidth(),
                    frameSize.getHeight()
            );
            mainHandler.post(() -> {
                if (encodedImage == null) {
                    Log.e(TAG, "Preview frame base64 encoding failed");
                    failPendingPhoto("Failed to encode preview frame");
                    return;
                }
                Log.i(TAG, "Preview frame base64 encoding complete");
                clearPhotoTimeout();
                if (photoCallback != null) {
                    photoCallback.success(encodedImage);
                    photoCallback = null;
                }
            });
        });
    }

    private void failPendingPhoto(String message) {
        clearPhotoTimeout();
        if (photoCallback != null) {
            photoCallback.error(message);
            photoCallback = null;
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

    private boolean listUsbDevices(CallbackContext callbackContext) {
        try {
            UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                callbackContext.error("UsbManager not available");
                return true;
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

            uvcCamera.updateCameraParams();

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
            support.put("gain", uvcCamera.checkSupportFlag(UVCCamera.PU_GAIN));
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
            current.put("gain", uvcCamera.getGain());
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
            ranges.put("gain", buildRangeJson(uvcCamera, "mGainMin", "mGainMax", "mGainDef"));
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

    private boolean applyStableCameraProfile(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }

            JSONObject options = args.optJSONObject(0);
            boolean autoFocus = options != null && options.has("autoFocus") ? options.optBoolean("autoFocus", false) : false;
            int focus = options != null ? clampPercent(options.optInt("focus", 0)) : 0;
            boolean autoExposure = options != null && options.has("autoExposure") ? options.optBoolean("autoExposure", true) : true;
            int exposure = options != null ? clampPercent(options.optInt("exposure", 50)) : 50;
            boolean autoWhiteBalance = options != null && options.has("autoWhiteBalance") ? options.optBoolean("autoWhiteBalance", true) : true;
            int whiteBalance = options != null ? clampPercent(options.optInt("whiteBalance", 50)) : 50;
            int brightness = options != null ? clampPercent(options.optInt("brightness", 50)) : 50;
            int contrast = options != null ? clampPercent(options.optInt("contrast", 50)) : 50;
            int sharpness = options != null ? clampPercent(options.optInt("sharpness", 50)) : 50;

            uvcCamera.setAutoFocus(autoFocus);
            if (!autoFocus) {
                uvcCamera.setFocus(focus);
            }
            setAutoExposureInternal(uvcCamera, autoExposure);
            if (!autoExposure) {
                setExposureInternal(uvcCamera, exposure);
            }
            uvcCamera.setAutoWhiteBlance(autoWhiteBalance);
            if (!autoWhiteBalance) {
                uvcCamera.setWhiteBlance(whiteBalance);
            }
            uvcCamera.setBrightness(brightness);
            uvcCamera.setContrast(contrast);
            uvcCamera.setSharpness(sharpness);

            callbackContext.success("stable-profile-applied");
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
        previewView.setAlpha(0.01f);
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                previewSurfaceReady = true;
                maybeOpenPendingDevice();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
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

        int width = previewVisible ? previewViewWidth : 1;
        int height = previewVisible ? previewViewHeight : 1;
        int leftMargin = previewVisible ? previewViewX : 0;
        int topMargin = previewVisible ? previewViewY : 0;
        float alpha = previewVisible ? 1.0f : 0.01f;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = leftMargin;
        params.topMargin = topMargin;

        // Keep the TextureView attached and visible to let Android create/retain
        // the underlying SurfaceTexture even when the preview is "hidden" for the user.
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
        Log.i(TAG, "applyPreviewLayout visible=" + previewVisible + ", x=" + leftMargin + ", y=" + topMargin + ", width=" + width + ", height=" + height);
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
            currentCamera.addPreviewDataCallBack(new IPreviewDataCallBack() {
                @Override
                public void onPreviewData(byte[] data, DataFormat format) {
                    if (data == null || format != DataFormat.NV21) {
                        return;
                    }
                    synchronized (previewFrameLock) {
                        latestPreviewFrame = data.clone();
                    }
                }
            });
            currentCamera.setUsbControlBlock(ctrlBlock);
            currentCamera.setCameraStateCallBack(new ICameraStateCallBack() {
                @Override
                public void onCameraState(MultiCameraClient.Camera self, State code, String msg) {
                    Log.i(TAG, "onCameraState code=" + code + ", msg=" + msg);
                    if (code == State.OPENED) {
                        currentPreviewSizes = self.getAllPreviewSizes(null);
                        logPreviewSizes("available-preview-sizes", currentPreviewSizes);
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

            CameraRequest request = new CameraRequest.Builder()
                    .setPreviewWidth(previewWidth)
                    .setPreviewHeight(previewHeight)
                    .create();
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
        closeCurrentCamera(true);
    }

    private void closeCurrentCamera(boolean resetOpeningFlag) {
        if (currentCamera != null) {
            try {
                Log.i(TAG, "Releasing currentCamera");
                currentCamera.closeCamera();
            } catch (Exception ignored) {
            }
            currentCamera = null;
        }
        synchronized (previewFrameLock) {
            latestPreviewFrame = null;
        }
        currentPreviewSizes = new ArrayList<>();
        if (resetOpeningFlag) {
            openingCamera = false;
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

    private PreviewSize resolvePreviewSizeForFrame(int frameLength) {
        int expectedPixels = (frameLength * 2) / 3;
        List<PreviewSize> sizes = currentPreviewSizes != null ? currentPreviewSizes : new ArrayList<>();

        PreviewSize requestedSize = findMatchingPreviewSize(sizes, previewWidth, previewHeight);
        if (requestedSize != null && requestedSize.getWidth() * requestedSize.getHeight() == expectedPixels) {
            Log.i(TAG, "resolvePreviewSizeForFrame matched requested preview size " + previewWidth + "x" + previewHeight);
            return requestedSize;
        }

        for (PreviewSize size : sizes) {
            if (size.getWidth() * size.getHeight() == expectedPixels) {
                Log.i(TAG, "resolvePreviewSizeForFrame matched available preview size " + size.getWidth() + "x" + size.getHeight());
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
                Log.i(TAG, "resolvePreviewSizeForFrame matched fallback size " + candidate[0] + "x" + candidate[1]);
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

    private PreviewSize resolveTargetPreviewSize(List<PreviewSize> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        PreviewSize requestedSize = findMatchingPreviewSize(sizes, requestedPreviewWidth, requestedPreviewHeight);
        if (!preferHighestResolution) {
            return requestedSize;
        }

        if (requestedSize != null) {
            return requestedSize;
        }

        int[][] preferredFallbacks = new int[][] {
                {1280, 720},
                {960, 720},
                {1024, 576},
                {864, 480},
                {800, 600},
                {640, 480}
        };
        for (int[] candidate : preferredFallbacks) {
            PreviewSize candidateSize = findMatchingPreviewSize(sizes, candidate[0], candidate[1]);
            if (candidateSize != null) {
                return candidateSize;
            }
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

    private boolean setAutoFocus(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
            }
            boolean enabled = args.optBoolean(0, true);
            uvcCamera.setAutoFocus(enabled);
            callbackContext.success("ok");
        } catch (Exception exception) {
            callbackContext.error("setAutoFocus failed: " + exception.getMessage());
        }
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
            setAutoExposureInternal(uvcCamera, enabled);
            JSONObject result = new JSONObject();
            result.put("requested", enabled);
            result.put("applied", getAutoExposure(uvcCamera));
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
            callbackContext.success(result);
        } catch (Exception exception) {
            Log.e(TAG, "setExposure failed", exception);
            callbackContext.error("setExposure failed: " + exception.getMessage());
        }
        return true;
    }

    private boolean setAutoWhiteBalance(JSONArray args, CallbackContext callbackContext) {
        try {
            UVCCamera uvcCamera = requireOpenedUvcCamera(callbackContext);
            if (uvcCamera == null) {
                return true;
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

    private void setAutoExposureInternal(UVCCamera uvcCamera, boolean enabled) throws Exception {
        invokeDeclaredVoidMethod(uvcCamera, "nativeUpdateExposureModeLimit");
        invokeDeclaredVoidMethod(uvcCamera, "nativeSetExposureMode", enabled ? UVC_EXPOSURE_MODE_AUTO : UVC_EXPOSURE_MODE_MANUAL);
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
            int mode = invokeDeclaredIntMethod(uvcCamera, "nativeGetExposureMode");
            return mode != UVC_EXPOSURE_MODE_MANUAL;
        } catch (Exception exception) {
            Log.w(TAG, "getAutoExposure failed", exception);
            return true;
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
