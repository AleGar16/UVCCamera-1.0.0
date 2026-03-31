package com.cordova.plugin;

public class HighResPhotoRequest {
    private final int requestedWidth;
    private final int requestedHeight;
    private final int jpegQuality;
    private final String cameraId;
    private final String outputPath;
    private final int timeoutMs;

    public HighResPhotoRequest(int requestedWidth, int requestedHeight, int jpegQuality, String cameraId, String outputPath, int timeoutMs) {
        this.requestedWidth = requestedWidth;
        this.requestedHeight = requestedHeight;
        this.jpegQuality = jpegQuality;
        this.cameraId = cameraId;
        this.outputPath = outputPath;
        this.timeoutMs = timeoutMs;
    }

    public int getRequestedWidth() {
        return requestedWidth;
    }

    public int getRequestedHeight() {
        return requestedHeight;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    public String getCameraId() {
        return cameraId;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }
}
