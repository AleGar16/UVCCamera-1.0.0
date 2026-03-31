package com.cordova.plugin;

public class HighResPhotoResult {
    private final String base64Jpeg;
    private final int width;
    private final int height;
    private final long fileSizeBytes;
    private final String backendName;

    public HighResPhotoResult(String base64Jpeg, int width, int height, long fileSizeBytes, String backendName) {
        this.base64Jpeg = base64Jpeg;
        this.width = width;
        this.height = height;
        this.fileSizeBytes = fileSizeBytes;
        this.backendName = backendName;
    }

    public String getBase64Jpeg() {
        return base64Jpeg;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getBackendName() {
        return backendName;
    }
}
