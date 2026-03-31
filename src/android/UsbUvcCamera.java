package com.cordova.plugin;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.serenegiant.usb.USBMonitor;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class UsbUvcCamera extends CordovaPlugin {
    private static final String TAG = "UsbUvcCamera";
    private static final int MAX_TAKE_PHOTO_ATTEMPTS = 6;
    private static final int TAKE_PHOTO_RETRY_DELAY_MS = 350;
    private static final int TAKE_PHOTO_TIMEOUT_MS = 6000;
    private MultiCameraClient cameraClient;
    private MultiCameraClient.Camera currentCamera;
    private UsbDevice currentDevice;
    private AspectRatioTextureView previewView;
    private ViewGroup previewContainer;
    private CallbackContext openCallback;
    private CallbackContext photoCallback;
    private int previewWidth = 1280;
    private int previewHeight = 720;
    private boolean previewSurfaceReady = false;
    private UsbDevice pendingOpenDevice;
    private USBMonitor.UsbControlBlock pendingCtrlBlock;
    private boolean openingCamera = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingPhotoTimeout;

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
                ensureCameraClient();
                if (cameraClient != null) {
                    safeRegisterCameraClient();
                    cameraClient.requestPermission(currentDevice);
                }
                return true;
            case "applyStableCameraProfile":
                callbackContext.success("noop");
                return true;
            case "listUsbDevices":
                return listUsbDevices(callbackContext);
            default:
                return false;
        }
    }

    private boolean open(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options != null) {
            previewWidth = options.optInt("width", 1280);
            previewHeight = options.optInt("height", 720);
        }

        Log.i(TAG, "open requested with width=" + previewWidth + ", height=" + previewHeight);

        openCallback = callbackContext;
        openingCamera = true;
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
        attemptTakePhoto(photoFile, 1);
        return true;
    }

    private void attemptTakePhoto(File photoFile, int attempt) {
        Log.d(TAG, "attemptTakePhoto attempt=" + attempt + ", currentCamera=" + (currentCamera != null) + ", currentDevice=" + (currentDevice != null ? currentDevice.getDeviceName() : "null"));
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

        Log.i(TAG, "captureImage starting on attempt " + attempt);
        currentCamera.captureImage(new ICaptureCallBack() {
            @Override
            public void onBegin() {
                Log.i(TAG, "UVC capture onBegin");
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "UVC capture onError: " + error);
                clearPhotoTimeout();
                if (photoCallback != null) {
                    photoCallback.error(error != null ? error : "UVC capture failed");
                    photoCallback = null;
                }
            }

            @Override
            public void onComplete(String path) {
                Log.i(TAG, "UVC capture onComplete: " + path);
                clearPhotoTimeout();
                if (photoCallback != null) {
                    photoCallback.success(path != null ? path : photoFile.getAbsolutePath());
                    photoCallback = null;
                }
            }
        }, photoFile.getAbsolutePath());
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

    private void ensureCameraClient() {
        if (cameraClient != null) {
            return;
        }
        cameraClient = new MultiCameraClient(cordova.getActivity(), new IDeviceConnectCallBack() {
            @Override
            public void onAttachDev(UsbDevice device) {
                Log.d(TAG, "USB attach: " + (device != null ? device.getDeviceName() : "null"));
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
            currentCamera.setUsbControlBlock(ctrlBlock);
            currentCamera.setCameraStateCallBack(new ICameraStateCallBack() {
                @Override
                public void onCameraState(MultiCameraClient.Camera self, State code, String msg) {
                    Log.i(TAG, "onCameraState code=" + code + ", msg=" + msg);
                    if (code == State.OPENED) {
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
                        openingCamera = false;
                        if (openCallback != null) {
                            openCallback.error(msg != null ? msg : "Failed to open UVC camera");
                            openCallback = null;
                        }
                    } else if (code == State.CLOSED) {
                        openingCamera = false;
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
            openingCamera = false;
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
