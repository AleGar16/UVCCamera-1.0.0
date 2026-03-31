package com.cordova.plugin;

import com.jiangdg.ausbc.MultiCameraClient;

public interface AusbcCameraHandleProvider {
    MultiCameraClient.Camera getCurrentCamera();
}
