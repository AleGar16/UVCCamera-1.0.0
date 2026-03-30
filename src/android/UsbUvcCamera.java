package com.cordova.plugin;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.camera.CameraUVC;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.usb.USBMonitor;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class UsbUvcCamera extends CordovaPlugin {
    private static final String TAG = "UsbUvcCamera";
    private MultiCameraClient cameraClient;
    private CameraUVC currentCamera;
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
            try {
                cameraClient.register();
            } catch (Exception ignored) {}
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
                ensureCameraClient();
                if (cameraClient != null) {
                    cameraClient.register();
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

        openCallback = callbackContext;
        ensureCameraClient();
        cordova.getActivity().runOnUiThread(this::ensurePreviewView);

        UsbDevice device = selectPreferredDevice(options);
        if (device == null) {
            callbackContext.error("No compatible USB UVC camera found");
            return true;
        }

        currentDevice = device;
        if (cameraClient != null) {
            cameraClient.register();
            cameraClient.requestPermission(device);
        } else {
            callbackContext.error("MultiCameraClient not initialized");
        }
        return true;
    }

    private boolean takePhoto(CallbackContext callbackContext) {
        if (currentCamera == null || !currentCamera.isCameraOpened()) {
            callbackContext.error("USB UVC camera not opened");
            return true;
        }

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

        currentCamera.captureImage(new ICaptureCallBack() {
            @Override
            public void onBegin() {
                Log.d(TAG, "UVC capture started");
            }

            @Override
            public void onError(String error) {
                if (photoCallback != null) {
                    photoCallback.error(error != null ? error : "UVC capture failed");
                    photoCallback = null;
                }
            }

            @Override
            public void onComplete(String path) {
                if (photoCallback != null) {
                    photoCallback.success(path != null ? path : photoFile.getAbsolutePath());
                    photoCallback = null;
                }
            }
        }, photoFile.getAbsolutePath());
        return true;
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
                    releaseCamera();
                }
            }

            @Override
            public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                if (device == null || ctrlBlock == null) {
                    return;
                }
                if (currentDevice == null || device.getDeviceId() != currentDevice.getDeviceId()) {
                    return;
                }
                pendingOpenDevice = device;
                pendingCtrlBlock = ctrlBlock;
                maybeOpenPendingDevice();
            }

            @Override
            public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                Log.d(TAG, "USB disconnect callback: " + (device != null ? device.getDeviceName() : "null"));
                if (device != null && currentDevice != null && device.getDeviceId() == currentDevice.getDeviceId()) {
                    releaseCamera();
                }
            }

            @Override
            public void onCancelDev(UsbDevice device) {
                if (openCallback != null) {
                    openCallback.error("USB permission request canceled");
                    openCallback = null;
                }
            }
        });
        cameraClient.register();
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
        List<UsbDevice> devices = cameraClient.getDeviceList();
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
            releaseCamera();
            currentCamera = new CameraUVC(cordova.getActivity(), device);
            currentCamera.setUsbControlBlock(ctrlBlock);
            currentCamera.setCameraStateCallBack(new ICameraStateCallBack() {
                @Override
                public void onCameraState(MultiCameraClient.ICamera self, State code, String msg) {
                    if (code == State.OPENED) {
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
                        if (openCallback != null) {
                            openCallback.error(msg != null ? msg : "Failed to open UVC camera");
                            openCallback = null;
                        }
                    } else if (code == State.CLOSED) {
                        Log.d(TAG, "UVC camera closed: " + msg);
                    }
                }
            });

            CameraRequest request = new CameraRequest.Builder()
                    .setPreviewWidth(previewWidth)
                    .setPreviewHeight(previewHeight)
                    .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                    .create();
            currentCamera.openCamera(previewView, request);
            pendingOpenDevice = null;
            pendingCtrlBlock = null;
        } catch (Exception e) {
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
        if (currentCamera != null) {
            try {
                currentCamera.closeCamera();
            } catch (Exception ignored) {
            }
            currentCamera = null;
        }
    }

    private void maybeOpenPendingDevice() {
        if (!previewSurfaceReady || pendingOpenDevice == null || pendingCtrlBlock == null) {
            return;
        }
        UsbDevice device = pendingOpenDevice;
        USBMonitor.UsbControlBlock ctrlBlock = pendingCtrlBlock;
        pendingOpenDevice = null;
        pendingCtrlBlock = null;
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
}
